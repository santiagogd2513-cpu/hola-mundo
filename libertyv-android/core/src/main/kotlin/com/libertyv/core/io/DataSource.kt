package com.libertyv.core.io

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Datos de acceso aleatorio (un archivo, una parte de un archivo o un array en memoria).
 * Las lecturas son posicionales, así que varias secuencias pueden leer a la vez.
 */
interface DataSource : Closeable {
    val size: Long

    /** Lee hasta [len] bytes en la posición [pos]. Devuelve los leídos (menos de [len] solo al final). */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int

    fun slice(offset: Long, length: Long): DataSource = SliceSource(this, offset, length)

    fun openStream(): InputStream = DataSourceInputStream(this)

    fun readFully(pos: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = read(pos + done, out, done, len - done)
            if (n <= 0) throw EOFException("Fin inesperado de los datos (pos=${pos + done})")
            done += n
        }
        return out
    }

    override fun close() {}
}

class ByteArraySource(private val data: ByteArray) : DataSource {
    override val size: Long get() = data.size.toLong()

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(len.toLong(), data.size - pos).toInt()
        System.arraycopy(data, pos.toInt(), buf, off, n)
        return n
    }
}

class FileChannelSource(
    private val channel: FileChannel,
    private val onClose: Closeable? = null,
) : DataSource {
    override val size: Long = channel.size()

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        val bb = ByteBuffer.wrap(buf, off, minOf(len.toLong(), size - pos).toInt())
        var total = 0
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos + total)
            if (n < 0) break
            total += n
        }
        return if (total == 0) -1 else total
    }

    override fun close() {
        channel.close()
        onClose?.close()
    }
}

class SliceSource(
    private val parent: DataSource,
    private val offset: Long,
    length: Long,
) : DataSource {
    override val size: Long = length

    init {
        if (offset < 0 || length < 0 || offset + length > parent.size) {
            throw IOException("Rango fuera del archivo: offset=$offset tamaño=$length (archivo=${parent.size})")
        }
    }

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        val n = minOf(len.toLong(), size - pos).toInt()
        return parent.read(offset + pos, buf, off, n)
    }

    override fun slice(offset: Long, length: Long): DataSource = SliceSource(parent, this.offset + offset, length)
}

class DataSourceInputStream(private val source: DataSource) : InputStream() {
    private var pos = 0L
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= source.size) return -1
        val n = source.read(pos, b, off, len)
        if (n > 0) pos += n
        return n
    }

    override fun skip(n: Long): Long {
        val s = minOf(n, source.size - pos).coerceAtLeast(0)
        pos += s
        return s
    }

    override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), source.size - pos).toInt()
}

fun InputStream.readFullyOrThrow(buf: ByteArray, off: Int, len: Int) {
    var done = 0
    while (done < len) {
        val n = read(buf, off + done, len - done)
        if (n < 0) throw EOFException("Fin inesperado de los datos")
        done += n
    }
}

/** Copia exactamente [count] bytes (o hasta el final si count < 0). Devuelve los copiados. */
fun InputStream.copyCount(
    out: OutputStream,
    count: Long = -1,
    progress: ((Long) -> Unit)? = null,
): Long {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (count < 0 || total < count) {
        val want = if (count < 0) buf.size else minOf(buf.size.toLong(), count - total).toInt()
        val n = read(buf, 0, want)
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
        progress?.invoke(total)
    }
    return total
}

internal fun be32(b: ByteArray, o: Int): Int =
    ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

internal fun le32(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

internal fun be16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

internal fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
