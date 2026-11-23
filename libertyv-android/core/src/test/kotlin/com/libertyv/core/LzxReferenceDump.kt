package com.libertyv.core

import com.libertyv.core.compression.LzxEncoder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Genera archivos .cab con nuestro LZX para comprobarlos con una implementación de
 * referencia (cabextract / libmspack). Solo se ejecuta si existe LZX_DUMP_DIR.
 * Los frames LZX de un CAB son idénticos a los de XMemCompress (32 KB, alineados a 16 bits).
 */
class LzxReferenceDump {
    @Test
    fun dumpCabs() {
        val dir = System.getenv("LZX_DUMP_DIR")
        assumeTrue(dir != null)
        val r = Random(99)
        val text = buildString { while (length < 400_000) append(listOf("ped", "veh", "<a>", "x", "\n", "model_", "1234")[r.nextInt(7)]) }.toByteArray()
        val mixed = ByteArrayOutputStream().apply {
            val block = r.nextBytes(30_000)
            repeat(8) { write(block); write(r.nextBytes(r.nextInt(100, 40_000))); write(ByteArray(r.nextInt(1, 5000))) }
        }.toByteArray()
        val samples = mapOf("text" to text, "random" to r.nextBytes(150_001), "mixed" to mixed, "tiny" to "ab".toByteArray())
        for ((name, data) in samples) {
            File(dir, "$name.bin").writeBytes(data)
            File(dir, "$name.cab").writeBytes(makeCab("$name.bin", data, aligned = false))
            File(dir, "$name-aligned.bin").writeBytes(data)
            File(dir, "$name-aligned.cab").writeBytes(makeCab("$name-aligned.bin", data, aligned = true))
        }
    }

    private fun makeCab(fileName: String, data: ByteArray, aligned: Boolean): ByteArray {
        val enc = LzxEncoder(16, aligned)
        val blocks = ArrayList<Pair<ByteArray, Int>>()
        var off = 0
        while (off < data.size) {
            val n = minOf(32768, data.size - off)
            blocks.add(enc.encodeFrame(data, off, n) to n)
            off += n
        }
        val nameBytes = fileName.toByteArray() + 0
        val headerSize = 36
        val folderSize = 8
        val fileEntrySize = 16 + nameBytes.size
        val dataStart = headerSize + folderSize + fileEntrySize
        val total = dataStart + blocks.sumOf { 8 + it.first.size }
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put("MSCF".toByteArray()); b.putInt(0); b.putInt(total); b.putInt(0)
        b.putInt(headerSize + folderSize); b.putInt(0)
        b.put(3); b.put(1); b.putShort(1); b.putShort(1); b.putShort(0); b.putShort(0); b.putShort(0)
        b.putInt(dataStart); b.putShort(blocks.size.toShort()); b.putShort((3 or (16 shl 8)).toShort())
        b.putInt(data.size); b.putInt(0); b.putShort(0); b.putShort(0x5821); b.putShort(0); b.putShort(0x20); b.put(nameBytes)
        for ((comp, n) in blocks) {
            b.putInt(0); b.putShort(comp.size.toShort()); b.putShort(n.toShort()); b.put(comp)
        }
        return b.array()
    }
}
