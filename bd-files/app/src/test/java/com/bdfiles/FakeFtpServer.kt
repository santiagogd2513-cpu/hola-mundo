package com.bdfiles

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Servidor FTP mínimo para las pruebas: habla lo justo del protocolo (RFC 959)
 * para poder comprobar que nuestro cliente hace bien su trabajo.
 *
 * [dosStyle] cambia el formato del listado a estilo MS-DOS, para probar que el
 * cliente entiende los dos formatos.
 */
class FakeFtpServer(
    val root: File,
    private val dosStyle: Boolean = false,
    private val requirePassword: Boolean = true,
    /** Usuario y contraseña que acepta. */
    private val acceptUser: String = "usuario",
    private val acceptPass: String = "secreto",
    /** Simula un servidor que no entiende CWD (hay consolas así). */
    private val refuseCwd: Boolean = false,
    /** Simula un servidor que solo admite EPSV, no PASV. */
    private val onlyEpsv: Boolean = false,
) {
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var running = true
    val commandLog = java.util.Collections.synchronizedList(ArrayList<String>())

    fun start() {
        thread(isDaemon = true) {
            while (running) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { runCatching { Session(s).run() } }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
    }

    private inner class Session(private val socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        private val out = socket.getOutputStream()
        private var cwd = "/"
        private var lastUser = ""
        private var authed = !requirePassword
        private var dataServer: ServerSocket? = null
        private var renameFrom: String? = null

        private fun reply(text: String) {
            out.write((text + "\r\n").toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }

        private fun resolve(p: String): File {
            val path = if (p.startsWith("/")) p else "$cwd/$p"
            val clean = path.replace("//", "/").trimEnd('/').ifEmpty { "/" }
            return File(root, clean.removePrefix("/"))
        }

        fun run() {
            reply("220 Servidor de pruebas listo")
            while (true) {
                val line = reader.readLine() ?: break
                commandLog.add(line)
                val cmd = line.substringBefore(' ').uppercase(Locale.US)
                val arg = line.substringAfter(' ', "").trim()
                when (cmd) {
                    "USER" -> {
                        lastUser = arg
                        reply(if (requirePassword) "331 Falta la contraseña" else "230 Adelante")
                    }
                    "PASS" -> {
                        authed = lastUser == acceptUser && arg == acceptPass
                        reply(if (authed) "230 Sesión iniciada" else "530 Usuario o contraseña incorrectos")
                    }
                    "SYST" -> reply("215 UNIX Type: L8")
                    "TYPE" -> reply("200 Tipo cambiado")
                    "OPTS" -> reply("200 Opción aceptada")
                    "NOOP" -> reply("200 Aquí sigo")
                    "PWD" -> reply("257 \"$cwd\" es la carpeta actual")
                    "CWD" -> {
                        if (refuseCwd) { reply("502 Orden no admitida"); continue }
                        val f = resolve(arg)
                        if (f.isDirectory) {
                            cwd = if (arg.startsWith("/")) arg.trimEnd('/').ifEmpty { "/" } else "$cwd/$arg"
                            reply("250 Carpeta cambiada")
                        } else {
                            reply("550 No existe la carpeta")
                        }
                    }
                    "EPSV" -> {
                        dataServer?.close()
                        val ds = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                        dataServer = ds
                        reply("229 Entering Extended Passive Mode (|||${ds.localPort}|)")
                    }
                    "PASV" -> {
                        if (onlyEpsv) { reply("502 Usa EPSV"); continue }
                        dataServer?.close()
                        val ds = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                        dataServer = ds
                        val p = ds.localPort
                        reply("227 Entering Passive Mode (127,0,0,1,${p shr 8},${p and 0xFF})")
                    }
                    "LIST" -> {
                        if (!authed) { reply("530 Inicia sesión primero"); continue }
                        val cleanArg = arg.removePrefix("-la").removePrefix("-a").trim()
                        val dir = resolve(cleanArg.ifEmpty { "." })
                        reply("150 Abriendo conexión de datos")
                        withData { s ->
                            val sb = StringBuilder()
                            dir.listFiles()?.sortedBy { it.name }?.forEach { sb.append(format(it)).append("\r\n") }
                            s.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                        }
                        reply("226 Listado enviado")
                    }
                    "RETR" -> {
                        val f = resolve(arg)
                        if (!f.isFile) { reply("550 No existe"); continue }
                        reply("150 Enviando archivo")
                        withData { s -> f.inputStream().use { it.copyTo(s.getOutputStream()) } }
                        reply("226 Archivo enviado")
                    }
                    "STOR" -> {
                        val f = resolve(arg)
                        f.parentFile?.mkdirs()
                        reply("150 Recibiendo archivo")
                        withData { s -> f.outputStream().use { o -> s.getInputStream().copyTo(o) } }
                        reply("226 Archivo recibido")
                    }
                    "SIZE" -> {
                        val f = resolve(arg)
                        reply(if (f.isFile) "213 ${f.length()}" else "550 No existe")
                    }
                    "MKD" -> {
                        val f = resolve(arg)
                        reply(if (f.mkdirs() || f.isDirectory) "257 Carpeta creada" else "550 No se pudo crear")
                    }
                    "DELE" -> {
                        val f = resolve(arg)
                        reply(if (f.isFile && f.delete()) "250 Borrado" else "550 No se pudo borrar")
                    }
                    "RMD" -> {
                        val f = resolve(arg)
                        reply(if (f.isDirectory && f.delete()) "250 Carpeta borrada" else "550 No se pudo borrar")
                    }
                    "RNFR" -> {
                        val f = resolve(arg)
                        if (f.exists()) { renameFrom = arg; reply("350 Listo para renombrar") }
                        else reply("550 No existe")
                    }
                    "RNTO" -> {
                        val from = renameFrom?.let { resolve(it) }
                        renameFrom = null
                        reply(if (from != null && from.renameTo(resolve(arg))) "250 Renombrado" else "550 Falló")
                    }
                    "QUIT" -> { reply("221 Adiós"); socket.close(); return }
                    else -> reply("502 Orden no reconocida")
                }
            }
            runCatching { socket.close() }
        }

        private fun withData(block: (Socket) -> Unit) {
            val ds = dataServer ?: return
            ds.use { server ->
                server.accept().use { s -> block(s) }
            }
            dataServer = null
        }

        private fun format(f: File): String {
            val size = if (f.isDirectory) 4096 else f.length()
            return if (dosStyle) {
                val d = SimpleDateFormat("MM-dd-yy  hh:mma", Locale.US).format(java.util.Date(f.lastModified()))
                val kind = if (f.isDirectory) "<DIR>         " else size.toString().padStart(14)
                "$d $kind ${f.name}"
            } else {
                val perms = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                val d = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(java.util.Date(f.lastModified()))
                "$perms   1 owner group ${size.toString().padStart(12)} $d ${f.name}"
            }
        }
    }
}
