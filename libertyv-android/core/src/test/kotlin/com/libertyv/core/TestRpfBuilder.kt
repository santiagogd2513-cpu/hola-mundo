package com.libertyv.core

import com.libertyv.core.compression.XMemCompressOutputStream
import com.libertyv.core.crypto.AesEcb
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Construye RPF7 sintéticos para las pruebas (el escritor real llegará en la fase 2). */
class TestRpfBuilder(private val platform: ConsolePlatform, private val key: ByteArray) {
    sealed class Node(val name: String)
    class Dir(name: String, val kids: List<Node>) : Node(name)
    class File(name: String, val data: ByteArray, val compress: Boolean, val encrypt: Boolean) : Node(name)
    class Resource(name: String, val data: ByteArray, val sysFlag: Int, val gfxFlag: Int) : Node(name)

    private val aes = AesEcb(key)

    fun compress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val s = if (platform == ConsolePlatform.PS3) {
            DeflaterOutputStream(bos, Deflater(Deflater.DEFAULT_COMPRESSION, true))
        } else {
            XMemCompressOutputStream(bos)
        }
        s.use { it.write(data) }
        return bos.toByteArray()
    }

    fun build(root: Dir): ByteArray {
        // Orden en anchura, como LibertyV: los hijos de cada carpeta quedan contiguos.
        val order = ArrayList<Node>()
        val firstChild = HashMap<Node, Int>()
        order.add(root)
        var qi = 0
        while (qi < order.size) {
            val n = order[qi++]
            if (n is Dir) {
                firstChild[n] = order.size
                order.addAll(n.kids)
            }
        }
        // Nombres (desplazamiento 0, sin alinear).
        val names = ByteArrayOutputStream()
        val nameOff = HashMap<Node, Int>()
        for (n in order) {
            nameOff[n] = names.size()
            names.write(n.name.toByteArray(Charsets.ISO_8859_1))
            names.write(0)
        }
        while (names.size() % 16 != 0) names.write(0)
        val namesBytes = names.toByteArray()

        val tocLen = 16 * order.size + namesBytes.size
        val data = ByteArrayOutputStream()
        var pos = align(16L + tocLen)
        val stored = HashMap<Node, ByteArray>()
        val offsets = HashMap<Node, Long>()
        for (n in order) {
            val bytes = when (n) {
                is File -> {
                    var b = if (n.compress) compress(n.data) else n.data
                    if (n.encrypt) {
                        b = b.copyOf()
                        aes.encryptInPlace(b)
                    }
                    b
                }
                is Resource -> {
                    var c = compress(n.data)
                    if (n.name.endsWith("sc")) {
                        c = c.copyOf(); aes.encryptInPlace(c)
                    }
                    ByteArray(16) + c
                }
                else -> continue
            }
            stored[n] = bytes
            offsets[n] = pos
            pos = align(pos + bytes.size)
        }

        val toc = ByteArray(tocLen)
        order.forEachIndexed { i, n ->
            val o = i * 16
            fun put24(at: Int, v: Int) { toc[at] = (v ushr 16).toByte(); toc[at + 1] = (v ushr 8).toByte(); toc[at + 2] = v.toByte() }
            fun put32(at: Int, v: Int) { toc[at] = (v ushr 24).toByte(); toc[at + 1] = (v ushr 16).toByte(); toc[at + 2] = (v ushr 8).toByte(); toc[at + 3] = v.toByte() }
            val no = nameOff[n]!!
            toc[o + 6] = (no ushr 8).toByte(); toc[o + 7] = no.toByte()
            when (n) {
                is Dir -> { put24(o, 0x7FFFFF); put24(o + 3, 0); put32(o + 8, firstChild[n]!!); put32(o + 12, n.kids.size) }
                is File -> {
                    val blk = (offsets[n]!! ushr 9).toInt()
                    put24(o, blk)
                    if (n.compress) {
                        put24(o + 3, stored[n]!!.size); put32(o + 8, n.data.size); put32(o + 12, if (n.encrypt) 1 else 0)
                    } else {
                        put24(o + 3, 0); put32(o + 8, n.data.size); put32(o + 12, 0)
                    }
                }
                is Resource -> {
                    put24(o, (1 shl 23) or (offsets[n]!! ushr 9).toInt())
                    put24(o + 3, stored[n]!!.size); put32(o + 8, n.sysFlag); put32(o + 12, n.gfxFlag)
                }
            }
        }
        namesBytes.copyInto(toc, 16 * order.size)
        aes.encryptInPlace(toc)

        val header = ByteArray(16)
        "RPF7".toByteArray().copyInto(header)
        fun h32(at: Int, v: Int) { header[at] = (v ushr 24).toByte(); header[at + 1] = (v ushr 16).toByte(); header[at + 2] = (v ushr 8).toByte(); header[at + 3] = v.toByte() }
        h32(4, order.size)
        h32(8, (platform.platformBit shl 31) or namesBytes.size)
        h32(12, 0)

        val total = ByteArray(pos.toInt())
        header.copyInto(total)
        toc.copyInto(total, 16)
        for ((n, b) in stored) b.copyInto(total, offsets[n]!!.toInt())
        data.close()
        return total
    }

    private fun align(p: Long) = (p + 511) / 512 * 512
}
