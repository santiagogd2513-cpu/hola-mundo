package com.bdfiles.core

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * Puente con Shizuku: ejecuta órdenes del sistema con permisos elevados, sin root.
 * Sirve para dos cosas:
 *  - Dar permisos a otras apps (pm grant / appops), que es lo que necesitan los emuladores.
 *  - Leer y escribir en carpetas donde Android normalmente no deja entrar.
 */
object ShizukuBridge {

    const val REQUEST_CODE = 4242

    class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
        val text: String get() = (out + "\n" + err).trim()
    }

    /** ¿Está instalado y en marcha el servicio de Shizuku? */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** ¿Nos ha dado permiso el usuario? */
    fun hasPermission(): Boolean = runCatching {
        if (!isRunning()) false
        else if (Shizuku.isPreV11()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    val isReady: Boolean get() = isRunning() && hasPermission()

    /** Versión de Android del servidor de Shizuku, para avisos. */
    fun serverVersion(): Int = runCatching { Shizuku.getVersion() }.getOrDefault(-1)

    /**
     * Lanza un proceso con los permisos de Shizuku.
     * `Shizuku.newProcess` es de uso interno, así que se llama por reflexión.
     */
    private fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }

    /** Ejecuta una orden de shell y espera el resultado. */
    fun run(command: String, timeoutMs: Long = 20_000): Result {
        if (!isReady) throw UserError("Shizuku no está activo. Actívalo en Ajustes → Shizuku.")
        return try {
            val p = newProcess(arrayOf("sh", "-c", command))
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = readerThread(p.inputStream, out)
            val tErr = readerThread(p.errorStream, err)
            val finished = waitFor(p, timeoutMs)
            tOut.join(1000)
            tErr.join(1000)
            if (!finished) {
                runCatching { p.destroy() }
                return Result(-1, out.toString(), "La orden tardó demasiado y se canceló.")
            }
            Result(p.exitValue(), out.toString(), err.toString())
        } catch (e: UserError) {
            throw e
        } catch (e: Exception) {
            throw UserError("Shizuku falló: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun waitFor(p: Process, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                p.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(25)
            }
        }
        return false
    }

    private fun readerThread(stream: InputStream, into: StringBuilder): Thread =
        Thread {
            runCatching {
                BufferedReader(InputStreamReader(stream)).use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        into.append(line).append('\n')
                        line = r.readLine()
                    }
                }
            }
        }.also { it.start() }

    /** Abre un proceso para ir leyendo su salida (p. ej. `cat archivo`). */
    fun openRead(command: String): InputStream {
        if (!isReady) throw UserError("Shizuku no está activo.")
        val p = newProcess(arrayOf("sh", "-c", command))
        return object : InputStream() {
            private val s = p.inputStream
            override fun read(): Int = s.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = s.read(b, off, len)
            override fun close() {
                runCatching { s.close() }
                runCatching { p.destroy() }
            }
        }
    }

    /** Abre un proceso en el que escribir (p. ej. `cat > archivo`). */
    fun openWrite(command: String): OutputStream {
        if (!isReady) throw UserError("Shizuku no está activo.")
        val p = newProcess(arrayOf("sh", "-c", command))
        return object : OutputStream() {
            private val s = p.outputStream
            override fun write(b: Int) = s.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = s.write(b, off, len)
            override fun flush() = s.flush()
            override fun close() {
                runCatching { s.flush() }
                runCatching { s.close() }
                runCatching { waitFor(p, 30_000) }
                runCatching { p.destroy() }
            }
        }
    }

    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
