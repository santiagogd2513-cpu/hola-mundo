package com.libertyv.core.rpf

import com.libertyv.core.ConsolePlatform
import com.libertyv.core.compression.XMemCompressOutputStream
import com.libertyv.core.io.DataSource
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class SaveProgress(val currentName: String, val doneBytes: Long, val totalBytes: Long)

/**
 * Escribe un RPF7 completo, igual que LibertyV:
 * - Los archivos que no han cambiado se copian byte a byte (sin recomprimir).
 * - Los importados se comprimen (Deflate en PS3, LZX/XMemCompress en Xbox 360) y,
 *   si van comprimidos, se cifran con AES. Los scripts (.xsc/.csc) siempre van cifrados.
 * - Los hijos de cada carpeta se guardan en orden ordinal; la tabla de entradas va en anchura.
 * - Cada archivo empieza en un múltiplo de 512 bytes.
 */
object Rpf7Writer {
    private const val DIRECTORY_MARK = 0x7FFFFF
    private const val MAX_24 = 0xFFFFFF

    private class Info(var f1: Int = 0, var f2: Int = 0, var f3: Int = 0, var f4: Int = 0, var f5: Int = 0, var f6: Int = 0)

    /** Escribe el RPF en [channel] (se vacía antes). [onProgress] puede lanzar para cancelar. */
    fun write(archive: Rpf7Archive, channel: FileChannel, onProgress: (SaveProgress) -> Unit = {}) {
        val root = archive.root

        // Orden de los datos y de los nombres: preorden (como LibertyV).
        val list = ArrayList<RpfEntry>()
        fun pre(e: RpfEntry) {
            list.add(e)
            if (e is RpfDirectory) e.ordinalChildren.forEach { pre(it) }
        }
        pre(root)

        // Orden de la tabla: en anchura; los hijos de cada carpeta quedan seguidos.
        val bfs = ArrayList<RpfEntry>()
        val firstChild = IdentityHashMap<RpfEntry, Int>()
        val queue = ArrayDeque<RpfEntry>()
        queue.add(root)
        var next = 1
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst()
            bfs.add(e)
            if (e is RpfDirectory) {
                val kids = e.ordinalChildren
                firstChild[e] = next
                next += kids.size
                queue.addAll(kids)
            }
        }

        val info = IdentityHashMap<RpfEntry, Info>()
        list.forEach { info[it] = Info(f1 = if (it is RpfResource) 1 else 0) }

        // Nombres.
        val totalNameLen = list.sumOf { it.name.length }
        var shift = -1
        for (i in 0 until 8) {
            if (totalNameLen.toLong() + list.size.toLong() * (i + 1) < (1L shl (16 + i))) {
                shift = i
                break
            }
        }
        if (shift < 0) throw RpfException("Demasiados archivos o nombres demasiado largos para un RPF")
        val names = ByteArrayOutputStream()
        val nameOffsets = HashMap<String, Int>()
        for (e in list) {
            val off = nameOffsets.getOrPut(e.name) {
                val o = names.size()
                names.write(e.name.toByteArray(Charsets.ISO_8859_1))
                names.write(0)
                while (names.size() % (1 shl shift) != 0) names.write(0)
                o
            }
            info[e]!!.f4 = off ushr shift
        }
        while (names.size() % 16 != 0) names.write(0)
        val namesBytes = names.toByteArray()
        if (namesBytes.size > 0xFFFFFFF) throw RpfException("La tabla de nombres es demasiado grande")

        // Datos.
        val files = list.filterIsInstance<RpfFileEntry>()
        val totalBytes = files.sumOf { it.size }
        var doneBytes = 0L
        val headerLen = 16L + 16L * bfs.size + namesBytes.size
        channel.truncate(0)
        val out = PositionedOutputStream(channel, align512(headerLen))
        out.padTo(align512(headerLen))

        for (e in list) {
            val inf = info[e]!!
            if (e is RpfDirectory) {
                inf.f2 = DIRECTORY_MARK
                inf.f3 = 0
                inf.f5 = firstChild[e]!!
                inf.f6 = e.ordinalChildren.size
                continue
            }
            e as RpfFileEntry
            val start = out.position
            if ((start ushr 9) >= DIRECTORY_MARK) throw RpfException("El RPF sería demasiado grande (más de 4 GB)")
            val base = doneBytes
            onProgress(SaveProgress(e.path, base, totalBytes))
            writeEntryData(archive, e, NonClosingOutputStream(out)) { n ->
                onProgress(SaveProgress(e.path, base + n, totalBytes))
            }
            val stored = out.position - start
            out.padTo(align512(out.position))
            doneBytes = base + e.size

            inf.f2 = (start ushr 9).toInt()
            when (e) {
                is RpfResource -> {
                    if (stored >= MAX_24) throw RpfException("“${e.path}” comprimido ocupa demasiado (máx. 16 MB)")
                    inf.f3 = stored.toInt()
                    inf.f5 = e.systemFlag
                    inf.f6 = e.graphicsFlag
                }
                is RpfRegularFile -> {
                    if (e.isCompressed) {
                        if (stored > MAX_24) throw RpfException("“${e.path}” comprimido ocupa demasiado (máx. 16 MB)")
                        if (stored == 0L) throw RpfException("“${e.path}”: datos comprimidos vacíos")
                        inf.f3 = stored.toInt()
                        inf.f5 = e.uncompressedSize.toInt()
                        inf.f6 = if (e.isEncrypted) 1 else 0
                    } else {
                        if (stored > 0xFFFFFFFFL) throw RpfException("“${e.path}” es demasiado grande")
                        inf.f3 = 0
                        inf.f5 = stored.toInt()
                        inf.f6 = if (e.isEncrypted) 1 else 0
                    }
                }
            }
        }
        out.flush()
        val end = out.position

        // Cabecera + tabla cifrada.
        val toc = ByteArray((16L * bfs.size + namesBytes.size).toInt())
        bfs.forEachIndexed { i, e ->
            val inf = info[e]!!
            val o = i * 16
            val f12 = ((inf.f1 and 1) shl 23) or (inf.f2 and 0x7FFFFF)
            toc[o] = (f12 ushr 16).toByte(); toc[o + 1] = (f12 ushr 8).toByte(); toc[o + 2] = f12.toByte()
            toc[o + 3] = (inf.f3 ushr 16).toByte(); toc[o + 4] = (inf.f3 ushr 8).toByte(); toc[o + 5] = inf.f3.toByte()
            toc[o + 6] = (inf.f4 ushr 8).toByte(); toc[o + 7] = inf.f4.toByte()
            putBe32(toc, o + 8, inf.f5)
            putBe32(toc, o + 12, inf.f6)
        }
        namesBytes.copyInto(toc, 16 * bfs.size)
        val rounds = if ((archive.headerFlag ushr 28) == 15) 16 else 1
        archive.aes.encryptInPlace(toc, 0, toc.size, rounds)

        val header = ByteArray(16)
        "RPF7".toByteArray(Charsets.ISO_8859_1).copyInto(header)
        putBe32(header, 4, bfs.size)
        putBe32(header, 8, (archive.platform.platformBit shl 31) or ((shift and 7) shl 28) or namesBytes.size)
        putBe32(header, 12, archive.headerFlag)

        channel.write(ByteBuffer.wrap(header), 0)
        channel.write(ByteBuffer.wrap(toc), 16)
        channel.truncate(end)
        channel.force(false)
        onProgress(SaveProgress("", totalBytes, totalBytes))
    }

    /** Bytes de un archivo tal como van dentro del RPF. */
    internal fun writeEntryData(archive: Rpf7Archive, e: RpfFileEntry, out: OutputStream, progress: ((Long) -> Unit)?) {
        val imported = e.imported
        if (imported == null) {
            // Sin cambios: copia exacta.
            archive.source.slice(e.offset, e.storedSize).openStream().use { copy(it, out, progress) }
            return
        }
        when (e) {
            is RpfRegularFile -> {
                if (!e.isCompressed) {
                    imported.openStream().use { copy(it, out, progress) }
                } else {
                    val sink = NonClosingOutputStream(out)
                    val enc = if (e.isEncrypted) archive.aes.encryptingStream(sink) else sink
                    compressing(archive.platform, enc).use { c -> imported.openStream().use { copy(it, c, progress) } }
                }
            }
            is RpfResource -> {
                out.write(ByteArray(16)) // LibertyV deja la cabecera interna a cero
                val sink = NonClosingOutputStream(out)
                val enc = if (e.isEncrypted) archive.aes.encryptingStream(sink) else sink
                compressing(archive.platform, enc).use { c -> imported.openStream().use { copy(it, c, progress) } }
            }
        }
    }

    fun compressing(platform: ConsolePlatform, out: OutputStream): OutputStream = when (platform) {
        ConsolePlatform.PS3 -> object : DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION, true), 64 * 1024) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    def.end()
                }
            }
        }
        ConsolePlatform.XBOX360 -> XMemCompressOutputStream(out)
    }

    private fun copy(input: InputStream, out: OutputStream, progress: ((Long) -> Unit)?) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            progress?.invoke(total)
        }
    }

    private fun align512(p: Long) = (p + 511) / 512 * 512

    /**
     * Comprueba un RPF recién escrito: misma estructura que el modelo y, para los archivos
     * importados, mismo contenido (CRC32) tras descomprimir y descifrar.
     * Devuelve la lista de problemas (vacía = todo bien).
     */
    fun verify(
        model: Rpf7Archive,
        written: DataSource,
        key: ByteArray,
        onProgress: (SaveProgress) -> Unit = {},
    ): List<String> {
        val problems = ArrayList<String>()
        val check = Rpf7Archive.open(written, model.platform, key, model.name)
        val files = model.root.allFiles()
        val toCheck = files.filter { it.isImported }
        val total = toCheck.sumOf { it.size }
        var done = 0L
        fun walk(a: RpfDirectory, b: RpfDirectory) {
            if (a.children.size != b.children.size) problems.add("“${a.path}”: número de elementos distinto")
            for (c in a.children) {
                val other = b.get(c.name)
                if (other == null) {
                    problems.add("Falta “${c.path}”")
                    continue
                }
                when (c) {
                    is RpfDirectory -> if (other is RpfDirectory) walk(c, other) else problems.add("“${c.path}” cambió de tipo")
                    is RpfFileEntry -> {
                        if (other !is RpfFileEntry || other.javaClass != c.javaClass) {
                            problems.add("“${c.path}” cambió de tipo")
                        } else if (other.size != c.size) {
                            problems.add("“${c.path}”: tamaño distinto")
                        } else if (c.isImported) {
                            val base = done
                            val crcA = crc(model.openContent(c)) {}
                            val crcB = crc(check.openContent(other)) { n -> onProgress(SaveProgress(c.path, base + n, total)) }
                            if (crcA != crcB) problems.add("“${c.path}”: el contenido no coincide al leerlo de nuevo")
                            done = base + c.size
                        }
                    }
                }
            }
        }
        walk(model.root, check.root)
        return problems
    }

    private fun crc(input: InputStream, progress: (Long) -> Unit): Long = input.use {
        val c = CRC32()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            c.update(buf, 0, n)
            total += n
            progress(total)
        }
        c.value
    }
}

/** Escribe en un FileChannel a partir de una posición, con búfer. */
private class PositionedOutputStream(private val channel: FileChannel, start: Long) : OutputStream() {
    private val buf = ByteBuffer.allocate(1 shl 20)
    var position: Long = start
        private set
    private var flushedPos = start

    override fun write(b: Int) {
        if (!buf.hasRemaining()) flush()
        buf.put(b.toByte())
        position++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var l = len
        while (l > 0) {
            if (!buf.hasRemaining()) flush()
            val n = minOf(l, buf.remaining())
            buf.put(b, o, n)
            o += n
            l -= n
            position += n
        }
    }

    fun padTo(target: Long) {
        while (position < target) {
            val n = minOf(target - position, 4096L).toInt()
            write(ByteArray(n))
        }
    }

    override fun flush() {
        buf.flip()
        while (buf.hasRemaining()) flushedPos += channel.write(buf, flushedPos)
        buf.clear()
    }
}

private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = out.flush()
}
