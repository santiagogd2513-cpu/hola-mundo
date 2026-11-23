package com.libertyv.core.audio

import com.libertyv.core.ConsolePlatform
import com.libertyv.core.io.DataSource
import java.io.IOException

class AwcException(msg: String) : IOException(msg)

/** Un trozo de audio comprimido dentro del AWC y qué muestras de él se usan. */
class AwcBlock(
    val data: DataSource,
    /** Muestras a descartar al principio de este bloque. */
    val skipSamples: Int,
    /** Muestras que se usan tras el descarte. */
    val samples: Int,
)

/** Una pista mono del AWC. */
class AwcTrack(
    val id: Int,
    val sampleRate: Int,
    val sampleCount: Long,
    /** null = el audio es un único flujo (se decodifica entero y se recorta a [sampleCount]). */
    val blocks: List<AwcBlock>?,
    /** Flujo completo cuando [blocks] es null. */
    val data: DataSource?,
) {
    val durationSeconds: Double get() = if (sampleRate == 0) 0.0 else sampleCount.toDouble() / sampleRate
}

/**
 * Contenedor de audio de RAGE (.awc). En consola el audio va en XMA2 (Xbox 360) o MP3 (PS3).
 */
class AwcFile(private val source: DataSource, val platform: ConsolePlatform) {
    val tracks: List<AwcTrack>
    val isMultiChannel: Boolean
    var bigEndian = true
        private set

    companion object {
        /** Hash "one at a time" de Jenkins usado por RAGE. */
        fun jenkinsHash(s: String): Int {
            var h = 0
            for (c in s) {
                h += c.code and 0xFF
                h += h shl 10
                h = h xor (h ushr 6)
            }
            h += h shl 3
            h = h xor (h ushr 11)
            h += h shl 15
            return h
        }

        fun tag(s: String): Int = jenkinsHash(s) and 0xFF

        private val TAG_DATA = tag("data")
        private val TAG_FORMAT = tag("format")
        private const val TAG_CHANNELS_INFO = 0x48 // 0x81F95048 & 0xFF
        private const val PACKET = 2048
    }

    private class Chunk(val tag: Int, val offset: Long, val size: Long)

    private fun u32(pos: Long): Int {
        val b = source.readFully(pos, 4)
        return if (bigEndian) {
            ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        } else {
            (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
        }
    }

    private fun u16(pos: Long): Int {
        val b = source.readFully(pos, 2)
        return if (bigEndian) {
            ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        } else {
            (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        }
    }

    private fun u64(pos: Long): Long {
        val a = u32(pos).toLong() and 0xFFFFFFFFL
        val b = u32(pos + 4).toLong() and 0xFFFFFFFFL
        return if (bigEndian) (a shl 32) or b else (b shl 32) or a
    }

    private fun chunkSource(c: Chunk): DataSource {
        if (c.offset + c.size > source.size) throw AwcException("Bloque fuera del archivo AWC")
        return source.slice(c.offset, c.size)
    }

    init {
        if (source.size < 16) throw AwcException("AWC demasiado pequeño")
        val magic = String(source.readFully(0, 4), Charsets.ISO_8859_1)
        bigEndian = when (magic) {
            "TADA" -> true
            "ADAT" -> false
            else -> throw AwcException("No es un AWC (firma incorrecta)")
        }
        val vf = u32(4)
        val flags = (vf ushr 16) and 0xFFFF
        val streamCount = u32(8)
        if ((flags ushr 8) != 0xFF) throw AwcException("Flags de AWC no soportados (0x%04x)".format(flags))
        if ((flags and 0xF8) != 0) throw AwcException("Flags de AWC no soportados (0x%04x)".format(flags))
        if (streamCount <= 0 || streamCount > 4096) throw AwcException("Número de pistas inválido: $streamCount")
        isMultiChannel = (flags and 4) == 4

        var pos = 16L + if ((flags and 1) == 1) 2L * streamCount else 0L
        val ids = IntArray(streamCount)
        val tagCounts = IntArray(streamCount)
        for (i in 0 until streamCount) {
            val v = u32(pos)
            pos += 4
            ids[i] = v and 0x1FFFFFFF
            tagCounts[i] = v ushr 29
        }
        val chunks = HashMap<Int, HashMap<Int, Chunk>>()
        for (i in 0 until streamCount) {
            val m = HashMap<Int, Chunk>()
            for (k in 0 until tagCounts[i]) {
                val v = u64(pos)
                pos += 8
                val tag = (v ushr 56).toInt() and 0xFF
                val offset = v and 0xFFFFFFF
                val size = (v ushr 28) and 0xFFFFFFF
                m[tag] = Chunk(tag, offset, size)
            }
            chunks[ids[i]] = m
        }

        tracks = if (isMultiChannel) parseMultiChannel(chunks, ids, streamCount) else {
            (0 until streamCount).map { i ->
                val m = chunks[ids[i]]!!
                val fmt = m[TAG_FORMAT] ?: throw AwcException("Pista ${ids[i]} sin formato")
                val data = m[TAG_DATA] ?: throw AwcException("Pista ${ids[i]} sin datos")
                val samples = u32(fmt.offset).toLong() and 0xFFFFFFFFL
                val rate = u16(fmt.offset + 8)
                AwcTrack(ids[i], rate, samples, null, chunkSource(data))
            }
        }
    }

    private fun parseMultiChannel(chunks: Map<Int, Map<Int, Chunk>>, ids: IntArray, streamCount: Int): List<AwcTrack> {
        val main = chunks[0] ?: throw AwcException("AWC multicanal sin pista 0")
        val info = main[TAG_CHANNELS_INFO] ?: throw AwcException("AWC multicanal sin información de canales")
        val dataChunk = main[TAG_DATA] ?: throw AwcException("AWC multicanal sin datos")

        val bigChunkSize = u32(info.offset + 4)
        val channels = u32(info.offset + 8)
        if (channels != streamCount - 1) throw AwcException("Número de canales inesperado")
        if (bigChunkSize <= 0) throw AwcException("Tamaño de bloque inválido")

        class ChannelInfo(val samples: Long, val rate: Int, val roundSize: Int)
        val chInfo = (0 until channels).map { k ->
            val p = info.offset + 12 + 16L * k
            ChannelInfo(
                samples = u32(p + 4).toLong() and 0xFFFFFFFFL,
                rate = u16(p + 10),
                roundSize = source.readFully(p + 13, 1)[0].toInt() and 0xFF,
            )
        }

        val headerSize = if (platform == ConsolePlatform.PS3) 24 else 16
        val blocks = List(channels) { ArrayList<AwcBlock>() }
        val dataStart = dataChunk.offset
        val dataLen = dataChunk.size
        var position = 0L
        while (position < dataLen) {
            var totalChunks = 0
            val sizes = LongArray(channels)
            val skips = IntArray(channels)
            val counts = IntArray(channels)
            var hp = dataStart + position
            for (j in 0 until channels) {
                // hp + 0: primer chunk (no se usa)
                val nChunks = u32(hp + 4)
                val skip = u32(hp + 8)
                val samples = u32(hp + 12)
                sizes[j] = if (platform == ConsolePlatform.PS3) {
                    u32(hp + 20).toLong() and 0xFFFFFFFFL
                } else {
                    nChunks.toLong() * PACKET
                }
                totalChunks += nChunks
                skips[j] = skip
                counts[j] = samples - skip
                hp += headerSize
            }
            var headerLen = totalChunks.toLong() * 4 + channels.toLong() * headerSize
            headerLen += (PACKET - headerLen % PACKET) % PACKET
            var p = position + headerLen
            for (k in 0 until channels) {
                if (dataStart + p + sizes[k] > source.size) throw AwcException("Datos de canal fuera del archivo")
                blocks[k].add(AwcBlock(source.slice(dataStart + p, sizes[k]), skips[k], counts[k]))
                var s = sizes[k]
                val r = chInfo[k].roundSize
                if (r != 0) s += (r - s % r) % r
                p += s
            }
            if (p - position > bigChunkSize) throw AwcException("Bloques demasiado grandes")
            if (totalChunks == 0 || position + bigChunkSize > dataLen) throw AwcException("Estructura multicanal inesperada")
            position += bigChunkSize
        }
        return (0 until channels).map { k ->
            AwcTrack(ids[k + 1], chInfo[k].rate, chInfo[k].samples, blocks[k], null)
        }
    }
}
