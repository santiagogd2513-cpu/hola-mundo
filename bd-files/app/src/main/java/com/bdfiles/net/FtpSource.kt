package com.bdfiles.net

import com.bdfiles.core.FileItem
import com.bdfiles.core.FileSource
import com.bdfiles.core.UserError
import com.bdfiles.core.joinPath
import com.bdfiles.core.parentOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Datos guardados de un servidor FTP. */
data class FtpServerConfig(
    val name: String,
    val host: String,
    val port: Int = 21,
    val user: String = "anonymous",
    val password: String = "",
    val deviceType: FtpDeviceType = FtpDeviceType.PC_OTHER,
    val passive: Boolean = deviceType.defaultPassive,
    val startPath: String = deviceType.startPath,
) {
    val id: String get() = "ftp:$name"
}

/** Un servidor FTP visto como si fuera una carpeta más. */
class FtpSource(val config: FtpServerConfig) : FileSource {
    override val id: String get() = config.id
    override val title: String get() = config.name
    override val rootPath: String get() = config.startPath

    private val client = FtpClient(
        host = config.host,
        port = config.port,
        user = config.user,
        password = config.password,
        deviceType = config.deviceType,
        passive = config.passive,
    )

    /** Se recuerda el último listado para saber si algo es carpeta sin volver a preguntar. */
    private val dirCache = HashMap<String, Boolean>()

    fun testConnection(): String {
        client.connect()
        val here = client.pwd()
        val n = client.list(config.startPath).size
        return "Conectado a ${config.host}.\nCarpeta actual: $here\n$n elementos en ${config.startPath}"
    }

    override fun list(path: String): List<FileItem> = try {
        client.list(path).map { e ->
            val full = joinPath(path, e.name)
            dirCache[full] = e.isDir
            FileItem(
                name = e.name,
                path = full,
                isDir = e.isDir,
                size = e.size,
                lastModified = e.modified,
                sourceId = id,
            )
        }
    } catch (e: Exception) {
        throw ftpError(e)
    }

    override fun stat(path: String): FileItem? = try {
        val parent = parentOf(path) ?: return null
        list(parent).firstOrNull { it.path == path }
    } catch (_: Exception) {
        null
    }

    override fun openRead(path: String): InputStream = try {
        client.openReadStream(path)
    } catch (e: Exception) {
        throw ftpError(e)
    }

    /**
     * FTP no deja escribir "poco a poco" de forma sencilla, así que se guarda
     * en memoria y se sube entero al cerrar. Para archivos muy grandes conviene
     * usar copiar/mover, que sí sube directamente desde disco.
     */
    override fun openWrite(path: String, size: Long): OutputStream = object : ByteArrayOutputStream() {
        override fun close() {
            super.close()
            try {
                client.upload(path, ByteArrayInputStream(toByteArray()))
            } catch (e: Exception) {
                throw ftpError(e)
            }
        }
    }

    /** Sube directamente desde un flujo, sin pasar por memoria. */
    fun uploadFrom(path: String, input: InputStream, onProgress: (Long) -> Unit) = try {
        client.upload(path, input, onProgress)
    } catch (e: Exception) {
        throw ftpError(e)
    }

    fun downloadTo(path: String, out: OutputStream, onProgress: (Long) -> Unit) = try {
        client.download(path, out, onProgress)
    } catch (e: Exception) {
        throw ftpError(e)
    }

    override fun mkdirs(path: String) {
        try {
            // Crea una por una las carpetas que falten.
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
            var cur = ""
            for (p in parts) {
                cur = "$cur/$p"
                runCatching { client.mkdir(cur) }
            }
        } catch (e: Exception) {
            throw ftpError(e)
        }
    }

    override fun createFile(path: String) {
        try {
            client.upload(path, ByteArrayInputStream(ByteArray(0)))
        } catch (e: Exception) {
            throw ftpError(e)
        }
    }

    override fun delete(path: String) {
        try {
            val isDir = dirCache[path] ?: (stat(path)?.isDir ?: false)
            if (isDir) {
                for (child in list(path)) delete(child.path)
                client.deleteDir(path)
            } else {
                client.deleteFile(path)
            }
        } catch (e: Exception) {
            throw ftpError(e)
        }
    }

    override fun rename(path: String, newName: String) {
        try {
            val parent = parentOf(path) ?: throw UserError("No se puede renombrar la raíz")
            client.rename(path, joinPath(parent, newName))
        } catch (e: Exception) {
            throw ftpError(e)
        }
    }

    override fun moveWithin(from: String, to: String): Boolean = try {
        client.rename(from, to)
        true
    } catch (_: Exception) {
        false
    }

    fun keepAlive() = client.keepAlive()

    override fun close() = client.disconnect()
}
