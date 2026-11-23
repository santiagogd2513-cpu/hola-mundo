package com.libertyv.core.crypto

import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256 en modo ECB sin relleno, como usa RAGE en los RPF7 de consola.
 * Solo se procesan bloques completos de 16 bytes; el resto final se deja tal cual.
 */
class AesEcb(private val key: ByteArray) {
    init {
        require(key.size == 32) { "La clave AES debe tener 32 bytes (tiene ${key.size})" }
    }

    fun newDecryptCipher(): Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
    }

    fun newEncryptCipher(): Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }

    /** Descifra en el sitio los bloques completos de [buf] (rounds = 16 para TOC con cifrado "16 rondas"). */
    fun decryptInPlace(buf: ByteArray, off: Int = 0, len: Int = buf.size, rounds: Int = 1) {
        val full = len / 16 * 16
        if (full == 0) return
        val c = newDecryptCipher()
        repeat(rounds) { c.doFinal(buf, off, full, buf, off) }
    }

    fun encryptInPlace(buf: ByteArray, off: Int = 0, len: Int = buf.size, rounds: Int = 1) {
        val full = len / 16 * 16
        if (full == 0) return
        val c = newEncryptCipher()
        repeat(rounds) { c.doFinal(buf, off, full, buf, off) }
    }

    fun decryptingStream(input: InputStream): InputStream = AesEcbInputStream(input, newDecryptCipher())

    fun encryptingStream(output: OutputStream): OutputStream = AesEcbOutputStream(output, newEncryptCipher())
}

private class AesEcbInputStream(private val input: InputStream, private val cipher: Cipher) : InputStream() {
    private val buf = ByteArray(64 * 1024)
    private var pos = 0
    private var lim = 0
    private var leftover = 0
    private var srcEof = false
    private val one = ByteArray(1)

    private fun fill(): Boolean {
        if (srcEof && leftover == 0) return false
        System.arraycopy(buf, lim, buf, 0, leftover)
        var n = leftover
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) {
                srcEof = true
                break
            }
            n += r
        }
        val full = n / 16 * 16
        if (full > 0) cipher.doFinal(buf, 0, full, buf, 0)
        pos = 0
        if (srcEof) {
            lim = n // el resto (<16 bytes) va sin cifrar
            leftover = 0
        } else {
            lim = full
            leftover = n - full
        }
        return lim > 0
    }

    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= lim && !fill()) return -1
        val n = minOf(len, lim - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() = input.close()
}

private class AesEcbOutputStream(out: OutputStream, private val cipher: Cipher) : FilterOutputStream(out) {
    private val buf = ByteArray(64 * 1024)
    private var n = 0

    override fun write(b: Int) {
        buf[n++] = b.toByte()
        if (n == buf.size) flushBlocks()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var l = len
        while (l > 0) {
            val c = minOf(l, buf.size - n)
            System.arraycopy(b, o, buf, n, c)
            n += c
            o += c
            l -= c
            if (n == buf.size) flushBlocks()
        }
    }

    private fun flushBlocks() {
        val full = n / 16 * 16
        if (full > 0) {
            cipher.doFinal(buf, 0, full, buf, 0)
            out.write(buf, 0, full)
        }
        System.arraycopy(buf, full, buf, 0, n - full)
        n -= full
    }

    override fun close() {
        flushBlocks()
        if (n > 0) out.write(buf, 0, n) // bloque final incompleto sin cifrar
        n = 0
        super.close()
    }
}
