package com.libertyv.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.libertyv.core.ConsolePlatform
import com.libertyv.core.io.DataSource
import com.libertyv.core.io.FileChannelSource
import com.libertyv.core.rpf.ExportTarget
import com.libertyv.core.rpf.ResourceExportMode
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/** Error pensado para mostrarse tal cual al usuario. */
class UserMessage(msg: String) : Exception(msg)

/** Guarda las claves AES en el almacenamiento privado de la app. */
class KeyStore(context: Context) {
    private val dir = File(context.filesDir, "keys").apply { mkdirs() }

    private fun file(p: ConsolePlatform) = File(dir, p.keyFileName)

    fun loadAll(): Map<ConsolePlatform, ByteArray> =
        ConsolePlatform.entries.mapNotNull { p ->
            val f = file(p)
            if (f.isFile) f.readBytes().takeIf { p.isValidKey(it) }?.let { p to it } else null
        }.toMap()

    /** Guarda la clave y devuelve su plataforma. */
    fun save(key: ByteArray): ConsolePlatform {
        val p = ConsolePlatform.forKey(key)
            ?: throw UserMessage(
                "Ese archivo no es una clave válida de GTA V (${key.size} bytes). " +
                    "Debe ser key_xbox360.dat o key_ps3.dat (32 bytes).",
            )
        file(p).writeBytes(key)
        return p
    }

    fun delete(p: ConsolePlatform) {
        file(p).delete()
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var resourceMode: ResourceExportMode
        get() = runCatching { ResourceExportMode.valueOf(prefs.getString("resourceMode", null) ?: "") }
            .getOrDefault(ResourceExportMode.RSC7)
        set(v) = prefs.edit().putString("resourceMode", v.name).apply()
}

fun ContentResolver.displayName(uri: Uri): String {
    runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { return it }
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
}

/** Abre un documento elegido por el usuario con acceso aleatorio. */
fun ContentResolver.openDataSource(uri: Uri): DataSource {
    val pfd = openFileDescriptor(uri, "r") ?: throw IOException("No se pudo abrir el archivo")
    val fis = FileInputStream(pfd.fileDescriptor)
    return FileChannelSource(fis.channel, Closeable {
        fis.close()
        pfd.close()
    })
}

fun fileDataSource(file: File): DataSource {
    val raf = RandomAccessFile(file, "r")
    return FileChannelSource(raf.channel, raf)
}

/** Exporta dentro de una carpeta elegida con el selector de Android (SAF). */
class DocumentTreeTarget(
    private val context: Context,
    treeUri: Uri,
) : ExportTarget {
    private val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Carpeta no válida")
    private val dirs = HashMap<String, DocumentFile>()
    private val createdByUs = HashSet<String>()

    private fun dir(path: String): DocumentFile {
        if (path.isEmpty()) return root
        dirs[path]?.let { return it }
        val parentPath = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val parent = dir(parentPath)
        val existing = if (parentPath.isNotEmpty() && parentPath in createdByUs) null else parent.findFile(name)
        val d = if (existing != null && existing.isDirectory) {
            existing
        } else {
            createdByUs.add(path)
            parent.createDirectory(name) ?: throw IOException("No se pudo crear la carpeta $name")
        }
        dirs[path] = d
        return d
    }

    override fun create(relativePath: String): OutputStream {
        val parentPath = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val parent = dir(parentPath)
        if (parentPath !in createdByUs) parent.findFile(name)?.delete()
        val f = parent.createFile("application/octet-stream", name)
            ?: throw IOException("No se pudo crear $name")
        return context.contentResolver.openOutputStream(f.uri, "wt")
            ?: throw IOException("No se pudo escribir $name")
    }
}
