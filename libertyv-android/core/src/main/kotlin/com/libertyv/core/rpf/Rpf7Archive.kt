package com.libertyv.core.rpf

import com.libertyv.core.ConsolePlatform
import com.libertyv.core.compression.Compression
import com.libertyv.core.crypto.AesEcb
import com.libertyv.core.io.DataSource
import com.libertyv.core.io.be16
import com.libertyv.core.io.be32
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream

class RpfException(msg: String) : IOException(msg)

/**
 * Lector de archivos RPF7 (Grand Theft Auto V de PlayStation 3 y Xbox 360).
 *
 * Cabecera (16 bytes, big-endian): "RPF7", número de entradas,
 * [bit plataforma | desplazamiento de nombres (3 bits) | longitud de nombres (28 bits)], flags.
 * Después va la tabla de entradas (16 bytes cada una) y la de nombres, ambas cifradas con AES.
 */
class Rpf7Archive private constructor(
    val source: DataSource,
    val platform: ConsolePlatform,
    internal val aes: AesEcb,
    val name: String,
) : Closeable {
    lateinit var root: RpfDirectory
        private set

    var entryCount = 0
        private set

    var headerFlag = 0
        private set

    companion object {
        const val MAGIC = "RPF7"
        private const val DIRECTORY_MARK = 0x7FFFFF

        fun open(source: DataSource, platform: ConsolePlatform, key: ByteArray, name: String = ""): Rpf7Archive {
            val a = Rpf7Archive(source, platform, AesEcb(key), name)
            a.parse()
            return a
        }

        /** Mira la cabecera y dice de qué consola es el RPF (o null si no es un RPF7). */
        fun detectPlatform(source: DataSource): ConsolePlatform? {
            if (source.size < 16) return null
            val h = source.readFully(0, 16)
            if (String(h, 0, 4, Charsets.ISO_8859_1) != MAGIC) return null
            val bit = (be32(h, 8) ushr 31) and 1
            return ConsolePlatform.entries.firstOrNull { it.platformBit == bit }
        }
    }

    private fun parse() {
        if (source.size < 16) throw RpfException("El archivo es demasiado pequeño para ser un RPF")
        val h = source.readFully(0, 16)
        if (String(h, 0, 4, Charsets.ISO_8859_1) != MAGIC) {
            throw RpfException("No es un RPF7 (firma incorrecta)")
        }
        entryCount = be32(h, 4)
        val packed = be32(h, 8)
        val platformBit = (packed ushr 31) and 1
        val nameShift = (packed ushr 28) and 7
        val namesLength = packed and 0xFFFFFFF
        headerFlag = be32(h, 12)

        if (platformBit != platform.platformBit) {
            val other = ConsolePlatform.entries.first { it.platformBit == platformBit }
            throw RpfException("Este RPF es de ${other.displayName}, no de ${platform.displayName}")
        }
        if (entryCount <= 0) throw RpfException("RPF vacío: no tiene carpeta raíz")
        val tocLength = 16L * entryCount + namesLength
        if (16 + tocLength > source.size) throw RpfException("La tabla de entradas es más grande que el archivo")

        val toc = source.readFully(16, tocLength.toInt())
        val rounds = if ((headerFlag ushr 28) == 15) 16 else 1
        aes.decryptInPlace(toc, 0, toc.size, rounds)

        val namesOffset = 16 * entryCount
        val visited = BooleanArray(entryCount)

        fun readName(nameOffset: Int): String {
            val start = nameOffset shl nameShift
            if (start < 0 || start >= namesLength) throw RpfException("Nombre de entrada fuera de rango")
            var end = namesOffset + start
            val limit = namesOffset + namesLength
            while (end < limit && toc[end] != 0.toByte()) end++
            if (end >= limit) throw RpfException("Nombre de entrada sin terminar")
            return String(toc, namesOffset + start, end - (namesOffset + start), Charsets.ISO_8859_1)
        }

        fun parseEntry(index: Int, depth: Int): RpfEntry {
            if (index < 0 || index >= entryCount) throw RpfException("Índice de entrada inválido: $index")
            if (visited[index]) throw RpfException("Entrada repetida en la tabla (posible bucle)")
            if (depth > 64) throw RpfException("Demasiadas carpetas anidadas")
            visited[index] = true
            val o = index * 16
            val f12 = ((toc[o].toInt() and 0xFF) shl 16) or ((toc[o + 1].toInt() and 0xFF) shl 8) or (toc[o + 2].toInt() and 0xFF)
            val isResource = (f12 ushr 23) and 1 == 1
            val blockOffset = f12 and 0x7FFFFF
            val f3 = ((toc[o + 3].toInt() and 0xFF) shl 16) or ((toc[o + 4].toInt() and 0xFF) shl 8) or (toc[o + 5].toInt() and 0xFF)
            val nameOffset = be16(toc, o + 6)
            val f5 = be32(toc, o + 8)
            val f6 = be32(toc, o + 12)
            val name = readName(nameOffset)

            if (blockOffset == DIRECTORY_MARK) {
                if (isResource) throw RpfException("Entrada inválida (carpeta y recurso a la vez): $name")
                val first = f5
                val count = f6
                if (first < 0 || count < 0 || first.toLong() + count > entryCount) {
                    throw RpfException("Carpeta con hijos fuera de rango: $name")
                }
                val kids = (0 until count).map { parseEntry(first + it, depth + 1) }
                return RpfDirectory(name, kids)
            }

            val offset = blockOffset.toLong() shl 9
            if (offset > source.size) throw RpfException("Datos fuera del archivo: $name")

            if (isResource) {
                if (f3 == 0xFFFFFF) throw RpfException("Recurso con tamaño -1 (no soportado): $name")
                if (f3 < 16) throw RpfException("Recurso demasiado pequeño: $name")
                checkRange(name, offset, f3.toLong())
                return RpfResource(name, offset, f3.toLong(), f5, f6, platform)
            }

            return if (f3 == 0) {
                val size = f5.toLong() and 0xFFFFFFFFL
                checkRange(name, offset, size)
                RpfRegularFile(name, offset, size, size, isCompressed = false, isEncrypted = f6 != 0)
            } else {
                checkRange(name, offset, f3.toLong())
                RpfRegularFile(
                    name, offset, f3.toLong(), f5.toLong() and 0xFFFFFFFFL,
                    isCompressed = true, isEncrypted = f6 != 0,
                )
            }
        }

        val r = parseEntry(0, 0)
        root = r as? RpfDirectory ?: throw RpfException("La raíz del RPF no es una carpeta")
    }

    private fun checkRange(name: String, offset: Long, size: Long) {
        if (offset + size > source.size) throw RpfException("Datos fuera del archivo: $name")
    }

    /** true si hay cambios sin guardar. */
    var isModified = false
        private set

    /** Bytes tal como están guardados dentro del RPF (no vale para archivos importados sin guardar). */
    fun openStored(entry: RpfFileEntry): InputStream {
        if (entry.isImported) throw RpfException("“${entry.name}” es nuevo: guarda el RPF para tenerlo comprimido")
        return source.slice(entry.offset, entry.storedSize).openStream()
    }

    /** Si el archivo está guardado sin comprimir ni cifrar, sus datos directamente (útil para RPF dentro de RPF). */
    fun plainSlice(entry: RpfFileEntry): DataSource? {
        if (entry is RpfRegularFile && !entry.isCompressed && !entry.isEncrypted) {
            return entry.imported ?: source.slice(entry.offset, entry.storedSize)
        }
        if (entry is RpfRegularFile && entry.imported != null) return entry.imported
        return null
    }

    // ---------------------------------------------------------------- edición

    private fun checkName(dir: RpfDirectory, name: String, except: RpfEntry? = null) {
        if (name.isBlank()) throw RpfException("El nombre no puede estar vacío")
        if (name.any { it == '/' || it == '\\' || it.code < 32 || it.code > 126 }) {
            throw RpfException("El nombre “$name” tiene caracteres no permitidos (solo letras sin tilde, números y símbolos básicos)")
        }
        if (name.length > 255) throw RpfException("El nombre es demasiado largo")
        val other = dir.get(name)
        if (other != null && other !== except) throw RpfException("Ya existe “$name” en esta carpeta")
    }

    fun createFolder(dir: RpfDirectory, name: String): RpfDirectory {
        checkName(dir, name)
        val d = RpfDirectory(name, emptyList())
        dir.add(d)
        isModified = true
        return d
    }

    /** Crea (si hace falta) las carpetas de [path] ("a/b") dentro de [dir]. */
    fun ensureFolders(dir: RpfDirectory, path: String): RpfDirectory {
        var cur = dir
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            cur = when (val e = cur.get(part)) {
                null -> createFolder(cur, part)
                is RpfDirectory -> e
                else -> throw RpfException("“${e.path}” es un archivo, no una carpeta")
            }
        }
        return cur
    }

    fun rename(entry: RpfEntry, newName: String) {
        val dir = entry.parent ?: throw RpfException("No se puede renombrar la carpeta raíz")
        if (newName == entry.name) return
        checkName(dir, newName, entry)
        entry.name = newName
        dir.invalidate()
        isModified = true
    }

    fun delete(entry: RpfEntry) {
        val dir = entry.parent ?: throw RpfException("No se puede borrar la carpeta raíz")
        dir.remove(entry)
        isModified = true
    }

    /**
     * Añade un archivo nuevo o reemplaza el que tenga el mismo nombre.
     * Si [data] es un RSC7 válido se guarda como recurso; si no, como archivo normal.
     * [compress] = null aplica la regla de LibertyV.
     */
    fun putFile(dir: RpfDirectory, name: String, data: DataSource, compress: Boolean? = null): RpfFileEntry {
        val existing = dir.get(name)
        if (existing is RpfDirectory) throw RpfException("Ya existe una carpeta llamada “$name”")
        checkName(dir, name, existing)
        val rsc = RpfResource.parseRsc7(data, platform)
        if (existing is RpfResource && rsc == null) {
            throw RpfException(
                "“$name” es un recurso: para reemplazarlo hace falta el archivo en formato RSC7 " +
                    "(expórtalo con el modo RSC7 y edita ese archivo).",
            )
        }
        val entry: RpfFileEntry = if (rsc != null) {
            RpfResource(name, -1, -1, rsc.first, rsc.second, platform, data.slice(16, data.size - 16))
        } else {
            RpfRegularFile.imported(name, data, compress ?: RpfRegularFile.shouldCompress(name, data.size))
        }
        if (existing != null) dir.remove(existing)
        dir.add(entry)
        isModified = true
        return entry
    }

    /** Marca el archivo como guardado (lo usa el escritor al terminar). */
    internal fun markSaved() {
        isModified = false
    }

    /**
     * Contenido ya extraído (descifrado y descomprimido).
     * Para un recurso son las páginas system + graphics seguidas.
     */
    fun openContent(entry: RpfFileEntry): InputStream {
        entry.imported?.let { return it.openStream() }
        return openOriginalContent(entry)
    }

    private fun openOriginalContent(entry: RpfFileEntry): InputStream = when (entry) {
        is RpfRegularFile -> {
            val stored = openStored(entry)
            if (!entry.isCompressed) {
                if (entry.isEncrypted) aes.decryptingStream(stored) else stored
            } else {
                val plain = if (entry.isEncrypted) aes.decryptingStream(stored) else stored
                LimitedInputStream(Compression.decompressing(platform, plain), entry.uncompressedSize)
            }
        }
        is RpfResource -> {
            val data = source.slice(entry.offset + 16, entry.storedSize - 16).openStream()
            val plain = if (entry.isEncrypted) aes.decryptingStream(data) else data
            LimitedInputStream(Compression.decompressing(platform, plain), entry.size)
        }
    }

    /** Lee el contenido completo a memoria (solo para archivos pequeños). */
    fun readContent(entry: RpfFileEntry): ByteArray = openContent(entry).use { it.readBytes() }

    /** Cabecera RSC7 de 16 bytes para un recurso extraído. */
    fun rsc7Header(entry: RpfResource): ByteArray {
        val b = ByteArray(16)
        "RSC7".toByteArray(Charsets.ISO_8859_1).copyInto(b)
        putBe32(b, 4, entry.version)
        putBe32(b, 8, entry.systemFlag)
        putBe32(b, 12, entry.graphicsFlag)
        return b
    }

    /** Contenido de un recurso con la cabecera RSC7 delante. */
    fun openRsc7(entry: RpfResource): InputStream =
        SequenceInputStream(ByteArrayInputStream(rsc7Header(entry)), openContent(entry))

    /** Escribe los bytes del archivo tal como irían dentro del RPF (comprimidos/cifrados). */
    fun writeStored(entry: RpfFileEntry, out: OutputStream, progress: ((Long) -> Unit)? = null) {
        Rpf7Writer.writeEntryData(this, entry, out, progress)
    }

    override fun close() = source.close()
}

internal fun putBe32(b: ByteArray, o: Int, v: Int) {
    b[o] = (v ushr 24).toByte()
    b[o + 1] = (v ushr 16).toByte()
    b[o + 2] = (v ushr 8).toByte()
    b[o + 3] = v.toByte()
}

/** Corta el flujo en [limit] bytes. */
class LimitedInputStream(private val input: InputStream, private val limit: Long) : InputStream() {
    private var pos = 0L
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= limit) return -1
        val n = input.read(b, off, minOf(len.toLong(), limit - pos).toInt())
        if (n > 0) pos += n
        return n
    }

    override fun close() = input.close()
}
