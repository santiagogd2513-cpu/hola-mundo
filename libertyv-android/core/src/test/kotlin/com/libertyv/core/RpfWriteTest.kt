package com.libertyv.core

import com.libertyv.core.io.ByteArraySource
import com.libertyv.core.io.FileChannelSource
import com.libertyv.core.rpf.Rpf7Archive
import com.libertyv.core.rpf.Rpf7Writer
import com.libertyv.core.rpf.RpfDirectory
import com.libertyv.core.rpf.RpfException
import com.libertyv.core.rpf.RpfRegularFile
import com.libertyv.core.rpf.RpfResource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

class RpfWriteTest {
    private val key = Random(42).nextBytes(32)

    private fun sampleArchive(platform: ConsolePlatform): Rpf7Archive {
        val text = "<CVehicleModelInfo> adder </CVehicleModelInfo>\n".repeat(3000).toByteArray()
        val p = if (platform == ConsolePlatform.XBOX360) "x" else "c"
        val res = ByteArray(platform.systemPageBase + platform.graphicsPageBase) { (it * 31 % 97).toByte() }
        val flagS = (1 shl 17) or (0x2 shl 28)
        val flagG = (1 shl 17) or (0xA shl 28)
        val bytes = TestRpfBuilder(platform, key).build(
            TestRpfBuilder.Dir(
                "",
                listOf(
                    TestRpfBuilder.Dir(
                        "common",
                        listOf(
                            TestRpfBuilder.File("vehicles.meta", text, compress = true, encrypt = true),
                            TestRpfBuilder.File("raw.bin", Random(7).nextBytes(5000), compress = false, encrypt = false),
                        ),
                    ),
                    TestRpfBuilder.File("Zeta.txt", "zeta".toByteArray(), compress = false, encrypt = false),
                    TestRpfBuilder.Resource("adder.${p}ft", res, flagS, flagG),
                ),
            ),
        )
        return Rpf7Archive.open(ByteArraySource(bytes), platform, key, "test.rpf")
    }

    private fun save(a: Rpf7Archive): Pair<File, Rpf7Archive> {
        val f = File.createTempFile("rpfw", ".rpf")
        f.deleteOnExit()
        RandomAccessFile(f, "rw").use { raf -> Rpf7Writer.write(a, raf.channel) }
        val raf = RandomAccessFile(f, "r")
        return f to Rpf7Archive.open(FileChannelSource(raf.channel, raf), a.platform, key, f.name)
    }

    private fun rsc7(platform: ConsolePlatform, payload: ByteArray, sys: Int, gfx: Int): ByteArray {
        val h = ByteArray(16)
        "RSC7".toByteArray().copyInto(h)
        fun put(o: Int, v: Int) { h[o] = (v ushr 24).toByte(); h[o + 1] = (v ushr 16).toByte(); h[o + 2] = (v ushr 8).toByte(); h[o + 3] = v.toByte() }
        put(4, RpfResource.versionFromFlags(sys, gfx)); put(8, sys); put(12, gfx)
        return h + payload
    }

    @Test fun editAndSaveXbox() = editAndSave(ConsolePlatform.XBOX360)

    @Test fun editAndSavePs3() = editAndSave(ConsolePlatform.PS3)

    private fun editAndSave(platform: ConsolePlatform) {
        val a = sampleArchive(platform)
        val p = if (platform == ConsolePlatform.XBOX360) "x" else "c"
        val common = a.root.get("common") as RpfDirectory
        val originalMetaStored = a.source.readFully((common.get("vehicles.meta") as RpfRegularFile).offset, (common.get("vehicles.meta") as RpfRegularFile).storedSize.toInt())

        // Cambios: añadir, reemplazar, carpeta nueva, renombrar, borrar, recurso, script cifrado.
        val newText = "<handling> mucho texto que se comprime bien </handling>\n".repeat(5000).toByteArray()
        val bigRandom = Random(9).nextBytes(150_000)
        val awc = Random(10).nextBytes(10_000)
        assertFalse(a.isModified)
        a.putFile(common, "handling.meta", ByteArraySource(newText))
        a.putFile(common, "raw.bin", ByteArraySource(bigRandom)) // reemplazo
        val audio = a.ensureFolders(a.root, "audio/sfx")
        a.putFile(audio, "test.awc", ByteArraySource(awc))
        a.rename(a.root.get("Zeta.txt")!!, "alpha.txt")
        val sys = (1 shl 17) or (0x3 shl 28)
        val gfx = (2 shl 17) or (0x1 shl 28)
        val resPayload = ByteArray(platform.systemPageBase + 2 * platform.graphicsPageBase) { (it % 251).toByte() }
        a.putFile(a.root, "new.${p}td", ByteArraySource(rsc7(platform, resPayload, sys, gfx)))
        val scriptPayload = ByteArray(platform.systemPageBase + 2 * platform.graphicsPageBase) { (it % 13).toByte() }
        a.putFile(a.root, "main.${p}sc", ByteArraySource(rsc7(platform, scriptPayload, sys, gfx)))
        a.createFolder(a.root, "borrar")
        a.delete(a.root.get("borrar")!!)
        assertTrue(a.isModified)

        val handling = common.get("handling.meta") as RpfRegularFile
        assertTrue(handling.isCompressed && handling.isEncrypted)
        val awcEntry = audio.get("test.awc") as RpfRegularFile
        assertFalse("Los .awc no se comprimen", awcEntry.isCompressed)
        assertTrue(a.root.get("new.${p}td") is RpfResource)

        val (file, b) = save(a)
        assertTrue(Rpf7Writer.verify(a, FileChannelSource(RandomAccessFile(file, "r").channel), key).isEmpty())

        assertEquals(listOf("audio", "common", "adder.${p}ft", "alpha.txt", "main.${p}sc", "new.${p}td"), b.root.children.map { it.name })
        assertNull(b.root.get("borrar"))
        val bCommon = b.root.get("common") as RpfDirectory
        assertArrayEquals(newText, b.readContent(bCommon.get("handling.meta") as RpfRegularFile))
        assertArrayEquals(bigRandom, b.readContent(bCommon.get("raw.bin") as RpfRegularFile))
        assertArrayEquals(awc, b.readContent(b.root.resolve("audio/sfx/test.awc") as RpfRegularFile))
        assertArrayEquals("zeta".toByteArray(), b.readContent(b.root.get("alpha.txt") as RpfRegularFile))
        val newRes = b.root.get("new.${p}td") as RpfResource
        assertEquals(sys, newRes.systemFlag)
        assertEquals(gfx, newRes.graphicsFlag)
        assertArrayEquals(resPayload, b.readContent(newRes))
        val script = b.root.get("main.${p}sc") as RpfResource
        assertTrue(script.isEncrypted)
        assertArrayEquals(scriptPayload, b.readContent(script))

        // Lo que no cambió se copia byte a byte.
        val meta = bCommon.get("vehicles.meta") as RpfRegularFile
        assertArrayEquals(originalMetaStored, b.source.readFully(meta.offset, meta.storedSize.toInt()))
        assertTrue(meta.isCompressed && meta.isEncrypted)
        // Los nuevos comprimidos quedan comprimidos y cifrados dentro del RPF.
        val h2 = bCommon.get("handling.meta") as RpfRegularFile
        assertTrue(h2.isCompressed && h2.isEncrypted && h2.storedSize < newText.size / 5)
        assertEquals(0L, h2.offset % 512)

        // Volver a guardar sin cambios da exactamente los mismos bytes.
        val (file2, _) = save(b)
        assertArrayEquals(file.readBytes(), file2.readBytes())
    }

    @Test
    fun manyLongNamesUseNameShift() {
        val a = sampleArchive(ConsolePlatform.XBOX360)
        val dir = a.createFolder(a.root, "muchos")
        repeat(1100) { i ->
            a.putFile(dir, "un_nombre_de_archivo_bastante_largo_para_llenar_la_tabla_${"%04d".format(i)}.dat", ByteArraySource(byteArrayOf(i.toByte())))
        }
        val (_, b) = save(a)
        val d = b.root.get("muchos") as RpfDirectory
        assertEquals(1100, d.children.size)
        assertArrayEquals(byteArrayOf(5), b.readContent(d.get("un_nombre_de_archivo_bastante_largo_para_llenar_la_tabla_0005.dat") as RpfRegularFile))
    }

    @Test
    fun invalidEditsAreRejected() {
        val a = sampleArchive(ConsolePlatform.XBOX360)
        val common = a.root.get("common") as RpfDirectory
        fun expectError(block: () -> Unit) = try { block(); fail() } catch (_: RpfException) {}
        expectError { a.rename(common.get("raw.bin")!!, "vehicles.meta") } // duplicado
        expectError { a.rename(common, "con/barra") }
        expectError { a.createFolder(a.root, "Zeta.txt") }
        expectError { a.putFile(a.root, "common", ByteArraySource(ByteArray(3))) } // es carpeta
        // Reemplazar un recurso con algo que no es RSC7.
        expectError { a.putFile(a.root, "adder.xft", ByteArraySource(ByteArray(100))) }
        assertFalse(a.isModified)
    }
}
