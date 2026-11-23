package com.libertyv.core.compression

import com.libertyv.core.ConsolePlatform
import com.libertyv.core.io.readFullyOrThrow
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

object Compression {
    /** Flujo que descomprime según la consola: Deflate en PS3, XMemCompress (LZX) en Xbox 360. */
    fun decompressing(platform: ConsolePlatform, input: InputStream): InputStream = when (platform) {
        ConsolePlatform.PS3 -> RawInflaterInputStream(input)
        ConsolePlatform.XBOX360 -> XMemDecompressInputStream(input)
    }
}

/** Deflate "crudo" (sin cabecera zlib), como el DeflateStream de .NET. */
class RawInflaterInputStream(input: InputStream) : InflaterInputStream(
    // El Inflater en modo nowrap puede necesitar un byte extra al final.
    SequenceInputStream(input, ByteArrayInputStream(ByteArray(1))),
    Inflater(true),
    64 * 1024,
) {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        try {
            super.close()
        } finally {
            inf.end()
        }
    }
}

/**
 * Formato de XMemCompress: una serie de bloques LZX (ventana de 64 KB).
 * Cada bloque empieza con:
 *  - 0xFF, tamaño descomprimido (2 bytes BE), tamaño comprimido (2 bytes BE); o
 *  - tamaño comprimido (2 bytes BE) y entonces el descomprimido es 32768.
 * Un tamaño comprimido 0 marca el final.
 */
class XMemDecompressInputStream(private val input: InputStream, windowBits: Int = 16) : InputStream() {
    private val lzx = LzxDecoder(windowBits)
    private var out = ByteArray(32768)
    private var outPos = 0
    private var outLen = 0
    private var comp = ByteArray(33024)
    private var eof = false
    private val one = ByteArray(1)

    private fun readByteOrThrow(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("Bloque XMem truncado")
        return b
    }

    private fun fill(): Boolean {
        while (!eof) {
            val first = input.read()
            if (first < 0) {
                eof = true
                return false
            }
            val frameSize: Int
            val blockSize: Int
            if (first == 0xFF) {
                frameSize = (readByteOrThrow() shl 8) or readByteOrThrow()
                blockSize = (readByteOrThrow() shl 8) or readByteOrThrow()
            } else {
                blockSize = (first shl 8) or readByteOrThrow()
                if (blockSize == 0) {
                    eof = true
                    return false
                }
                frameSize = 32768
            }
            if (blockSize > comp.size) comp = ByteArray(blockSize)
            input.readFullyOrThrow(comp, 0, blockSize)
            if (frameSize > out.size) out = ByteArray(frameSize)
            lzx.decompress(comp, 0, blockSize, out, 0, frameSize)
            outPos = 0
            outLen = frameSize
            if (outLen > 0) return true
        }
        return false
    }

    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (outPos >= outLen && !fill()) return -1
        val n = minOf(len, outLen - outPos)
        System.arraycopy(out, outPos, b, off, n)
        outPos += n
        return n
    }

    override fun close() = input.close()
}
