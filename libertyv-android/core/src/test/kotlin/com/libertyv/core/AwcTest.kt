package com.libertyv.core

import com.libertyv.core.audio.AudioDecoder
import com.libertyv.core.audio.AudioDecoderFactory
import com.libertyv.core.audio.AwcDecoding
import com.libertyv.core.audio.AwcFile
import com.libertyv.core.audio.WavWriter
import com.libertyv.core.io.ByteArraySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class AwcTest {
    /** Decodificador falso: cada paquete de 2048 bytes da 100 muestras con el valor del primer byte. */
    private val fake = AudioDecoderFactory { _, _ ->
        object : AudioDecoder {
            override fun feed(data: ByteArray, off: Int, len: Int) = ShortArray(100) { data[off].toShort() }
            override fun close() {}
        }
    }

    private fun packets(vararg values: Int) = ByteArray(2048 * values.size) { values[it / 2048].toByte() }

    @Test
    fun singleStream() {
        val data = packets(1, 2, 3)
        val b = ByteBuffer.allocate(4096) // big-endian
        b.put("TADA".toByteArray())
        b.putInt((0xFF00 shl 16) or 1) // flags 0xFF00, versión 1
        b.putInt(1) // 1 pista
        b.putInt(0)
        b.putInt((2 shl 29) or 1234) // id 1234, 2 tags
        val fmtOff = 64L
        val dataOff = 2048L
        fun chunk(tag: Int, off: Long, size: Long) = b.putLong((tag.toLong() shl 56) or (size shl 28) or off)
        chunk(AwcFile.tag("format"), fmtOff, 20)
        chunk(AwcFile.tag("data"), dataOff, data.size.toLong())
        b.position(fmtOff.toInt())
        b.putInt(250) // muestras
        b.putInt(-1)
        b.putShort(32000.toShort())
        val all = b.array().copyOf(2048) + data

        val awc = AwcFile(ByteArraySource(all), ConsolePlatform.XBOX360)
        assertFalse(awc.isMultiChannel)
        val t = awc.tracks.single()
        assertEquals(1234, t.id)
        assertEquals(32000, t.sampleRate)
        assertEquals(250L, t.sampleCount)

        val out = ArrayList<Short>()
        AwcDecoding.decodeTrack(t, ConsolePlatform.XBOX360, fake, { s, o, l -> for (i in 0 until l) out.add(s[o + i]) })
        assertEquals(250, out.size)
        assertEquals(List(100) { 1.toShort() } + List(100) { 2.toShort() } + List(50) { 3.toShort() }, out)

        val wav = ByteArrayOutputStream()
        WavWriter.writeTrack(t, ConsolePlatform.XBOX360, fake, wav)
        assertEquals(44 + 500, wav.size())
        assertEquals("RIFF", String(wav.toByteArray(), 0, 4))
    }

    @Test
    fun multiChannel() {
        // 2 canales, 2 bloques grandes de 8192 bytes; cada canal 1 paquete por bloque.
        val big = 8192
        val b = ByteBuffer.allocate(2048 + 2 * big)
        b.put("TADA".toByteArray())
        b.putInt(((0xFF00 or 4) shl 16) or 1)
        b.putInt(3)
        b.putInt(0)
        b.putInt((2 shl 29) or 0) // pista 0: info + data
        b.putInt((0 shl 29) or 77)
        b.putInt((0 shl 29) or 78)
        val infoOff = 256L
        val dataOff = 2048L
        fun chunk(tag: Int, off: Long, size: Long) = b.putLong((tag.toLong() shl 56) or (size shl 28) or off)
        chunk(0x48, infoOff, 12 + 32)
        chunk(AwcFile.tag("data"), dataOff, 2L * big)
        b.position(infoOff.toInt())
        b.putInt(0); b.putInt(big); b.putInt(2)
        for (k in 0 until 2) {
            b.putInt(77 + k); b.putInt(150); b.putShort(0); b.putShort(24000.toShort()); b.put(0); b.put(0); b.putShort(0)
        }
        for (blk in 0 until 2) {
            val base = dataOff.toInt() + blk * big
            b.position(base)
            for (ch in 0 until 2) {
                b.putInt(0); b.putInt(1); b.putInt(if (blk == 0) 10 else 0); b.putInt(if (blk == 0) 85 else 75)
            }
            // cabecera: 2 chunks*4 + 2*16 = 40 -> redondeado a 2048
            for (ch in 0 until 2) {
                val p = base + 2048 + ch * 2048
                for (i in 0 until 2048) b.put(p + i, (10 * (blk + 1) + ch).toByte())
            }
        }
        val awc = AwcFile(ByteArraySource(b.array()), ConsolePlatform.XBOX360)
        assertTrue(awc.isMultiChannel)
        assertEquals(listOf(77, 78), awc.tracks.map { it.id })
        val t = awc.tracks[1]
        assertEquals(24000, t.sampleRate)
        val out = ArrayList<Short>()
        AwcDecoding.decodeTrack(t, ConsolePlatform.XBOX360, fake, { s, o, l -> for (i in 0 until l) out.add(s[o + i]) })
        // bloque 1: descarta 10, toma 75 (valor 11); bloque 2: toma 75 (valor 21)
        assertEquals(List(75) { 11.toShort() } + List(75) { 21.toShort() }, out)
    }
}
