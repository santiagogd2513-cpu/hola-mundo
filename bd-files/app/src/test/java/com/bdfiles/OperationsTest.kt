package com.bdfiles

import com.bdfiles.core.Archives
import com.bdfiles.core.Conflict
import com.bdfiles.core.FileOperations
import com.bdfiles.core.LocalSource
import com.bdfiles.core.OpProgress
import com.bdfiles.core.SortBy
import com.bdfiles.core.FileSorting
import com.bdfiles.core.FileItem
import com.bdfiles.core.uniqueName
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

class OperationsTest {
    private lateinit var tmp: File
    private lateinit var src: File
    private lateinit var dst: File
    private val progreso = ArrayList<OpProgress>()

    private fun source(root: File) = LocalSource("local", "Prueba", root.absolutePath)

    @Before
    fun setUp() {
        tmp = File.createTempFile("bdfiles", "").let { it.delete(); it.mkdirs(); it }
        src = File(tmp, "origen").apply { mkdirs() }
        dst = File(tmp, "destino").apply { mkdirs() }
        progreso.clear()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun ops(verify: Boolean = true) =
        FileOperations(report = { progreso.add(it) }, isCancelled = { false }, verify = verify)

    private fun item(f: File) = FileItem(f.name, f.absolutePath, f.isDirectory, f.length(), f.lastModified())

    @Test
    fun copiaConVerificacionYProgreso() {
        val datos = Random(1).nextBytes(500_000)
        File(src, "grande.bin").writeBytes(datos)
        File(src, "sub").mkdirs()
        File(src, "sub/pequeno.txt").writeText("hola")

        val s = source(src)
        val result = ops().copy(
            s, listOf(item(File(src, "grande.bin")), item(File(src, "sub"))),
            source(dst), dst.absolutePath, move = false,
        )

        assertTrue(result.errors.toString(), result.ok)
        assertArrayEquals(datos, File(dst, "grande.bin").readBytes())
        assertEquals("hola", File(dst, "sub/pequeno.txt").readText())
        // El original sigue donde estaba.
        assertTrue(File(src, "grande.bin").exists())
        // Hubo avisos de progreso con velocidad y tiempo restante.
        assertTrue(progreso.size > 2)
        assertTrue(progreso.any { it.bytesPerSecond > 0 })
        assertTrue(progreso.any { it.action == "Verificando" })
        assertTrue(progreso.last().doneFiles == 2)
    }

    @Test
    fun mueveYBorraElOriginalSoloSiVerifica() {
        val datos = Random(2).nextBytes(200_000)
        File(src, "mover.bin").writeBytes(datos)
        val result = ops().copy(
            source(src), listOf(item(File(src, "mover.bin"))),
            source(dst), dst.absolutePath, move = true,
        )
        assertTrue(result.ok)
        assertArrayEquals(datos, File(dst, "mover.bin").readBytes())
        assertFalse("El original debe desaparecer al mover", File(src, "mover.bin").exists())
    }

    @Test
    fun noSobrescribeSinPermiso() {
        File(src, "a.txt").writeText("nuevo")
        File(dst, "a.txt").writeText("viejo")

        ops().copy(source(src), listOf(item(File(src, "a.txt"))), source(dst), dst.absolutePath, false, Conflict.KEEP_BOTH)
        assertEquals("viejo", File(dst, "a.txt").readText())
        assertEquals("nuevo", File(dst, "a (2).txt").readText())

        ops().copy(source(src), listOf(item(File(src, "a.txt"))), source(dst), dst.absolutePath, false, Conflict.OVERWRITE)
        assertEquals("nuevo", File(dst, "a.txt").readText())

        val r = ops().copy(source(src), listOf(item(File(src, "a.txt"))), source(dst), dst.absolutePath, false, Conflict.SKIP)
        assertFalse(r.ok)
        assertTrue(r.errors.first().contains("omitió"))
    }

    @Test
    fun borraCarpetasEnteras() {
        File(src, "x/y/z").mkdirs()
        File(src, "x/y/z/f.txt").writeText("1")
        val r = ops().delete(source(src), listOf(item(File(src, "x"))))
        assertTrue(r.ok)
        assertFalse(File(src, "x").exists())
    }

    @Test
    fun comprimeYExtraeZip() {
        File(src, "carpeta/dentro").mkdirs()
        val datos = Random(3).nextBytes(120_000)
        File(src, "carpeta/dentro/datos.bin").writeBytes(datos)
        File(src, "carpeta/nota.txt").writeText("texto de prueba")

        val zip = File(tmp, "paquete.zip")
        val r = Archives.createZip(listOf(File(src, "carpeta")), zip, { progreso.add(it) }, { false })
        assertTrue(r.ok)
        assertTrue(zip.length() > 0)
        assertTrue(progreso.any { it.action == "Comprimiendo" })

        val entradas = Archives.listEntries(zip).filter { !it.isDir }.map { it.path }.sorted()
        assertEquals(listOf("carpeta/dentro/datos.bin", "carpeta/nota.txt"), entradas)

        val salida = File(tmp, "salida")
        val r2 = Archives.extract(zip, salida, null, { progreso.add(it) }, { false })
        assertTrue(r2.errors.toString(), r2.ok)
        assertArrayEquals(datos, File(salida, "carpeta/dentro/datos.bin").readBytes())
        assertEquals("texto de prueba", File(salida, "carpeta/nota.txt").readText())
    }

    @Test
    fun extraeSoloLoSeleccionado() {
        File(src, "a.txt").writeText("aaa")
        File(src, "b.txt").writeText("bbb")
        val zip = File(tmp, "dos.zip")
        Archives.createZip(listOf(File(src, "a.txt"), File(src, "b.txt")), zip, {}, { false })
        val salida = File(tmp, "solo_b")
        Archives.extract(zip, salida, setOf("b.txt"), {}, { false })
        assertFalse(File(salida, "a.txt").exists())
        assertEquals("bbb", File(salida, "b.txt").readText())
    }

    @Test
    fun editaZipAnadiendoYQuitando() {
        File(src, "uno.txt").writeText("1")
        File(src, "dos.txt").writeText("2")
        val zip = File(tmp, "editar.zip")
        Archives.createZip(listOf(File(src, "uno.txt")), zip, {}, { false })

        Archives.addToZip(zip, listOf(File(src, "dos.txt")), "", {}, { false })
        assertEquals(setOf("uno.txt", "dos.txt"), Archives.listEntries(zip).map { it.path }.toSet())

        Archives.removeFromZip(zip, setOf("uno.txt"), {}, { false })
        assertEquals(setOf("dos.txt"), Archives.listEntries(zip).map { it.path }.toSet())
    }

    @Test
    fun noEscribeFueraDeLaCarpetaAlExtraer() {
        // ZIP con una entrada maliciosa "../fuera.txt"
        val zip = File(tmp, "malo.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("../fuera.txt"))
            z.write("no deberia estar aqui".toByteArray())
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("bueno.txt"))
            z.write("si".toByteArray())
            z.closeEntry()
        }
        val salida = File(tmp, "seguro")
        val r = Archives.extract(zip, salida, null, {}, { false })
        assertFalse(File(tmp, "fuera.txt").exists())
        assertEquals("si", File(salida, "bueno.txt").readText())
        assertTrue(r.errors.any { it.contains("no permitida") })
    }

    @Test
    fun reconoceLosTiposDeComprimido() {
        assertEquals("ZIP", Archives.formatOf("a.zip")?.label)
        assertEquals("ZIP", Archives.formatOf("mod.jar")?.label)
        assertEquals("ZIP", Archives.formatOf("app.apk")?.label)
        assertEquals("7z", Archives.formatOf("x.7z")?.label)
        assertEquals("RAR", Archives.formatOf("x.rar")?.label)
        assertEquals("TAR.GZ", Archives.formatOf("x.tar.gz")?.label)
        assertEquals(null, Archives.formatOf("foto.jpg"))
    }

    @Test
    fun ordenaConAncladosPrimero() {
        val items = listOf(
            FileItem("zeta", "/zeta", true),
            FileItem("alfa", "/alfa", true),
            FileItem("b.txt", "/b.txt", false, 10),
            FileItem("a.txt", "/a.txt", false, 99),
        )
        val normal = FileSorting.sort(items, SortBy.NAME, false, true, emptySet())
        assertEquals(listOf("alfa", "zeta", "a.txt", "b.txt"), normal.map { it.name })

        val anclado = FileSorting.sort(items, SortBy.NAME, false, true, setOf("local|/zeta"))
        assertEquals("zeta", anclado.first().name)

        val porTamano = FileSorting.sort(items, SortBy.SIZE, true, true, emptySet())
        assertEquals(listOf("a.txt", "b.txt"), porTamano.filter { !it.isDir }.map { it.name })
    }

    @Test
    fun generaNombresSinRepetir() {
        val existentes = setOf("foto.jpg", "foto (2).jpg")
        assertEquals("foto (3).jpg", uniqueName(existentes, "foto.jpg"))
        assertEquals("nuevo.txt", uniqueName(existentes, "nuevo.txt"))
        assertEquals("carpeta (2)", uniqueName(setOf("carpeta"), "carpeta"))
    }
}
