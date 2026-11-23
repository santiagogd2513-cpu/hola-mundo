package com.libertyv.core

import com.libertyv.core.compression.LzxDecoder
import com.libertyv.core.compression.LzxEncoder
import com.libertyv.core.compression.XMemCompressOutputStream
import com.libertyv.core.compression.XMemDecompressInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class LzxTest {
    private fun roundTrip(data: ByteArray): Int {
        roundTripAligned(data)
        val bos = ByteArrayOutputStream()
        XMemCompressOutputStream(bos).use { it.write(data) }
        val comp = bos.toByteArray()
        val back = XMemDecompressInputStream(ByteArrayInputStream(comp)).readBytes()
        assertArrayEquals(data, back)
        return comp.size
    }

    /** Mismos datos con bloques "aligned", decodificados frame a frame. */
    private fun roundTripAligned(data: ByteArray) {
        val enc = LzxEncoder(16, useAligned = true)
        val dec = LzxDecoder(16)
        var off = 0
        while (off < data.size) {
            val n = minOf(32768, data.size - off)
            val comp = enc.encodeFrame(data, off, n)
            val out = ByteArray(n)
            dec.decompress(comp, 0, comp.size, out, 0, n)
            assertArrayEquals(data.copyOfRange(off, off + n), out)
            off += n
        }
    }

    @Test
    fun roundTripText() {
        val sb = StringBuilder()
        val r = Random(1)
        val words = listOf("vehicle", "weapon", "ped", "model", "texture", "<Item>", "</Item>", "\n", "  ")
        while (sb.length < 300_000) sb.append(words[r.nextInt(words.size)]).append(' ')
        val data = sb.toString().toByteArray()
        val size = roundTrip(data)
        assertTrue("Debería comprimir texto ($size)", size < data.size / 3)
    }

    @Test
    fun roundTripRandom() {
        roundTrip(Random(2).nextBytes(200_000))
    }

    @Test
    fun roundTripZerosAndSmall() {
        roundTrip(ByteArray(100_000))
        roundTrip(ByteArray(1) { 7 })
        roundTrip(ByteArray(32768) { (it % 3).toByte() })
        roundTrip(ByteArray(32769) { (it % 251).toByte() })
    }

    @Test
    fun roundTripLongRangeRepeats() {
        val r = Random(3)
        val block = r.nextBytes(20_000)
        val out = ByteArrayOutputStream()
        repeat(12) {
            out.write(block)
            out.write(r.nextBytes(r.nextInt(1, 30_000)))
        }
        roundTrip(out.toByteArray())
    }

    /** Bloque LZX "sin comprimir" construido a mano según la especificación. */
    @Test
    fun uncompressedBlock() {
        val payload = "Hola mundo desde un bloque LZX sin comprimir!".toByteArray() // 45 bytes (impar)
        val bits = ArrayList<Int>() // valores de 1 bit
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((v ushr i) and 1) }
        put(0, 1) // sin E8
        put(3, 3) // tipo sin comprimir
        put(payload.size ushr 8, 16)
        put(payload.size and 0xFF, 8)
        while (bits.size % 16 != 0) bits.add(0) // relleno hasta 16 bits
        val out = ByteArrayOutputStream()
        for (w in 0 until bits.size / 16) {
            var v = 0
            for (i in 0 until 16) v = (v shl 1) or bits[w * 16 + i]
            out.write(v and 0xFF)
            out.write(v ushr 8)
        }
        repeat(3) { out.write(byteArrayOf(1, 0, 0, 0)) } // R0, R1, R2
        out.write(payload)
        out.write(0) // relleno a par
        val comp = out.toByteArray()
        val dec = LzxDecoder(16)
        val res = ByteArray(payload.size)
        dec.decompress(comp, 0, comp.size, res, 0, res.size)
        assertArrayEquals(payload, res)
    }
}
