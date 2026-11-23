package com.libertyv.core

import com.libertyv.core.crypto.AesEcb
import com.libertyv.core.io.ByteArraySource
import com.libertyv.core.rpf.ExportTarget
import com.libertyv.core.rpf.ResourceExportMode
import com.libertyv.core.rpf.Rpf7Archive
import com.libertyv.core.rpf.RpfDirectory
import com.libertyv.core.rpf.RpfException
import com.libertyv.core.rpf.RpfExporter
import com.libertyv.core.rpf.RpfRegularFile
import com.libertyv.core.rpf.RpfResource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class RpfTest {
    private val key = Random(42).nextBytes(32)

    // Flag con 1 página de tamaño base (bit 4 => 16 * base... usamos bits 17.. => 1 * base)
    private val oneSystemPage = (1 shl 17) or (0x2 shl 28) // versión alta 2
    private val oneGraphicsPage = (1 shl 17) or (0xA shl 28)

    private fun sample(platform: ConsolePlatform): Triple<ByteArray, ByteArray, ByteArray> {
        val text = ("<CVehicleModelInfo> adder </CVehicleModelInfo>\n").repeat(3000).toByteArray()
        val bin = Random(7).nextBytes(70_000)
        val sys = platform.systemPageBase
        val gfx = platform.graphicsPageBase
        val res = ByteArray(sys + gfx) { (it * 31 % 97).toByte() }
        return Triple(text, bin, res)
    }

    private fun buildSample(platform: ConsolePlatform): ByteArray {
        val (text, bin, res) = sample(platform)
        val b = TestRpfBuilder(platform, key)
        val p = if (platform == ConsolePlatform.XBOX360) "x" else "c"
        return b.build(
            TestRpfBuilder.Dir(
                "",
                listOf(
                    TestRpfBuilder.Dir(
                        "common",
                        listOf(
                            TestRpfBuilder.File("vehicles.meta", text, compress = true, encrypt = true),
                            TestRpfBuilder.File("raw.bin", bin, compress = false, encrypt = false),
                            TestRpfBuilder.Dir("empty", emptyList()),
                        ),
                    ),
                    TestRpfBuilder.File("plain.bin", bin, compress = true, encrypt = false),
                    TestRpfBuilder.Resource("adder.${p}ft", res, oneSystemPage, oneGraphicsPage),
                    TestRpfBuilder.Resource("main.${p}sc", res, oneSystemPage, oneGraphicsPage),
                ),
            ),
        )
    }

    @Test
    fun parseAndExtractXbox() = parseAndExtract(ConsolePlatform.XBOX360)

    @Test
    fun parseAndExtractPs3() = parseAndExtract(ConsolePlatform.PS3)

    private fun parseAndExtract(platform: ConsolePlatform) {
        val (text, bin, res) = sample(platform)
        val bytes = buildSample(platform)
        assertEquals(platform, Rpf7Archive.detectPlatform(ByteArraySource(bytes)))
        val a = Rpf7Archive.open(ByteArraySource(bytes), platform, key, "test.rpf")
        assertEquals(8, a.entryCount)
        val root = a.root
        val px = if (platform == ConsolePlatform.XBOX360) "x" else "c"
        assertEquals(listOf("common", "adder.${px}ft", "main.${px}sc", "plain.bin"), root.children.map { it.name })
        val common = root.children[0] as RpfDirectory
        assertEquals(listOf("empty", "raw.bin", "vehicles.meta"), common.children.map { it.name })

        val meta = common.children[2] as RpfRegularFile
        assertTrue(meta.isCompressed && meta.isEncrypted)
        assertEquals("common/vehicles.meta", meta.path)
        assertArrayEquals(text, a.readContent(meta))
        assertArrayEquals(bin, a.readContent(common.children[1] as RpfRegularFile))
        val plain = root.children.first { it.name == "plain.bin" } as RpfRegularFile
        assertTrue(plain.isCompressed && !plain.isEncrypted)
        assertArrayEquals(bin, a.readContent(plain))

        for (r in root.children.filterIsInstance<RpfResource>()) {
            assertEquals(platform.systemPageBase.toLong(), r.systemSize)
            assertEquals(platform.graphicsPageBase.toLong(), r.graphicsSize)
            assertEquals(0x2A, r.version)
            assertArrayEquals(res, a.readContent(r))
        }
        assertTrue(root.children.filterIsInstance<RpfResource>().single { it.name.startsWith("main") }.isEncrypted)

        // Exportar toda la carpeta raíz en modo SYS + GFX.
        val files = HashMap<String, ByteArray>()
        val target = ExportTarget { path ->
            object : ByteArrayOutputStream() {
                override fun close() { files[path] = toByteArray() }
            }
        }
        val errors = RpfExporter(a, ResourceExportMode.SYS_GFX).exportDirectory(root, target) {}
        assertTrue(errors.toString(), errors.isEmpty())
        val p = if (platform == ConsolePlatform.XBOX360) "x" else "c"
        assertEquals(
            setOf("common/vehicles.meta", "common/raw.bin", "plain.bin", "adder.${p}ft.sys", "adder.${p}ft.gfx", "main.${p}sc.sys", "main.${p}sc.gfx"),
            files.keys,
        )
        assertArrayEquals(res.copyOfRange(0, platform.systemPageBase), files["adder.${p}ft.sys"])
        assertArrayEquals(res.copyOfRange(platform.systemPageBase, res.size), files["adder.${p}ft.gfx"])

        // RSC7: cabecera + datos.
        val rsc = ByteArrayOutputStream()
        val adder = root.children.filterIsInstance<RpfResource>().first()
        RpfExporter(a, ResourceExportMode.RSC7).write(adder, rsc)
        val rb = rsc.toByteArray()
        assertEquals("RSC7", String(rb, 0, 4))
        assertArrayEquals(res, rb.copyOfRange(16, rb.size))
    }

    @Test
    fun wrongPlatformIsReported() {
        val bytes = buildSample(ConsolePlatform.XBOX360)
        try {
            Rpf7Archive.open(ByteArraySource(bytes), ConsolePlatform.PS3, key)
            fail()
        } catch (e: RpfException) {
            assertTrue(e.message!!.contains("Xbox 360"))
        }
    }

    @Test
    fun aesStreamKeepsTrailingBytes() {
        val aes = AesEcb(key)
        val data = Random(5).nextBytes(100_003)
        val enc = data.copyOf()
        aes.encryptInPlace(enc)
        assertFalse(enc.contentEquals(data))
        assertArrayEquals(data.copyOfRange(100_000, 100_003), enc.copyOfRange(100_000, 100_003))
        val back = aes.decryptingStream(ByteArrayInputStream(enc)).readBytes()
        assertArrayEquals(data, back)
        val bos = ByteArrayOutputStream()
        aes.encryptingStream(bos).use { it.write(data) }
        assertArrayEquals(enc, bos.toByteArray())
    }

    @Test
    fun resourceSizes() {
        // 3 páginas de base<<1 + media página (bit 24).
        val flag = (3 shl 17) or 1 or (1 shl 24)
        assertEquals(3L * 16384 + 8192, RpfResource.sizeFromFlag(flag, 8192))
    }

    @Test
    fun realKeyHashes() {
        assertEquals(ConsolePlatform.XBOX360.keyMd5, "ead1ea1a3870557b424bc8cf73f51018")
        assertEquals(null, ConsolePlatform.forKey(key))
    }
}
