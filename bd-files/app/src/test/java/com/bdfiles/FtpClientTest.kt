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
        assertEquals("Por defecto, los de Aurora", "xboxftp", x.defaultUser)
        assertEquals("xboxftp", x.defaultPass)
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

/** Casos pensados para las consolas: credenciales de Aurora, servidores raros y registro. */
class FtpConsoleTest {
    private lateinit var root: File
    private var server: FakeFtpServer? = null

    @Before
    fun setUp() {
        root = File.createTempFile("ftpcon", "").let { it.delete(); it.mkdirs(); it }
        File(root, "Hdd1").mkdirs()
        File(root, "Usb0").mkdirs()
        File(root, "default.xex").writeBytes(ByteArray(2048))
    }

    @After
    fun tearDown() {
        server?.stop()
        root.deleteRecursively()
    }

    private fun start(
        user: String = "xboxftp",
        pass: String = "xboxftp",
        refuseCwd: Boolean = false,
        onlyEpsv: Boolean = false,
    ): FakeFtpServer {
        val s = FakeFtpServer(root, acceptUser = user, acceptPass = pass, refuseCwd = refuseCwd, onlyEpsv = onlyEpsv)
        s.start()
        server = s
        return s
    }

    @Test
    fun elPerfilDeXboxTraeLosDatosDeAurora() {
        val x = FtpDeviceType.XBOX360
        assertEquals("xboxftp", x.defaultUser)
        assertEquals("xboxftp", x.defaultPass)
        assertEquals("Aurora", x.knownCredentials.first().from)
        // FSD y compañía siguen estando como alternativa
        assertTrue(x.knownCredentials.any { it.user == "xbox" && it.password == "xbox" })
        assertEquals(1, x.defaultConnections)
        assertTrue(x.connectionsLocked)

        val ps3 = FtpDeviceType.PS3
        assertEquals("root", ps3.defaultUser)
        assertEquals(1, ps3.defaultConnections)
        assertTrue(ps3.connectionsLocked)
        assertEquals("/dev_hdd0", ps3.startPath)

        assertTrue(FtpDeviceType.PC_OTHER.defaultConnections > 1)
        assertTrue(!FtpDeviceType.PC_OTHER.connectionsLocked)
    }

    @Test
    fun conectaConLosDatosDeAuroraSinQueElUsuarioLosEscriba() {
        val s = start(user = "xboxftp", pass = "xboxftp")
        val c = FtpClient("127.0.0.1", s.port, "xboxftp", "xboxftp", FtpDeviceType.XBOX360)
        c.connect()
        assertEquals("xboxftp", c.workingCredential?.user)
        assertEquals(3, c.list("/").size)
        c.disconnect()
    }

    @Test
    fun siLosDatosPuestosFallanPruebaLosDeLaConsola() {
        // El servidor solo acepta los de FSD, pero el usuario dejó los de Aurora.
        val s = start(user = "xbox", pass = "xbox")
        val c = FtpClient("127.0.0.1", s.port, "xboxftp", "xboxftp", FtpDeviceType.XBOX360)
        c.connect()
        assertEquals("xbox", c.workingCredential?.user)
        assertTrue(c.list("/").any { it.name == "Hdd1" })
        c.disconnect()
    }

    @Test
    fun funcionaAunqueElServidorNoEntiendaCwd() {
        val s = start(refuseCwd = true)
        val c = FtpClient("127.0.0.1", s.port, "xboxftp", "xboxftp", FtpDeviceType.XBOX360)
        c.connect()
        val items = c.list("/")
        assertEquals(3, items.size)
        assertTrue(c.logText.contains("CWD no funcionó"))
        c.disconnect()
    }

    @Test
    fun funcionaConServidoresQueSoloAdmitenEpsv() {
        val s = start(onlyEpsv = true)
        val c = FtpClient("127.0.0.1", s.port, "xboxftp", "xboxftp", FtpDeviceType.XBOX360)
        c.connect()
        assertEquals(3, c.list("/").size)
        assertTrue(c.logText.contains("EPSV"))
        c.disconnect()
    }

    @Test
    fun elRegistroGuardaLoHabladoYNoEnsenaLaContrasena() {
        val s = start()
        val c = FtpClient("127.0.0.1", s.port, "xboxftp", "miclave", FtpDeviceType.XBOX360)
        runCatching { c.connect() }
        val log = c.logText
        assertTrue("Debe registrar la conexión", log.contains("Conectando a 127.0.0.1"))
        assertTrue("Debe registrar los comandos", log.contains("USER"))
        assertTrue("No debe guardar la contraseña", !log.contains("miclave"))
        assertTrue(log.contains("PASS ******"))
        c.disconnect()
    }

    @Test
    fun siNadaFuncionaElMensajeListaLosDatosHabituales() {
        val s = start(user = "otro", pass = "otra")
        val c = FtpClient("127.0.0.1", s.port, "xboxftp", "xboxftp", FtpDeviceType.XBOX360)
        try {
            c.connect()
            fail("Debería haber fallado")
        } catch (e: UserError) {
            val m = e.message!!
            assertTrue(m.contains("xboxftp"))
            assertTrue(m.contains("xbox / xbox"))
            assertTrue(m.contains("Aurora"))
        }
    }

    @Test
    fun servidorApagadoExplicaQueMirarEnLaConsola() {
        val c = FtpClient("127.0.0.1", 1, "xboxftp", "xboxftp", FtpDeviceType.XBOX360, timeoutMs = 1500)
        try {
            c.connect()
            fail("Debería haber fallado")
        } catch (e: UserError) {
            val m = e.message!!
            assertTrue(m.contains("No se pudo conectar"))
            assertTrue("Debe dar pistas de la Xbox", m.contains("Aurora") || m.contains("FTP está encendido"))
            assertTrue(m.contains("misma red"))
        }
    }
}
