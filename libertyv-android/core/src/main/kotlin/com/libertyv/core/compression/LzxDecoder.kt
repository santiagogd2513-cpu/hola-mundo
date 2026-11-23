package com.libertyv.core.compression

import java.io.IOException

class LzxException(msg: String) : IOException(msg)

/**
 * Decodificador LZX (el formato que usa XMemCompress de Xbox 360).
 *
 * Sigue el comportamiento del decodificador LZX clásico (LZXinit / LZXdecompress):
 * cada llamada a [decompress] decodifica un "frame" completo cuyo flujo de bits empieza
 * alineado, mientras que la ventana, los árboles Huffman y los offsets repetidos se
 * conservan entre frames.
 */
class LzxDecoder(windowBits: Int) {
    companion object {
        private const val MIN_MATCH = 2
        private const val NUM_CHARS = 256
        const val BLOCKTYPE_VERBATIM = 1
        const val BLOCKTYPE_ALIGNED = 2
        const val BLOCKTYPE_UNCOMPRESSED = 3
        const val PRETREE_NUM_ELEMENTS = 20
        const val ALIGNED_NUM_ELEMENTS = 8
        const val NUM_PRIMARY_LENGTHS = 7
        const val NUM_SECONDARY_LENGTHS = 249

        private const val PRETREE_MAXSYMBOLS = PRETREE_NUM_ELEMENTS
        private const val PRETREE_TABLEBITS = 6
        private const val MAINTREE_MAXSYMBOLS = NUM_CHARS + 50 * 8
        private const val MAINTREE_TABLEBITS = 12
        private const val LENGTH_MAXSYMBOLS = NUM_SECONDARY_LENGTHS + 1
        private const val LENGTH_TABLEBITS = 12
        private const val ALIGNED_MAXSYMBOLS = ALIGNED_NUM_ELEMENTS
        private const val ALIGNED_TABLEBITS = 7
        private const val LENTABLE_SAFETY = 64

        val EXTRA_BITS = IntArray(52)
        val POSITION_BASE = IntArray(51)

        init {
            var j = 0
            var i = 0
            while (i <= 50) {
                EXTRA_BITS[i] = j
                EXTRA_BITS[i + 1] = j
                if (i != 0 && j < 17) j++
                i += 2
            }
            j = 0
            for (k in 0..50) {
                POSITION_BASE[k] = j
                j += 1 shl EXTRA_BITS[k]
            }
        }

        fun positionSlots(windowBits: Int): Int = when (windowBits) {
            20 -> 42
            21 -> 50
            else -> windowBits shl 1
        }

        /**
         * Construye la tabla de decodificación Huffman canónica (igual que make_decode_table).
         * Devuelve false si las longitudes no forman un código válido.
         */
        fun makeDecodeTable(nsyms: Int, nbits: Int, length: IntArray, table: IntArray): Boolean {
            var pos = 0
            var tableMask = 1 shl nbits
            var bitMask = tableMask ushr 1
            var nextSymbol = bitMask
            var bitNum = 1

            while (bitNum <= nbits) {
                for (sym in 0 until nsyms) {
                    if (length[sym] == bitNum) {
                        var leaf = pos
                        pos += bitMask
                        if (pos > tableMask) return false
                        var fill = bitMask
                        while (fill-- > 0) table[leaf++] = sym
                    }
                }
                bitMask = bitMask ushr 1
                bitNum++
            }

            if (pos != tableMask) {
                for (sym in pos until tableMask) table[sym] = 0
                pos = pos shl 16
                tableMask = tableMask shl 16
                bitMask = 1 shl 15

                while (bitNum <= 16) {
                    for (sym in 0 until nsyms) {
                        if (length[sym] == bitNum) {
                            var leaf = pos ushr 16
                            for (fill in 0 until bitNum - nbits) {
                                if (table[leaf] == 0) {
                                    table[nextSymbol shl 1] = 0
                                    table[(nextSymbol shl 1) + 1] = 0
                                    table[leaf] = nextSymbol++
                                }
                                leaf = table[leaf] shl 1
                                if (((pos ushr (15 - fill)) and 1) != 0) leaf++
                            }
                            table[leaf] = sym
                            pos += bitMask
                            if (pos > tableMask) return false
                        }
                    }
                    bitMask = bitMask ushr 1
                    bitNum++
                }
            }

            if (pos == tableMask) return true
            for (sym in 0 until nsyms) if (length[sym] != 0) return false
            return true
        }
    }

    private val windowSize = 1 shl windowBits
    private val window = ByteArray(windowSize)
    private var windowPosn = 0
    private var framePosn = 0
    private var r0 = 1
    private var r1 = 1
    private var r2 = 1
    private val mainElements = NUM_CHARS + (positionSlots(windowBits) shl 3)
    private var headerRead = false
    private var blockType = 0
    private var blockLength = 0
    private var blockRemaining = 0
    private var intelFileSize = 0
    private var intelCurPos = 0
    private var intelStarted = false
    private var framesRead = 0

    private val pretreeLen = IntArray(PRETREE_MAXSYMBOLS + LENTABLE_SAFETY)
    private val pretreeTable = IntArray((1 shl PRETREE_TABLEBITS) + (PRETREE_MAXSYMBOLS shl 1))
    private val mainLen = IntArray(MAINTREE_MAXSYMBOLS + LENTABLE_SAFETY)
    private val mainTable = IntArray((1 shl MAINTREE_TABLEBITS) + (MAINTREE_MAXSYMBOLS shl 1))
    private val lengthLen = IntArray(LENGTH_MAXSYMBOLS + LENTABLE_SAFETY)
    private val lengthTable = IntArray((1 shl LENGTH_TABLEBITS) + (LENGTH_MAXSYMBOLS shl 1))
    private val alignedLen = IntArray(ALIGNED_MAXSYMBOLS + LENTABLE_SAFETY)
    private val alignedTable = IntArray((1 shl ALIGNED_TABLEBITS) + (ALIGNED_MAXSYMBOLS shl 1))

    // Estado del flujo de bits (palabras de 16 bits little-endian, bits de MSB a LSB).
    private var inBuf = ByteArray(0)
    private var inPos = 0
    private var inEnd = 0
    private var bitBuf = 0
    private var bitsLeft = 0

    init {
        require(windowBits in 15..21) { "Ventana LZX no soportada: $windowBits" }
    }

    private fun byteAt(i: Int): Int = if (i < inEnd) inBuf[i].toInt() and 0xFF else 0

    private fun initBitstream() {
        bitBuf = 0
        bitsLeft = 0
    }

    private fun ensureBits(n: Int) {
        while (bitsLeft < n) {
            val word = byteAt(inPos) or (byteAt(inPos + 1) shl 8)
            bitBuf = bitBuf or (word shl (16 - bitsLeft))
            bitsLeft += 16
            inPos += 2
        }
    }

    private fun peekBits(n: Int): Int = if (n == 0) 0 else bitBuf ushr (32 - n)

    private fun removeBits(n: Int) {
        bitBuf = bitBuf shl n
        bitsLeft -= n
    }

    private fun readBits(n: Int): Int {
        if (n == 0) return 0
        ensureBits(n)
        val v = peekBits(n)
        removeBits(n)
        return v
    }

    private fun readHuffSym(table: IntArray, lens: IntArray, maxSymbols: Int, tableBits: Int): Int {
        ensureBits(16)
        var i = table[peekBits(tableBits)]
        if (i >= maxSymbols) {
            var j = 1 shl (32 - tableBits)
            do {
                j = j ushr 1
                i = i shl 1
                i = i or (if ((bitBuf and j) != 0) 1 else 0)
                if (j == 0) throw LzxException("Código Huffman inválido")
                if (i >= table.size) throw LzxException("Código Huffman inválido")
                i = table[i]
            } while (i >= maxSymbols)
        }
        removeBits(lens[i])
        return i
    }

    private fun readLengths(lens: IntArray, first: Int, last: Int) {
        for (x in 0 until PRETREE_NUM_ELEMENTS) pretreeLen[x] = readBits(4)
        if (!makeDecodeTable(PRETREE_MAXSYMBOLS, PRETREE_TABLEBITS, pretreeLen, pretreeTable)) {
            throw LzxException("Pre-árbol LZX inválido")
        }
        var x = first
        while (x < last) {
            var z = readHuffSym(pretreeTable, pretreeLen, PRETREE_MAXSYMBOLS, PRETREE_TABLEBITS)
            when (z) {
                17 -> {
                    var y = readBits(4) + 4
                    while (y-- > 0) setLen(lens, x++, 0)
                }
                18 -> {
                    var y = readBits(5) + 20
                    while (y-- > 0) setLen(lens, x++, 0)
                }
                19 -> {
                    var y = readBits(1) + 4
                    z = readHuffSym(pretreeTable, pretreeLen, PRETREE_MAXSYMBOLS, PRETREE_TABLEBITS)
                    z = lens[x] - z
                    if (z < 0) z += 17
                    while (y-- > 0) setLen(lens, x++, z)
                }
                else -> {
                    z = lens[x] - z
                    if (z < 0) z += 17
                    setLen(lens, x++, z)
                }
            }
        }
    }

    private fun setLen(lens: IntArray, x: Int, v: Int) {
        if (x >= lens.size) throw LzxException("Tabla de longitudes desbordada")
        lens[x] = v
    }

    /**
     * Decodifica un frame: [inLen] bytes comprimidos producen exactamente [outLen] bytes.
     */
    fun decompress(input: ByteArray, inOff: Int, inLen: Int, output: ByteArray, outOff: Int, outLen: Int) {
        inBuf = input
        inPos = inOff
        inEnd = inOff + inLen
        initBitstream()

        if (!headerRead) {
            var i = 0
            var j = 0
            if (readBits(1) != 0) {
                i = readBits(16)
                j = readBits(16)
            }
            intelFileSize = (i shl 16) or j
            headerRead = true
        }

        if (framePosn >= windowSize) framePosn -= windowSize
        if (windowPosn >= windowSize) windowPosn -= windowSize
        val frameStart = framePosn
        val frameEnd = frameStart + outLen
        if (outLen < 0 || frameEnd > windowSize) throw LzxException("Tamaño de frame inválido: $outLen")

        while (windowPosn < frameEnd) {
            if (blockRemaining == 0) {
                if (blockType == BLOCKTYPE_UNCOMPRESSED) {
                    if ((blockLength and 1) != 0) inPos++ // byte de relleno
                    initBitstream()
                }
                blockType = readBits(3)
                val hi = readBits(16)
                val lo = readBits(8)
                blockLength = (hi shl 8) or lo
                blockRemaining = blockLength

                when (blockType) {
                    BLOCKTYPE_ALIGNED, BLOCKTYPE_VERBATIM -> {
                        if (blockType == BLOCKTYPE_ALIGNED) {
                            for (k in 0 until ALIGNED_NUM_ELEMENTS) alignedLen[k] = readBits(3)
                            if (!makeDecodeTable(ALIGNED_MAXSYMBOLS, ALIGNED_TABLEBITS, alignedLen, alignedTable)) {
                                throw LzxException("Árbol 'aligned' inválido")
                            }
                        }
                        readLengths(mainLen, 0, NUM_CHARS)
                        readLengths(mainLen, NUM_CHARS, mainElements)
                        if (!makeDecodeTable(MAINTREE_MAXSYMBOLS, MAINTREE_TABLEBITS, mainLen, mainTable)) {
                            throw LzxException("Árbol principal inválido")
                        }
                        if (mainLen[0xE8] != 0) intelStarted = true
                        readLengths(lengthLen, 0, NUM_SECONDARY_LENGTHS)
                        if (!makeDecodeTable(LENGTH_MAXSYMBOLS, LENGTH_TABLEBITS, lengthLen, lengthTable)) {
                            throw LzxException("Árbol de longitudes inválido")
                        }
                    }
                    BLOCKTYPE_UNCOMPRESSED -> {
                        intelStarted = true
                        ensureBits(16)
                        if (bitsLeft > 16) inPos -= 2
                        if (inPos + 12 > inEnd) throw LzxException("Bloque sin comprimir truncado")
                        r0 = le32(inBuf, inPos)
                        r1 = le32(inBuf, inPos + 4)
                        r2 = le32(inBuf, inPos + 8)
                        inPos += 12
                    }
                    else -> throw LzxException("Tipo de bloque LZX inválido: $blockType")
                }
            }

            if (inPos > inEnd) {
                if (inPos > inEnd + 2 || bitsLeft < 16) throw LzxException("Datos LZX truncados")
            }

            var thisRun = minOf(blockRemaining, frameEnd - windowPosn)
            if (thisRun <= 0) continue
            blockRemaining -= thisRun

            when (blockType) {
                BLOCKTYPE_VERBATIM, BLOCKTYPE_ALIGNED -> {
                    val aligned = blockType == BLOCKTYPE_ALIGNED
                    while (thisRun > 0) {
                        var mainElement = readHuffSym(mainTable, mainLen, MAINTREE_MAXSYMBOLS, MAINTREE_TABLEBITS)
                        if (mainElement < NUM_CHARS) {
                            window[windowPosn++] = mainElement.toByte()
                            thisRun--
                            continue
                        }
                        mainElement -= NUM_CHARS
                        var matchLength = mainElement and NUM_PRIMARY_LENGTHS
                        if (matchLength == NUM_PRIMARY_LENGTHS) {
                            matchLength += readHuffSym(lengthTable, lengthLen, LENGTH_MAXSYMBOLS, LENGTH_TABLEBITS)
                        }
                        matchLength += MIN_MATCH
                        var matchOffset = mainElement ushr 3

                        if (matchOffset > 2) {
                            if (aligned) {
                                var extra = EXTRA_BITS[matchOffset]
                                matchOffset = POSITION_BASE[matchOffset] - 2
                                if (extra > 3) {
                                    extra -= 3
                                    matchOffset += readBits(extra) shl 3
                                    matchOffset += readHuffSym(alignedTable, alignedLen, ALIGNED_MAXSYMBOLS, ALIGNED_TABLEBITS)
                                } else if (extra == 3) {
                                    matchOffset += readHuffSym(alignedTable, alignedLen, ALIGNED_MAXSYMBOLS, ALIGNED_TABLEBITS)
                                } else if (extra > 0) {
                                    matchOffset += readBits(extra)
                                } else {
                                    matchOffset = 1
                                }
                            } else {
                                matchOffset = if (matchOffset != 3) {
                                    POSITION_BASE[matchOffset] - 2 + readBits(EXTRA_BITS[matchOffset])
                                } else {
                                    1
                                }
                            }
                            r2 = r1
                            r1 = r0
                            r0 = matchOffset
                        } else if (matchOffset == 0) {
                            matchOffset = r0
                        } else if (matchOffset == 1) {
                            matchOffset = r1
                            r1 = r0
                            r0 = matchOffset
                        } else {
                            matchOffset = r2
                            r2 = r0
                            r0 = matchOffset
                        }

                        if (windowPosn + matchLength > windowSize) throw LzxException("Coincidencia fuera de la ventana")
                        if (matchOffset <= 0 || matchOffset > windowSize) throw LzxException("Offset LZX inválido")

                        var rundest = windowPosn
                        thisRun -= matchLength
                        var runsrc: Int
                        if (windowPosn >= matchOffset) {
                            runsrc = rundest - matchOffset
                        } else {
                            runsrc = rundest + (windowSize - matchOffset)
                            var copyLength = matchOffset - windowPosn
                            if (copyLength < matchLength) {
                                matchLength -= copyLength
                                windowPosn += copyLength
                                while (copyLength-- > 0) window[rundest++] = window[runsrc++]
                                runsrc = 0
                            }
                        }
                        windowPosn += matchLength
                        while (matchLength-- > 0) window[rundest++] = window[runsrc++]
                    }
                    // Una coincidencia puede pasarse del final del frame: se descuenta del bloque.
                    if (thisRun < 0) {
                        if (-thisRun > blockRemaining) throw LzxException("Coincidencia más larga que el bloque")
                        blockRemaining -= -thisRun
                    }
                }
                BLOCKTYPE_UNCOMPRESSED -> {
                    if (inPos + thisRun > inEnd) throw LzxException("Bloque sin comprimir truncado")
                    System.arraycopy(inBuf, inPos, window, windowPosn, thisRun)
                    inPos += thisRun
                    windowPosn += thisRun
                }
                else -> throw LzxException("Tipo de bloque LZX inválido: $blockType")
            }
        }

        System.arraycopy(window, frameStart, output, outOff, outLen)
        framePosn = frameEnd
        undoE8(output, outOff, outLen)
    }

    private fun undoE8(out: ByteArray, off: Int, len: Int) {
        if (len <= 10 || !intelStarted || intelFileSize == 0 || framesRead >= 32768) {
            intelCurPos += len
            framesRead++
            return
        }
        framesRead++
        var data = off
        val dataEnd = off + len - 10
        var curPos = intelCurPos
        val fileSize = intelFileSize
        intelCurPos = curPos + len
        while (data < dataEnd) {
            if ((out[data++].toInt() and 0xFF) != 0xE8) {
                curPos++
                continue
            }
            val absOff = le32(out, data)
            if (absOff >= -curPos && absOff < fileSize) {
                val relOff = if (absOff >= 0) absOff - curPos else absOff + fileSize
                out[data] = relOff.toByte()
                out[data + 1] = (relOff ushr 8).toByte()
                out[data + 2] = (relOff ushr 16).toByte()
                out[data + 3] = (relOff ushr 24).toByte()
            }
            data += 4
            curPos += 5
        }
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
