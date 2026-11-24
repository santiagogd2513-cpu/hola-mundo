package com.bdfiles

import com.bdfiles.core.UserError
import com.bdfiles.net.FtpClient
import com.bdfiles.net.FtpDeviceType
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class FtpClientTest {
    private lateinit var root: File
    private var server: FakeFtpServer? = null

    @Before
    fun setUp() {
        root = File.createTempFile("ftproot", "").let {
            it.delete(); it.mkdirs(); it
        }
    }

    @After
    fun tearDown() {
        server?.stop()
        root.deleteRecursively()
    }

    private fun start(dosStyle: Boolean = false): FtpClient {
        val s = FakeFtpServer(root, dosStyle = dosStyle)
        s.start()
        server = s
        return FtpClient(
            host = "127.0.0.1",
            port = s.port,
            user = "usuario",
            password = "secreto",
            deviceType = FtpDeviceType.PC_OTHER,
        ).also { it.connect() }
    }

    @Test
    fun listaArchivosYCarpetas() {
        File(root, "carpeta").mkdirs()
        File(root, "hola mundo.txt").writeText("abc")
        File(root, "datos.bin").writeBytes(ByteArray(5000))
        val c = start()
        val items = c.list("/").sortedBy { it.name }
        assertEquals(listOf("carpeta", "datos.bin", "hola mundo.txt"), items.map { it.name })
        assertTrue(items[0].isDir)
        assertEquals(5000L, items[1].size)
        assertEquals(3L, items[2].size)
        c.disconnect()
    }

    @Test
    fun listaEnFormatoDos() {
        File(root, "juegos").mkdirs()
        File(root, "save.bin").writeBytes(ByteArray(1234))
        val c = start(dosStyle = true)
        val items = c.list("/").sortedBy { it.name }
        assertEquals(listOf("juegos", "save.bin"), items.map { it.name })
        assertTrue(items[0].isDir)
        assertEquals(1234L, items[1].size)
        c.disconnect()
    }

    @Test
    fun descargaYSubeArchivos() {
        val data = Random(1).nextBytes(300_000)
        File(root, "origen.bin").writeBytes(data)
        val c = start()

        val out = ByteArrayOutputStream()
        var lastProgress = 0L
        c.download("/origen.bin", out) { lastProgress = it }
        assertArrayEquals(data, out.toByteArray())
        assertEquals(data.size.toLong(), lastProgress)

        val subida = Random(2).nextBytes(150_000)
        c.upload("/subido.bin", ByteArrayInputStream(subida))
        assertArrayEquals(subida, File(root, "subido.bin").readBytes())
        assertEquals(subida.size.toLong(), c.size("/subido.bin"))
        c.disconnect()
    }

    @Test
    fun flujoDeLecturaPorPartes() {
        val data = Random(3).nextBytes(100_000)
        File(root, "stream.bin").writeBytes(data)
        val c = start()
        val leido = c.openReadStream("/stream.bin").use { it.readBytes() }
        assertArrayEquals(data, leido)
        // La conexión sigue sirviendo después de leer.
        assertEquals(1, c.list("/").size)
        c.disconnect()
    }

    @Test
    fun carpetasRenombrarYBorrar() {
        val c = start()
        c.mkdir("/nueva")
        assertTrue(File(root, "nueva").isDirectory)
        c.upload("/nueva/a.txt", ByteArrayInputStream("hola".toByteArray()))
        c.rename("/nueva/a.txt", "/nueva/b.txt")
        assertTrue(File(root, "nueva/b.txt").isFile)
        c.deleteFile("/nueva/b.txt")
        c.deleteDir("/nueva")
        assertTrue(!File(root, "nueva").exists())
        c.disconnect()
    }

    @Test
    fun contrasenaIncorrectaDaMensajeClaro() {
        val s = FakeFtpServer(root)
        s.start()
        server = s
        val c = FtpClient("127.0.0.1", s.port, "usuario", "malapass", FtpDeviceType.PC_OTHER)
        try {
            c.connect()
            fail("Debería haber fallado")
        } catch (e: UserError) {
            assertTrue(e.message!!.contains("contraseña", ignoreCase = true))
        }
    }

    @Test
    fun servidorApagadoDaMensajeClaro() {
        val c = FtpClient("127.0.0.1", 1, "u", "p", FtpDeviceType.PC_OTHER, timeoutMs = 1500)
        try {
            c.connect()
            fail("Debería haber fallado")
        } catch (e: UserError) {
            assertTrue(e.message!!.contains("No se pudo conectar"))
        }
    }

    @Test
    fun perfilDeXbox360UsaValoresPropios() {
        val x = FtpDeviceType.XBOX360
        assertEquals("xbox", x.defaultUser)
        assertEquals("xbox", x.defaultPass)
        assertTrue("La Xbox necesita más tiempo de espera", x.timeoutMs >= 30_000)
        assertTrue("Los FTP de Xbox no suelen entender UTF-8", !x.utf8)
        assertTrue(x.defaultPassive)
    }

    @Test
    fun analizaListadosDeConsolasReales() {
        // Formato típico de FSD/Aurora en Xbox 360.
        val xbox = """
            drwxr-xr-x   1 root root            0 Jan 01  2013 Hdd1
            drwxr-xr-x   1 root root            0 Jan 01  2013 Usb0
            -rw-r--r--   1 root root      7340032 Mar 14 10:22 default.xex
        """.trimIndent()
        val items = FtpClient.parseList(xbox)
        assertEquals(listOf("Hdd1", "Usb0", "default.xex"), items.map { it.name })
        assertTrue(items[0].isDir && items[1].isDir)
        assertTrue(!items[2].isDir)
        assertEquals(7340032L, items[2].size)

        // Nombres con espacios y enlaces.
        val conEspacios = FtpClient.parseList(
            "-rw-r--r-- 1 u g 120 Feb 03 08:00 mi archivo con espacios.txt\n" +
                "lrwxrwxrwx 1 u g 7 Feb 03 08:00 atajo -> /Hdd1",
        )
        assertEquals("mi archivo con espacios.txt", conEspacios[0].name)
        assertEquals("atajo", conEspacios[1].name)
        assertTrue(conEspacios[1].isDir)
    }
}
