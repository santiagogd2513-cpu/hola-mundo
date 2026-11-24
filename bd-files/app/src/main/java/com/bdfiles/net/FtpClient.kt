package com.bdfiles.net

import com.bdfiles.core.UserError
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Tipo de aparato al otro lado: cambia valores por defecto y pequeñas manías del servidor. */
enum class FtpDeviceType(val label: String, val hint: String) {
    XBOX360(
        "Xbox 360",
        "Para FSD, Aurora, XeXMenu o DashLaunch. Usuario y contraseña «xbox», " +
            "espera más larga y sin UTF-8 (muchos servidores de Xbox no lo entienden).",
    ),
    PC_OTHER(
        "PC u otro",
        "Servidor FTP normal: UTF-8 activado y tiempos de espera cortos.",
    ),
    PS3(
        "PlayStation 3",
        "Para multiMAN o webMAN. Como la Xbox, pero con las rutas de la PS3 (/dev_hdd0).",
    ),
    ROUTER_NAS(
        "Router o NAS",
        "Equipos que a veces solo admiten modo activo. Si el listado se queda colgado, " +
            "desactiva el modo pasivo.",
    );

    val defaultUser: String get() = if (this == XBOX360) "xbox" else "anonymous"
    val defaultPass: String get() = if (this == XBOX360) "xbox" else "bdfiles@local"
    val utf8: Boolean get() = this == PC_OTHER
    val timeoutMs: Int get() = if (this == PC_OTHER) 15_000 else 40_000
    val defaultPassive: Boolean get() = this != ROUTER_NAS

    val startPath: String
        get() = when (this) {
            XBOX360 -> "/"
            PS3 -> "/dev_hdd0"
            else -> "/"
        }
}

class FtpEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
)

/**
 * Cliente FTP escrito desde cero (RFC 959), pensado también para consolas.
 * No usa ninguna librería de FTP: habla el protocolo directamente por sockets.
 */
class FtpClient(
    private val host: String,
    private val port: Int = 21,
    private val user: String,
    private val password: String,
    private val deviceType: FtpDeviceType = FtpDeviceType.PC_OTHER,
    private val passive: Boolean = deviceType.defaultPassive,
    private val timeoutMs: Int = deviceType.timeoutMs,
) {
    private var control: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null
    private val charset: Charset = if (deviceType.utf8) Charsets.UTF_8 else Charsets.ISO_8859_1

    /** Última respuesta recibida, útil para mensajes de error. */
    var lastReply: String = ""
        private set

    val isConnected: Boolean get() = control?.isConnected == true && control?.isClosed == false

    // ------------------------------------------------------------ conexión

    @Synchronized
    fun connect() {
        if (isConnected) return
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: Exception) {
            throw UserError(
                "No se pudo conectar a $host:$port.\n\n" +
                    "Comprueba que el aparato está encendido, en la misma red y con el FTP abierto.\n" +
                    "(${e.message ?: e.javaClass.simpleName})",
            )
        }
        s.soTimeout = timeoutMs
        s.keepAlive = true
        control = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), charset))
        writer = BufferedOutputStream(s.getOutputStream())

        val hello = readReply()
        if (hello.code !in 200..299) {
            disconnect()
            throw UserError("El servidor rechazó la conexión:\n${hello.text}")
        }

        val userReply = command("USER $user")
        if (userReply.code in 300..399) {
            val passReply = command("PASS $password")
            if (passReply.code !in 200..299) {
                disconnect()
                throw UserError(
                    "Usuario o contraseña incorrectos.\n${passReply.text}\n\n" +
                        if (deviceType == FtpDeviceType.XBOX360) {
                            "En la Xbox suele ser usuario «xbox» y contraseña «xbox»."
                        } else "",
                )
            }
        } else if (userReply.code !in 200..299) {
            disconnect()
            throw UserError("El servidor no aceptó el usuario:\n${userReply.text}")
        }

        if (deviceType.utf8) runCatching { command("OPTS UTF8 ON") }
        command("TYPE I") // binario
    }

    @Synchronized
    fun disconnect() {
        runCatching { if (isConnected) sendLine("QUIT") }
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { control?.close() }
        control = null
        reader = null
        writer = null
    }

    /** Manda un NOOP para que el servidor no corte la conexión por inactividad. */
    fun keepAlive() {
        runCatching { if (isConnected) command("NOOP") }
    }

    // ------------------------------------------------------------ protocolo

    private class Reply(val code: Int, val text: String)

    private fun sendLine(line: String) {
        val w = writer ?: throw UserError("No hay conexión con el servidor FTP")
        w.write((line + "\r\n").toByteArray(charset))
        w.flush()
    }

    private fun readReply(): Reply {
        val r = reader ?: throw UserError("No hay conexión con el servidor FTP")
        val first = r.readLine() ?: throw UserError("El servidor cerró la conexión")
        val sb = StringBuilder(first)
        val code = first.take(3).toIntOrNull() ?: 0
        // Respuesta de varias líneas: "250-texto" ... "250 fin"
        if (first.length > 3 && first[3] == '-') {
            val endPrefix = "${first.take(3)} "
            while (true) {
                val line = r.readLine() ?: break
                sb.append('\n').append(line)
                if (line.startsWith(endPrefix)) break
            }
        }
        lastReply = sb.toString()
        return Reply(code, lastReply)
    }

    @Synchronized
    private fun command(cmd: String): Reply {
        sendLine(cmd)
        return readReply()
    }

    private fun requireOk(cmd: String, vararg okCodes: IntRange): Reply {
        val r = command(cmd)
        if (okCodes.none { r.code in it }) {
            throw UserError("El servidor respondió a «${cmd.substringBefore(' ')}»:\n${r.text}")
        }
        return r
    }

    /** Abre la conexión de datos (pasivo o activo) y devuelve con qué leer o escribir. */
    private fun openData(transferCommand: String): Pair<Socket, ServerSocket?> {
        if (passive) {
            val r = command("PASV")
            if (r.code != 227) throw UserError("El servidor no admite modo pasivo:\n${r.text}")
            val nums = Regex("(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)")
                .find(r.text) ?: throw UserError("No se entendió la respuesta PASV:\n${r.text}")
            val g = nums.groupValues
            val dataPort = (g[5].toInt() shl 8) + g[6].toInt()
            // Algunos servidores (routers, consolas) dan una IP interna que no vale:
            // se usa siempre la IP a la que ya estamos conectados.
            val dataHost = control?.inetAddress?.hostAddress ?: host
            val s = Socket()
            s.connect(InetSocketAddress(dataHost, dataPort), timeoutMs)
            s.soTimeout = timeoutMs
            val reply = command(transferCommand)
            if (reply.code !in 100..199 && reply.code !in 200..299) {
                runCatching { s.close() }
                throw UserError("El servidor rechazó la orden:\n${reply.text}")
            }
            return s to null
        }

        // Modo activo: abrimos nosotros un puerto y el servidor se conecta.
        val server = ServerSocket(0)
        server.soTimeout = timeoutMs
        val local = control?.localAddress?.hostAddress
            ?: throw UserError("No se pudo averiguar la dirección local")
        val ip = local.replace('.', ',')
        val p = server.localPort
        val r = command("PORT $ip,${p shr 8},${p and 0xFF}")
        if (r.code !in 200..299) {
            server.close()
            throw UserError("El servidor no admite modo activo:\n${r.text}\n\nPrueba a activar el modo pasivo.")
        }
        val reply = command(transferCommand)
        if (reply.code !in 100..199 && reply.code !in 200..299) {
            server.close()
            throw UserError("El servidor rechazó la orden:\n${reply.text}")
        }
        val s = server.accept()
        s.soTimeout = timeoutMs
        return s to server
    }

    private fun finishTransfer() {
        val r = readReply()
        if (r.code !in 200..299) throw UserError("La transferencia no terminó bien:\n${r.text}")
    }

    // ------------------------------------------------------------ operaciones

    fun pwd(): String {
        val r = command("PWD")
        return Regex("\"(.*)\"").find(r.text)?.groupValues?.get(1) ?: "/"
    }

    fun list(path: String): List<FtpEntry> {
        connect()
        // Entrar en la carpeta y listar sin argumentos funciona en más servidores
        // (sobre todo consolas) que pasar la ruta a LIST.
        requireOk("CWD ${normalize(path)}", 200..299)
        val (socket, server) = openData("LIST")
        val text = try {
            socket.getInputStream().readBytes().toString(charset)
        } finally {
            runCatching { socket.close() }
            runCatching { server?.close() }
        }
        finishTransfer()
        return parseList(text)
    }

    fun size(path: String): Long {
        connect()
        val r = command("SIZE ${normalize(path)}")
        if (r.code != 213) return -1
        return r.text.drop(3).trim().toLongOrNull() ?: -1
    }

    fun exists(path: String): Boolean {
        connect()
        if (size(path) >= 0) return true
        val saved = runCatching { pwd() }.getOrNull()
        val ok = command("CWD ${normalize(path)}").code in 200..299
        if (saved != null) runCatching { command("CWD $saved") }
        return ok
    }

    /** Descarga un archivo. [onProgress] recibe los bytes que lleva. */
    fun download(path: String, out: OutputStream, onProgress: (Long) -> Unit = {}) {
        connect()
        val (socket, server) = openData("RETR ${normalize(path)}")
        try {
            val input = BufferedInputStream(socket.getInputStream(), BUFFER)
            val buf = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                onProgress(total)
            }
            out.flush()
        } finally {
            runCatching { socket.close() }
            runCatching { server?.close() }
        }
        finishTransfer()
    }

    /** Sube un archivo. */
    fun upload(path: String, input: InputStream, onProgress: (Long) -> Unit = {}) {
        connect()
        val (socket, server) = openData("STOR ${normalize(path)}")
        try {
            val out = BufferedOutputStream(socket.getOutputStream(), BUFFER)
            val buf = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                onProgress(total)
            }
            out.flush()
            runCatching { socket.shutdownOutput() }
        } finally {
            runCatching { socket.close() }
            runCatching { server?.close() }
        }
        finishTransfer()
    }

    /** Flujo de lectura que mantiene abierta la conexión de datos mientras se lee. */
    fun openReadStream(path: String): InputStream {
        connect()
        val (socket, server) = openData("RETR ${normalize(path)}")
        val raw = socket.getInputStream()
        return object : InputStream() {
            private var closed = false
            override fun read(): Int = raw.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = raw.read(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                runCatching { socket.close() }
                runCatching { server?.close() }
                runCatching { finishTransfer() }
            }
        }
    }

    fun mkdir(path: String) {
        connect()
        val r = command("MKD ${normalize(path)}")
        if (r.code !in 200..299 && !r.text.contains("exists", true)) {
            throw UserError("No se pudo crear la carpeta:\n${r.text}")
        }
    }

    fun deleteFile(path: String) {
        connect()
        val r = command("DELE ${normalize(path)}")
        if (r.code !in 200..299) throw UserError("No se pudo borrar el archivo:\n${r.text}")
    }

    fun deleteDir(path: String) {
        connect()
        val r = command("RMD ${normalize(path)}")
        if (r.code !in 200..299) throw UserError("No se pudo borrar la carpeta:\n${r.text}")
    }

    fun rename(from: String, to: String) {
        connect()
        val r1 = command("RNFR ${normalize(from)}")
        if (r1.code !in 300..399) throw UserError("No se pudo renombrar:\n${r1.text}")
        val r2 = command("RNTO ${normalize(to)}")
        if (r2.code !in 200..299) throw UserError("No se pudo renombrar:\n${r2.text}")
    }

    // ------------------------------------------------------------ utilidades

    private fun normalize(path: String): String {
        var p = path.replace('\\', '/')
        if (!p.startsWith("/")) p = "/$p"
        if (p.length > 1) p = p.trimEnd('/')
        return p.ifEmpty { "/" }
    }

    companion object {
        private const val BUFFER = 64 * 1024

        private val MONTHS = listOf(
            "jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec",
        )

        /** Entiende los dos formatos habituales de LIST: estilo UNIX y estilo MS-DOS. */
        fun parseList(text: String): List<FtpEntry> {
            val out = ArrayList<FtpEntry>()
            for (raw in text.split("\r\n", "\n")) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("total ", true)) continue
                val entry = parseUnix(line) ?: parseDos(line) ?: continue
                if (entry.name == "." || entry.name == ".." || entry.name.isEmpty()) continue
                out.add(entry)
            }
            return out
        }

        // drwxr-xr-x   2 user group     4096 Jan  5 10:11 nombre con espacios
        private fun parseUnix(line: String): FtpEntry? {
            if (line.length < 20) return null
            val c = line[0]
            if (c != 'd' && c != '-' && c != 'l') return null
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 8) return null
            val size = parts.getOrNull(4)?.toLongOrNull() ?: 0L
            // La fecha son los campos 5, 6 y 7; el nombre es todo lo que va detrás.
            var idx = 0
            var found = 0
            while (idx < line.length && found < 8) {
                while (idx < line.length && line[idx] == ' ') idx++
                while (idx < line.length && line[idx] != ' ') idx++
                found++
            }
            while (idx < line.length && line[idx] == ' ') idx++
            if (idx >= line.length) return null
            var name = line.substring(idx)
            val isLink = c == 'l'
            if (isLink) name = name.substringBefore(" -> ")
            val time = parseUnixDate(parts.getOrNull(5), parts.getOrNull(6), parts.getOrNull(7))
            // Los enlaces se tratan como carpetas: en consolas casi siempre lo son.
            return FtpEntry(name, c == 'd' || isLink, if (c == 'd') 0 else size, time)
        }

        private fun parseUnixDate(month: String?, day: String?, yearOrTime: String?): Long {
            if (month == null || day == null || yearOrTime == null) return 0
            val m = MONTHS.indexOf(month.lowercase(Locale.US).take(3))
            if (m < 0) return 0
            val d = day.toIntOrNull() ?: return 0
            val cal = Calendar.getInstance()
            val now = cal.get(Calendar.YEAR)
            cal.set(Calendar.MILLISECOND, 0)
            cal.set(Calendar.SECOND, 0)
            if (yearOrTime.contains(':')) {
                val hm = yearOrTime.split(':')
                cal.set(now, m, d, hm[0].toIntOrNull() ?: 0, hm.getOrNull(1)?.toIntOrNull() ?: 0)
                // Si la fecha sale en el futuro, es del año pasado.
                if (cal.timeInMillis > System.currentTimeMillis() + 86_400_000L) {
                    cal.set(Calendar.YEAR, now - 1)
                }
            } else {
                cal.set(yearOrTime.toIntOrNull() ?: now, m, d, 0, 0)
            }
            return cal.timeInMillis
        }

        // 01-05-24  10:11AM       <DIR>          nombre
        private fun parseDos(line: String): FtpEntry? {
            val m = Regex(
                "^(\\d{2}-\\d{2}-\\d{2,4})\\s+(\\d{2}:\\d{2}(?:[AaPp][Mm])?)\\s+(<DIR>|\\d+)\\s+(.+)$",
            ).find(line) ?: return null
            val (date, time, sizeOrDir, name) = m.destructured
            val isDir = sizeOrDir.equals("<DIR>", true)
            val size = if (isDir) 0L else sizeOrDir.toLongOrNull() ?: 0L
            val millis = runCatching {
                val pattern = if (time.length > 5) "MM-dd-yy hh:mma" else "MM-dd-yy HH:mm"
                SimpleDateFormat(pattern, Locale.US).parse("$date $time")?.time ?: 0L
            }.getOrDefault(0L)
            return FtpEntry(name.trim(), isDir, size, millis)
        }
    }
}

/** Error de red con un mensaje claro. */
fun ftpError(e: Throwable): UserError = when (e) {
    is UserError -> e
    is IOException -> UserError("Problema de red: ${e.message ?: "la conexión se cortó"}")
    else -> UserError(e.message ?: e.javaClass.simpleName)
}
