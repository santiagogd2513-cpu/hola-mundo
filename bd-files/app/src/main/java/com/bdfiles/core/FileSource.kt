package com.bdfiles.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Error pensado para enseñárselo al usuario tal cual. */
class UserError(message: String) : IOException(message)

/**
 * Un sitio del que leer y en el que escribir archivos: el almacenamiento del móvil,
 * una tarjeta por SAF, rutas del sistema por Shizuku o un servidor FTP.
 */
interface FileSource : Closeable {
    /** Identificador estable, p. ej. "local" o "ftp:Mi Xbox". */
    val id: String

    /** Nombre que se le enseña al usuario. */
    val title: String

    /** Carpeta por la que se empieza. */
    val rootPath: String

    val canWrite: Boolean get() = true

    /**
     * true si está al otro lado de la red. En esos casos recorrer carpetas es lento,
     * así que no se mide el tamaño total antes de empezar.
     */
    val isRemote: Boolean get() = false

    fun list(path: String): List<FileItem>

    fun stat(path: String): FileItem?

    fun openRead(path: String): InputStream

    /** Abre para escribir, creando o vaciando el archivo. [size] ayuda a algunos orígenes (FTP). */
    fun openWrite(path: String, size: Long = -1): OutputStream

    fun mkdirs(path: String)

    fun createFile(path: String)

    /** Borra; si es carpeta, con todo lo que tenga dentro. */
    fun delete(path: String)

    fun rename(path: String, newName: String)

    /** Mover dentro del mismo origen. Devuelve false si hay que copiar y borrar. */
    fun moveWithin(from: String, to: String): Boolean = false

    /** Espacio libre en bytes, o -1 si no se sabe. */
    fun freeSpace(): Long = -1

    /** Espacio total en bytes, o -1 si no se sabe. */
    fun totalSpace(): Long = -1

    override fun close() {}
}

/** Une una carpeta con un nombre usando "/" (sin barras repetidas). */
fun joinPath(parent: String, name: String): String = when {
    parent.isEmpty() || parent == "/" -> "/$name"
    parent.endsWith("/") -> "$parent$name"
    else -> "$parent/$name"
}

fun parentOf(path: String): String? {
    if (path == "/" || path.isEmpty()) return null
    val p = path.trimEnd('/').substringBeforeLast('/', "")
    return if (p.isEmpty()) "/" else p
}

fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

/** Busca un nombre libre: "foto.jpg" -> "foto (2).jpg". */
fun uniqueName(existing: Set<String>, name: String): String {
    if (name !in existing) return name
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "")
    var i = 2
    while (true) {
        val candidate = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
        if (candidate !in existing) return candidate
        i++
    }
}
