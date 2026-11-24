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

/** Un juego de usuario y contraseña habitual para un aparato. */
class FtpCredential(val user: String, val password: String, val from: String)

/** Tipo de aparato al otro lado: cambia los valores por defecto y las manías del servidor. */
enum class FtpDeviceType(val label: String, val hint: String) {
    XBOX360(
        "Xbox 360",
        "Se configura para Aurora (usuario y contraseña «xboxftp»). Si usas FSD, XeXMenu o " +
            "DashLaunch, prueba «xbox». Una sola conexión, esperas largas y sin UTF-8: " +
            "los servidores de Xbox no lo entienden.",
    ),
    PC_OTHER(
        "PC u otro",
        "Servidor FTP normal: UTF-8 activado, esperas cortas y varias conexiones a la vez.",
    ),
    PS3(
        "PlayStation 3",
        "Para multiMAN o webMAN: usuario y contraseña «root». Una sola conexión y " +
            "arranca en /dev_hdd0.",
    ),
    ROUTER_NAS(
        "Router o NAS",
        "Equipos que a veces solo admiten modo activo. Si el listado se queda colgado, " +
            "desactiva el modo pasivo.",
    );

    /** Usuarios y contraseñas que se prueban por orden si falla el que has puesto. */
    val knownCredentials: List<FtpCredential>
        get() = when (this) {
            XBOX360 -> listOf(
                FtpCredential("xboxftp", "xboxftp", "Aurora"),
                FtpCredential("xbox", "xbox", "FSD / XeXMenu / DashLaunch"),
                FtpCredential("anonymous", "anonymous", "sin contraseña"),
            )
            PS3 -> listOf(
                FtpCredential("root", "root", "multiMAN / webMAN"),
                FtpCredential("anonymous", "anonymous", "sin contraseña"),
            )
            else -> emptyList()
        }

    val defaultUser: String get() = knownCredentials.firstOrNull()?.user ?: "anonymous"
    val defaultPass: String get() = knownCredentials.firstOrNull()?.password ?: "bdfiles@local"
    val utf8: Boolean get() = this == PC_OTHER
    val timeoutMs: Int get() = if (this == PC_OTHER) 15_000 else 45_000
    val defaultPassive: Boolean get() = this != ROUTER_NAS

    /** Las consolas solo aguantan una conexión a la vez. */
    val defaultConnections: Int get() = if (this == XBOX360 || this == PS3) 1 else 2

    /** true si el número de conexiones no se debe poder cambiar. */
    val connectionsLocked: Boolean get() = this == XBOX360 || this == PS3

    val startPath: String
        get() = when (this) {
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
 *
 * Guarda un registro de todo lo que manda y recibe, para poder enseñárselo
 * al usuario cuando algo falla.
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

    /** Todo lo hablado con el servidor, para enseñarlo si hay problemas. */
    private val logLines = java.util.Collections.synchronizedList(ArrayList<String>())

    /** Usuario y contraseña con los que se logró entrar. */
    var workingCredential: FtpCredential? = null
        private set

    val log: List<String> get() = ArrayList(logLines)

    val logText: String get() = log.joinToString("\n")

    private fun note(line: String) {
        if (logLines.size > 400) logLines.removeAt(0)
        logLines.add(line)
    }

    /** Última respuesta recibida, útil para mensajes de error. */
    var lastReply: String = ""
        private set

    val isConnected: Boolean get() = control?.isConnected == true && control?.isClosed == false

    // ------------------------------------------------------------ conexión

    @Synchronized
    fun connect() {
        if (isConnected) return
        logLines.clear()
        note("→ Conectando a $host:$port (espera máxima ${timeoutMs / 1000} s)")

        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: Exception) {
            note("✗ No se pudo abrir la conexión: ${e.javaClass.simpleName}: ${e.message}")
            throw UserError(
                "No se pudo conectar a $host:$port.\n\n" +
                    connectionAdvice() +
                    "\n\nDetalle: ${e.message ?: e.javaClass.simpleName}",
            )
        }
        s.soTimeout = timeoutMs
        s.keepAlive = true
        s.tcpNoDelay = true
        control = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), charset))
        writer = BufferedOutputStream(s.getOutputStream())
        note("✓ Conexión abierta")

        val hello = try {
            readReply()
        } catch (e: Exception) {
            disconnect()
            throw UserError(
                "El aparato aceptó la conexión pero no saludó.\n\n" +
                    "Suele pasar cuando el puerto $port no es de FTP, o cuando el servidor del " +
                    "aparato está colgado. Prueba a reiniciar el FTP en la consola.",
            )
        }
        if (hello.code !in 200..299) {
            disconnect()
            throw UserError("El servidor rechazó la conexión:\n${hello.text}")
        }

        // Se prueba lo que haya puesto el usuario y, si no, los datos típicos del aparato.
        val attempts = ArrayList<FtpCredential>()
        attempts.add(FtpCredential(user, password, "los que pusiste"))
        for (c in deviceType.knownCredentials) {
            if (attempts.none { it.user == c.user && it.password == c.password }) attempts.add(c)
        }

        var lastError = ""
        for (cred in attempts) {
            if (login(cred)) {
                workingCredential = cred
                note("✓ Dentro con el usuario «${cred.user}» (${cred.from})")
                if (deviceType.utf8) runCatching { command("OPTS UTF8 ON") }
                command("TYPE I")
                return
            }
            lastError = lastReply
            // Algunos servidores cortan tras un fallo: se vuelve a abrir la conexión.
            if (!isConnected && cred !== attempts.last()) {
                note("… el servidor cortó, reabriendo para el siguiente intento")
                reopenControl() ?: break
            }
        }

        disconnect()
        throw UserError(
            "El usuario o la contraseña no son correctos.\n\n" +
                credentialAdvice() +
                "\n\nRespuesta del servidor: ${lastError.ifBlank { "ninguna" }}",
        )
    }

    /** Vuelve a abrir la conexión de control. Devuelve null si no se pudo. */
    private fun reopenControl(): Socket? = runCatching {
        runCatching { control?.close() }
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        control = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), charset))
        writer = BufferedOutputStream(s.getOutputStream())
        readReply()
        s
    }.getOrNull()

    private fun login(cred: FtpCredential): Boolean {
        val userReply = runCatching { command("USER ${cred.user}") }.getOrNull() ?: return false
        if (userReply.code in 200..299) return true
        if (userReply.code !in 300..399) return false
        val passReply = runCatching { command("PASS ${cred.password}") }.getOrNull() ?: return false
        return passReply.code in 200..299
    }

    private fun connectionAdvice(): String = when (deviceType) {
        FtpDeviceType.XBOX360 ->
            "Comprueba en la Xbox:\n" +
                "• Que el FTP está encendido (en Aurora: Ajustes → Red → Servidor FTP).\n" +
                "• Que la IP es la que sale en la consola y no ha cambiado.\n" +
                "• Que la Xbox y el móvil están en la misma red wifi.\n" +
                "• Que el móvil no está usando datos móviles en vez del wifi."
        FtpDeviceType.PS3 ->
            "Comprueba en la PS3 que multiMAN o webMAN tiene el FTP encendido y que " +
                "la consola y el móvil están en la misma red."
        else ->
            "Comprueba que el aparato está encendido, que la dirección y el puerto son " +
                "correctos y que los dos están en la misma red."
    }

    private fun credentialAdvice(): String {
        val list = deviceType.knownCredentials
        if (list.isEmpty()) return "Revisa el usuario y la contraseña del servidor."
        return "Probé estos, y ninguno valió:\n" +
            list.joinToString("\n") { "• ${it.user} / ${it.password}  (${it.from})" } +
            "\n\nMira en la consola qué usuario tiene puesto el servidor FTP."
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
        val shown = if (line.startsWith("PASS ")) "PASS ******" else line
        note("→ $shown")
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
        note("← $lastReply")
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

    /**
     * Abre la conexión de datos. Prueba, por orden: pasivo (PASV), pasivo moderno
     * (EPSV) y, si nada funciona, modo activo.
     */
    private fun openData(transferCommand: String): Pair<Socket, ServerSocket?> {
        val problems = ArrayList<String>()

        if (passive) {
            passiveSocket("PASV", problems)?.let { return startTransfer(it, null, transferCommand) }
            passiveSocket("EPSV", problems)?.let { return startTransfer(it, null, transferCommand) }
        }
        activeSocket(problems)?.let { (sock, server) -> return startTransfer(sock, server, transferCommand) }

        throw UserError(
            "No se pudo abrir la conexión de datos con el servidor.\n\n" +
                problems.joinToString("\n") { "• $it" } +
                "\n\nPrueba a cambiar el modo pasivo en los ajustes del servidor.",
        )
    }

    private fun passiveSocket(mode: String, problems: ArrayList<String>): Socket? {
        val r = runCatching { command(mode) }.getOrElse {
            problems.add("$mode: ${it.message}")
            return null
        }
        val dataPort: Int = when {
            mode == "PASV" && r.code == 227 -> {
                val m = Regex("(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)")
                    .find(r.text)
                if (m == null) {
                    problems.add("PASV: no se entendió la respuesta")
                    return null
                }
                (m.groupValues[5].toInt() shl 8) + m.groupValues[6].toInt()
            }
            mode == "EPSV" && r.code == 229 -> {
                val m = Regex("\\((.)\\1\\1(\\d+)\\1\\)").find(r.text)
                if (m == null) {
                    problems.add("EPSV: no se entendió la respuesta")
                    return null
                }
                m.groupValues[2].toInt()
            }
            else -> {
                problems.add("$mode: ${r.text.take(80)}")
                return null
            }
        }
        // Algunos equipos dan una IP interna que no vale: se usa la que ya tenemos.
        val dataHost = control?.inetAddress?.hostAddress ?: host
        return runCatching {
            val sock = Socket()
            sock.connect(InetSocketAddress(dataHost, dataPort), timeoutMs)
            sock.soTimeout = timeoutMs
            note("✓ Datos por $mode en $dataHost:$dataPort")
            sock
        }.getOrElse {
            problems.add("$mode: no se pudo conectar al puerto $dataPort (${it.message})")
            null
        }
    }

    private fun activeSocket(problems: ArrayList<String>): Pair<Socket, ServerSocket>? {
        val server = runCatching { ServerSocket(0) }.getOrElse {
            problems.add("Activo: no se pudo abrir un puerto local")
            return null
        }
        server.soTimeout = timeoutMs
        val local = control?.localAddress?.hostAddress
        if (local == null) {
            server.close()
            problems.add("Activo: no se sabe la dirección local")
            return null
        }
        val p = server.localPort
        val r = runCatching { command("PORT ${local.replace('.', ',')},${p shr 8},${p and 0xFF}") }.getOrNull()
        if (r == null || r.code !in 200..299) {
            server.close()
            problems.add("Activo: el servidor rechazó PORT (${r?.text?.take(60) ?: "sin respuesta"})")
            return null
        }
        note("✓ Esperando conexión de datos en modo activo, puerto $p")
        return Socket() to server
    }

    /** Manda la orden de transferencia y deja lista la conexión de datos. */
    private fun startTransfer(
        socket: Socket,
        server: ServerSocket?,
        transferCommand: String,
    ): Pair<Socket, ServerSocket?> {
        val reply = runCatching { command(transferCommand) }.getOrElse {
            runCatching { socket.close() }
            runCatching { server?.close() }
            throw UserError("El servidor no respondió a «${transferCommand.substringBefore(' ')}»: ${it.message}")
        }
        if (reply.code !in 100..299) {
            runCatching { socket.close() }
            runCatching { server?.close() }
            throw UserError("El servidor rechazó la orden:\n${reply.text}")
        }
        // En modo activo, el servidor se conecta a nosotros después de la orden.
        if (server != null) {
            val accepted = runCatching { server.accept() }.getOrElse {
                runCatching { server.close() }
                throw UserError("El servidor no llegó a conectarse (modo activo): ${it.message}")
            }
            accepted.soTimeout = timeoutMs
            return accepted to server
        }
        return socket to null
    }

    private fun finishTransfer() {
        val r = readReply()
        if (r.code !in 200..299) throw UserError("La transferencia no terminó bien:\n${r.text}")
    }

    // ------------------------------------------------------------ operaciones

    fun pwd(): String {
        val r = runCatching { command("PWD") }.getOrNull() ?: return "/"
        return Regex("\"(.*)\"").find(r.text)?.groupValues?.get(1) ?: "/"
    }

    /**
     * Lista una carpeta. Prueba primero entrando en ella (CWD + LIST), que es lo que
     * mejor entienden las consolas, y si no, pasándole la ruta a LIST.
     */
    fun list(path: String): List<FtpEntry> {
        connect()
        val target = normalize(path)
        val cwd = runCatching { command("CWD $target") }.getOrNull()
        val inside = cwd != null && cwd.code in 200..299
        if (!inside) note("… CWD no funcionó, se probará LIST con la ruta")

        val text = runCatching {
            readData(if (inside) "LIST" else "LIST $target")
        }.getOrElse { first ->
            // Último intento: algunos servidores solo responden a LIST -la
            runCatching { readData("LIST -la $target") }.getOrElse { throw first }
        }

        val entries = parseList(text)
        if (entries.isEmpty() && text.isNotBlank()) {
            note("⚠ Llegó texto pero no se entendió el formato del listado:")
            note(text.lines().take(3).joinToString("\n"))
        }
        return entries
    }

    private fun readData(command: String): String {
        val (socket, server) = openData(command)
        val text = try {
            socket.getInputStream().readBytes().toString(charset)
        } finally {
            runCatching { socket.close() }
            runCatching { server?.close() }
        }
        finishTransfer()
        return text
    }

    fun size(path: String): Long {
        connect()
        val r = runCatching { command("SIZE ${normalize(path)}") }.getOrNull() ?: return -1
        if (r.code != 213) return -1
        return r.text.drop(3).trim().toLongOrNull() ?: -1
    }

    fun exists(path: String): Boolean {
        connect()
        if (size(path) >= 0) return true
        val saved = runCatching { pwd() }.getOrNull()
        val ok = runCatching { command("CWD ${normalize(path)}").code in 200..299 }.getOrDefault(false)
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
