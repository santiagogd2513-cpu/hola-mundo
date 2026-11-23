package com.libertyv.core.audio

import com.libertyv.core.ConsolePlatform
import com.libertyv.core.io.DataSource
import java.io.Closeable
import java.io.OutputStream

/**
 * Decodificador de audio mono a PCM de 16 bits.
 * En Android lo implementa FFmpeg (XMA2 para Xbox 360, MP3 para PS3).
 */
interface AudioDecoder : Closeable {
    /** Le da datos comprimidos y devuelve las muestras que haya producido (puede ser vacío). */
    fun feed(data: ByteArray, off: Int, len: Int): ShortArray
}

fun interface AudioDecoderFactory {
    fun create(platform: ConsolePlatform, sampleRate: Int): AudioDecoder
}

/** Recibe las muestras PCM en orden. */
fun interface PcmSink {
    fun write(samples: ShortArray, off: Int, len: Int)
}

object AwcDecoding {
    private const val XMA_PACKET = 2048

    /**
     * Decodifica una pista completa, igual que LibertyV:
     * - Pista simple: se decodifica todo y se toman [AwcTrack.sampleCount] muestras.
     * - Pista multicanal: en cada bloque se descartan `skipSamples`, se toman `samples`
     *   y el resto del bloque se decodifica pero se ignora. El decodificador sigue vivo
     *   entre bloques.
     * Si faltan muestras se rellena con silencio.
     */
    fun decodeTrack(
        track: AwcTrack,
        platform: ConsolePlatform,
        factory: AudioDecoderFactory,
        sink: PcmSink,
        checkCancelled: () -> Unit = {},
    ) {
        var remaining = track.sampleCount
        fun emit(buf: ShortArray, off: Int, len: Int) {
            val n = minOf(len.toLong(), remaining).toInt()
            if (n > 0) {
                sink.write(buf, off, n)
                remaining -= n
            }
        }

        factory.create(platform, track.sampleRate).use { dec ->
            val blocks = track.blocks
            if (blocks == null) {
                feedAll(track.data!!, platform, dec, checkCancelled) { pcm -> emit(pcm, 0, pcm.size) }
            } else {
                for (block in blocks) {
                    var skip = block.skipSamples.toLong()
                    var take = block.samples.toLong()
                    feedAll(block.data, platform, dec, checkCancelled) { pcm ->
                        var o = 0
                        var l = pcm.size
                        if (skip > 0) {
                            val s = minOf(skip, l.toLong()).toInt()
                            skip -= s
                            o += s
                            l -= s
                        }
                        if (l > 0 && take > 0) {
                            val t = minOf(take, l.toLong()).toInt()
                            emit(pcm, o, t)
                            take -= t
                        }
                    }
                    if (take > 0) {
                        // El bloque dio menos muestras de las esperadas: rellenar con silencio.
                        emitSilence(minOf(take, remaining)) { b, o, l -> emit(b, o, l) }
                    }
                }
            }
        }
        if (remaining > 0) emitSilence(remaining) { b, o, l -> sink.write(b, o, l) }
    }

    private inline fun emitSilence(count: Long, out: (ShortArray, Int, Int) -> Unit) {
        val z = ShortArray(4096)
        var left = count
        while (left > 0) {
            val n = minOf(left, z.size.toLong()).toInt()
            out(z, 0, n)
            left -= n
        }
    }

    private inline fun feedAll(
        data: DataSource,
        platform: ConsolePlatform,
        dec: AudioDecoder,
        checkCancelled: () -> Unit,
        onPcm: (ShortArray) -> Unit,
    ) {
        val chunk = if (platform == ConsolePlatform.XBOX360) XMA_PACKET else 16 * 1024
        val buf = ByteArray(chunk)
        var pos = 0L
        while (pos < data.size) {
            checkCancelled()
            val want = minOf(chunk.toLong(), data.size - pos).toInt()
            // XMA solo admite paquetes completos de 2048 bytes (un resto final se ignora).
            if (platform == ConsolePlatform.XBOX360 && want < XMA_PACKET) break
            var got = 0
            while (got < want) {
                val n = data.read(pos + got, buf, got, want - got)
                if (n <= 0) break
                got += n
            }
            if (got <= 0) break
            pos += got
            val pcm = dec.feed(buf, 0, got)
            if (pcm.isNotEmpty()) onPcm(pcm)
        }
    }
}

/** Escribe un WAV PCM de 16 bits. */
object WavWriter {
    fun header(channels: Int, sampleRate: Int, dataBytes: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val blockAlign = 2 * channels
        b.put("RIFF".toByteArray(Charsets.ISO_8859_1))
        b.putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt())
        b.put("WAVE".toByteArray(Charsets.ISO_8859_1))
        b.put("fmt ".toByteArray(Charsets.ISO_8859_1))
        b.putInt(16)
        b.putShort(1)
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * blockAlign)
        b.putShort(blockAlign.toShort())
        b.putShort(16)
        b.put("data".toByteArray(Charsets.ISO_8859_1))
        b.putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        return b.array()
    }

    /** Decodifica [track] y escribe un WAV mono en [out]. */
    fun writeTrack(
        track: AwcTrack,
        platform: ConsolePlatform,
        factory: AudioDecoderFactory,
        out: OutputStream,
        checkCancelled: () -> Unit = {},
    ) {
        out.write(header(1, track.sampleRate, track.sampleCount * 2))
        val bytes = ByteArray(8192 * 2)
        AwcDecoding.decodeTrack(track, platform, factory, { s, o, l ->
            var i = 0
            while (i < l) {
                val n = minOf(l - i, 8192)
                for (k in 0 until n) {
                    val v = s[o + i + k].toInt()
                    bytes[2 * k] = v.toByte()
                    bytes[2 * k + 1] = (v shr 8).toByte()
                }
                out.write(bytes, 0, 2 * n)
                i += n
            }
        }, checkCancelled)
    }
}
