package com.bdfiles.net

import com.bdfiles.core.OpProgress
import com.bdfiles.core.UserError
import com.bdfiles.core.formatSize
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** Descarga de un enlace directo (http/https), con progreso. */
object Downloader {

    class Info(val fileName: String, val size: Long)

    /** Saca el nombre del archivo de la cabecera o del final de la dirección. */
    private fun fileNameFrom(conn: HttpURLConnection, url: URL): String {
        conn.getHeaderField("Content-Disposition")?.let { cd ->
            Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)").find(cd)?.groupValues?.get(1)?.let {
                return runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it).trim()
            }
        }
        val fromPath = url.path.substringAfterLast('/')
        return if (fromPath.isNotBlank()) {
            runCatching { URLDecoder.decode(fromPath, "UTF-8") }.getOrDefault(fromPath)
        } else {
            "descarga"
        }
    }

    private fun open(urlText: String): Pair<HttpURLConnection, URL> {
        var url = URL(urlText)
        var redirects = 0
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            // Algunos servidores rechazan peticiones sin navegador declarado.
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BDFiles")
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location == null || ++redirects > 8) throw UserError("Demasiados redireccionamientos")
                url = URL(url, location)
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw UserError(
                    when (code) {
                        401, 403 -> "El servidor no deja descargar este archivo sin iniciar sesión (error $code)."
                        404 -> "El enlace no existe o ha caducado (error 404)."
                        else -> "El servidor respondió con el error $code."
                    },
                )
            }
            return conn to url
        }
    }

    /**
     * Descarga [urlText] dentro de [destDir]. Devuelve el archivo creado.
     * Si la respuesta es una página web en vez de un archivo, avisa en vez de
     * guardar el HTML.
     */
    fun download(
        urlText: String,
        destDir: File,
        report: (OpProgress) -> Unit,
        isCancelled: () -> Boolean,
        suggestedName: String? = null,
    ): File {
        val clean = urlText.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            throw UserError("El enlace tiene que empezar por http:// o https://")
        }
        val (conn, finalUrl) = open(clean)
        try {
            val type = conn.contentType.orEmpty()
            if (type.startsWith("text/html")) {
                throw UserError(
                    "Ese enlace lleva a una página web, no a un archivo.\n\n" +
                        "En MediaFire o Mega hay que entrar en la página y copiar el enlace de descarga " +
                        "directa (el que acaba en el nombre del archivo).",
                )
            }
            val total = conn.contentLengthLong
            val name = suggestedName?.takeIf { it.isNotBlank() } ?: fileNameFrom(conn, finalUrl)
            destDir.mkdirs()
            var target = File(destDir, name)
            var i = 2
            while (target.exists()) {
                val base = name.substringBeforeLast('.', name)
                val ext = name.substringAfterLast('.', "")
                target = File(destDir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
                i++
            }

            val start = System.currentTimeMillis()
            conn.inputStream.use { input ->
                BufferedOutputStream(target.outputStream(), 256 * 1024).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) {
                            target.delete()
                            throw com.bdfiles.core.CancelledException()
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val elapsed = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(1)
                        val speed = done.toDouble() / elapsed
                        report(
                            OpProgress(
                                action = "Descargando",
                                currentName = name,
                                doneBytes = done,
                                totalBytes = if (total > 0) total else 0,
                                doneFiles = 0,
                                totalFiles = 1,
                                bytesPerSecond = speed,
                                elapsedSeconds = elapsed,
                                remainingSeconds = if (total > 0 && speed > 1) ((total - done) / speed).toLong() else -1,
                                fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null,
                                indeterminate = total <= 0,
                            ),
                        )
                    }
                }
            }
            return target
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    fun describe(file: File): String = "${file.name} (${formatSize(file.length())})"
}
