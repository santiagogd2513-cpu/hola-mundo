package com.libertyv.core.compression

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.PriorityQueue

/**
 * Compresor LZX compatible con [LzxDecoder] y con XMemDecompress.
 * Genera un bloque "verbatim" por frame, con búsqueda de coincidencias por cadenas hash.
 * Las coincidencias nunca cruzan el final de un frame.
 */
class LzxEncoder(private val windowBits: Int = 16, private val useAligned: Boolean = false) {
    companion object {
        const val FRAME_SIZE = 32768
        private const val MIN_MATCH = 3 // usamos >=3 para que compense
        private const val MAX_MATCH = 257
        private const val HASH_BITS = 15
        private const val MAX_CHAIN = 48
        private const val NUM_CHARS = 256
    }

    private val windowSize = 1 shl windowBits
    private val maxDistance = windowSize - 3
    private val mainElements = NUM_CHARS + (LzxDecoder.positionSlots(windowBits) shl 3)

    // Historial: los últimos windowSize bytes + el frame actual.
    private val hist = ByteArray(windowSize + FRAME_SIZE)
    private var histLen = 0
    private var histBase = 0L // posición global de hist[0]
    private val head = IntArray(1 shl HASH_BITS) { -1 }
    private val prev = IntArray(hist.size)

    private val prevMainLen = IntArray(mainElements)
    private val prevLengthLen = IntArray(LzxDecoder.NUM_SECONDARY_LENGTHS)
    private var headerWritten = false

    /** Comprime un frame (<= 32768 bytes). Devuelve los bytes comprimidos de ese frame. */
    fun encodeFrame(data: ByteArray, off: Int, len: Int): ByteArray {
        require(len in 1..FRAME_SIZE)
        slideIfNeeded(len)
        val start = histLen
        System.arraycopy(data, off, hist, start, len)
        histLen += len
        val end = start + len

        // 1) Buscar coincidencias (greedy).
        val tokens = IntArray(len * 2)
        var nTok = 0
        var i = start
        while (i < end) {
            var bestLen = 0
            var bestDist = 0
            if (i + MIN_MATCH <= end) {
                val h = hash(i)
                var cand = head[h]
                var chain = 0
                val maxLen = minOf(MAX_MATCH, end - i)
                while (cand >= 0 && chain < MAX_CHAIN) {
                    val dist = i - cand
                    if (dist > maxDistance) break
                    if (dist > 0 && hist[cand + bestLen] == hist[i + bestLen]) {
                        var l = 0
                        while (l < maxLen && hist[cand + l] == hist[i + l]) l++
                        if (l > bestLen) {
                            bestLen = l
                            bestDist = dist
                            if (l == maxLen) break
                        }
                    }
                    cand = prev[cand]
                    chain++
                }
            }
            if (bestLen >= MIN_MATCH) {
                tokens[nTok++] = bestLen or Int.MIN_VALUE
                tokens[nTok++] = bestDist
                for (k in 0 until bestLen) insert(i + k, end)
                i += bestLen
            } else {
                tokens[nTok++] = hist[i].toInt() and 0xFF
                insert(i, end)
                i++
            }
        }

        // 2) Frecuencias.
        val mainFreq = IntArray(mainElements)
        val lenFreq = IntArray(LzxDecoder.NUM_SECONDARY_LENGTHS)
        val alignedFreq = IntArray(LzxDecoder.ALIGNED_NUM_ELEMENTS)
        var t = 0
        while (t < nTok) {
            val v = tokens[t]
            if (v < 0) {
                val ml = v and Int.MAX_VALUE
                val dist = tokens[t + 1]
                val slot = slotFor(dist + 2)
                val lh = minOf(ml - 2, 7)
                mainFreq[NUM_CHARS + (slot shl 3) + lh]++
                if (lh == 7) lenFreq[ml - 2 - 7]++
                if (LzxDecoder.EXTRA_BITS[slot] >= 3) alignedFreq[(dist + 2 - LzxDecoder.POSITION_BASE[slot]) and 7]++
                t += 2
            } else {
                mainFreq[v]++
                t++
            }
        }
        val mainLen = Huffman.lengths(mainFreq, 16)
        val lengthLen = Huffman.lengths(lenFreq, 16)
        val aligned = useAligned && alignedFreq.any { it > 0 }
        val alignedLen = if (aligned) Huffman.lengths(alignedFreq, 7) else IntArray(0)
        val alignedCode = if (aligned) Huffman.codes(alignedLen) else IntArray(0)
        val mainCode = Huffman.codes(mainLen)
        val lengthCode = Huffman.codes(lengthLen)

        // 3) Escribir.
        val bw = BitWriter()
        if (!headerWritten) {
            bw.write(0, 1) // sin traducción E8
            headerWritten = true
        }
        bw.write(if (aligned) LzxDecoder.BLOCKTYPE_ALIGNED else LzxDecoder.BLOCKTYPE_VERBATIM, 3)
        bw.write(len ushr 8, 16)
        bw.write(len and 0xFF, 8)
        if (aligned) for (k in 0 until LzxDecoder.ALIGNED_NUM_ELEMENTS) bw.write(alignedLen[k], 3)
        writeLengths(bw, prevMainLen, mainLen, 0, NUM_CHARS)
        writeLengths(bw, prevMainLen, mainLen, NUM_CHARS, mainElements)
        writeLengths(bw, prevLengthLen, lengthLen, 0, LzxDecoder.NUM_SECONDARY_LENGTHS)
        mainLen.copyInto(prevMainLen)
        lengthLen.copyInto(prevLengthLen)

        t = 0
        while (t < nTok) {
            val v = tokens[t]
            if (v < 0) {
                val ml = v and Int.MAX_VALUE
                val fo = tokens[t + 1] + 2
                val slot = slotFor(fo)
                val lh = minOf(ml - 2, 7)
                val sym = NUM_CHARS + (slot shl 3) + lh
                bw.write(mainCode[sym], mainLen[sym])
                if (lh == 7) {
                    val ls = ml - 2 - 7
                    bw.write(lengthCode[ls], lengthLen[ls])
                }
                val extra = LzxDecoder.EXTRA_BITS[slot]
                val v = fo - LzxDecoder.POSITION_BASE[slot]
                if (aligned && extra >= 3) {
                    if (extra > 3) bw.write(v ushr 3, extra - 3)
                    bw.write(alignedCode[v and 7], alignedLen[v and 7])
                } else if (extra > 0) {
                    bw.write(v, extra)
                }
                t += 2
            } else {
                bw.write(mainCode[v], mainLen[v])
                t++
            }
        }
        bw.align16()
        return bw.toByteArray()
    }

    private fun slotFor(formattedOffset: Int): Int {
        var s = 3
        while (s + 1 < LzxDecoder.POSITION_BASE.size && LzxDecoder.POSITION_BASE[s + 1] <= formattedOffset) s++
        return s
    }

    private fun hash(p: Int): Int {
        val v = ((hist[p].toInt() and 0xFF) shl 16) or ((hist[p + 1].toInt() and 0xFF) shl 8) or (hist[p + 2].toInt() and 0xFF)
        return (v * -0x61c88647 ushr (32 - HASH_BITS))
    }

    private fun insert(p: Int, end: Int) {
        if (p + 3 > end) return
        val h = hash(p)
        prev[p] = head[h]
        head[h] = p
    }

    private fun slideIfNeeded(incoming: Int) {
        if (histLen + incoming <= hist.size) return
        val keep = windowSize // la ventana completa sigue disponible para coincidencias
        val drop = histLen - keep
        System.arraycopy(hist, drop, hist, 0, keep)
        histLen = keep
        histBase += drop
        for (k in head.indices) head[k] = if (head[k] >= drop) head[k] - drop else -1
        for (k in 0 until keep) {
            val pv = prev[k + drop]
            prev[k] = if (pv >= drop) pv - drop else -1
        }
    }

    /** Codifica longitudes con el pre-árbol (deltas mod 17 y rachas de ceros 17/18). */
    private fun writeLengths(bw: BitWriter, prevLens: IntArray, lens: IntArray, first: Int, last: Int) {
        val syms = ArrayList<Int>()
        val extras = ArrayList<Int>()
        var x = first
        while (x < last) {
            if (lens[x] == 0) {
                var run = 0
                while (x + run < last && lens[x + run] == 0) run++
                if (run >= 20) {
                    val r = minOf(run, 51)
                    syms.add(18); extras.add(r - 20)
                    x += r
                    continue
                } else if (run >= 4) {
                    val r = minOf(run, 19)
                    syms.add(17); extras.add(r - 4)
                    x += r
                    continue
                }
            }
            var d = prevLens[x] - lens[x]
            if (d < 0) d += 17
            syms.add(d); extras.add(-1)
            x++
        }
        val freq = IntArray(LzxDecoder.PRETREE_NUM_ELEMENTS)
        syms.forEach { freq[it]++ }
        val ptLen = Huffman.lengths(freq, 15)
        val ptCode = Huffman.codes(ptLen)
        for (k in 0 until LzxDecoder.PRETREE_NUM_ELEMENTS) bw.write(ptLen[k], 4)
        for (k in syms.indices) {
            val s = syms[k]
            bw.write(ptCode[s], ptLen[s])
            when (s) {
                17 -> bw.write(extras[k], 4)
                18 -> bw.write(extras[k], 5)
            }
        }
    }
}

/** Escritor de bits LZX: palabras de 16 bits little-endian, bits de MSB a LSB. */
internal class BitWriter {
    private val out = ByteArrayOutputStream()
    private var acc = 0L
    private var n = 0

    fun write(value: Int, bits: Int) {
        if (bits == 0) return
        acc = (acc shl bits) or (value.toLong() and ((1L shl bits) - 1))
        n += bits
        while (n >= 16) {
            val w = ((acc ushr (n - 16)) and 0xFFFF).toInt()
            out.write(w and 0xFF)
            out.write(w ushr 8)
            n -= 16
        }
        acc = acc and ((1L shl n) - 1)
    }

    fun align16() {
        if (n > 0) write(0, 16 - n)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal object Huffman {
    /** Longitudes Huffman limitadas a [maxLen]. Siempre forman un código completo (o todo ceros). */
    fun lengths(freqIn: IntArray, maxLen: Int): IntArray {
        val n = freqIn.size
        val lens = IntArray(n)
        val used = freqIn.indices.filter { freqIn[it] > 0 }
        if (used.isEmpty()) return lens
        if (used.size == 1) {
            lens[used[0]] = 1
            lens[if (used[0] == 0) 1 else 0] = 1
            return lens
        }
        val freq = freqIn.copyOf()
        while (true) {
            build(freq, lens)
            if (lens.max() <= maxLen) return lens
            for (i in freq.indices) if (freq[i] > 0) freq[i] = maxOf(1, freq[i] / 2)
        }
    }

    private fun build(freq: IntArray, lens: IntArray) {
        lens.fill(0)
        val n = freq.size
        val parent = IntArray(2 * n) { -1 }
        val weight = LongArray(2 * n)
        val pq = PriorityQueue<Int>(compareBy<Int>({ weight[it] }, { it }))
        for (i in 0 until n) if (freq[i] > 0) {
            weight[i] = freq[i].toLong()
            pq.add(i)
        }
        var next = n
        while (pq.size > 1) {
            val a = pq.poll()
            val b = pq.poll()
            weight[next] = weight[a] + weight[b]
            parent[a] = next
            parent[b] = next
            pq.add(next)
            next++
        }
        for (i in 0 until n) if (freq[i] > 0) {
            var d = 0
            var p = parent[i]
            while (p >= 0) {
                d++
                p = parent[p]
            }
            lens[i] = d
        }
    }

    /** Códigos canónicos: por longitud y luego por símbolo (lo que espera makeDecodeTable). */
    fun codes(lens: IntArray): IntArray {
        val maxLen = lens.maxOrNull() ?: 0
        val blCount = IntArray(maxLen + 2)
        for (l in lens) if (l > 0) blCount[l]++
        val nextCode = IntArray(maxLen + 2)
        var code = 0
        for (bits in 1..maxLen) {
            code = (code + blCount[bits - 1]) shl 1
            nextCode[bits] = code
        }
        val codes = IntArray(lens.size)
        for (i in lens.indices) {
            val l = lens[i]
            if (l != 0) codes[i] = nextCode[l]++
        }
        return codes
    }
}

/**
 * Escribe datos en formato XMemCompress (bloques LZX con cabecera de tamaño).
 * Al cerrar se escribe el último frame (incompleto).
 */
class XMemCompressOutputStream(out: OutputStream, windowBits: Int = 16) : FilterOutputStream(out) {
    private val enc = LzxEncoder(windowBits)
    private val frame = ByteArray(LzxEncoder.FRAME_SIZE)
    private var n = 0
    private var closed = false

    override fun write(b: Int) {
        frame[n++] = b.toByte()
        if (n == frame.size) flushFrame()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var l = len
        while (l > 0) {
            val c = minOf(l, frame.size - n)
            System.arraycopy(b, o, frame, n, c)
            n += c
            o += c
            l -= c
            if (n == frame.size) flushFrame()
        }
    }

    private fun flushFrame() {
        if (n == 0) return
        val comp = enc.encodeFrame(frame, 0, n)
        if (n == LzxEncoder.FRAME_SIZE) {
            out.write(comp.size ushr 8)
            out.write(comp.size and 0xFF)
        } else {
            out.write(0xFF)
            out.write(n ushr 8)
            out.write(n and 0xFF)
            out.write(comp.size ushr 8)
            out.write(comp.size and 0xFF)
        }
        out.write(comp)
        n = 0
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        flushFrame()
        super.close()
    }
}
