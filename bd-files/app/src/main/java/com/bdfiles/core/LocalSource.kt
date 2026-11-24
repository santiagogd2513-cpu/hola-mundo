package com.bdfiles.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Almacenamiento normal del móvil (y tarjetas montadas a las que se pueda llegar por ruta). */
open class LocalSource(
    override val id: String,
    override val title: String,
    override val rootPath: String,
) : FileSource {

    private fun f(path: String) = File(path)

    override fun list(path: String): List<FileItem> {
        val dir = f(path)
        if (!dir.exists()) throw UserError("La carpeta ya no existe:\n$path")
        if (!dir.isDirectory) throw UserError("“${dir.name}” no es una carpeta")
        val kids = dir.listFiles()
            ?: throw UserError(
                "No se puede leer “${dir.name}”.\n\n" +
                    "Puede que Android no deje entrar ahí. Prueba a dar acceso con SAF " +
                    "(menú ⋮ → Permisos de carpetas) o activa Shizuku.",
            )
        return kids.map { it.toItem() }
    }

    private fun File.toItem() = FileItem(
        name = name,
        path = absolutePath,
        isDir = isDirectory,
        size = if (isDirectory) 0 else length(),
        lastModified = lastModified(),
        readOnly = !canWrite(),
        sourceId = id,
    )

    override fun stat(path: String): FileItem? = f(path).takeIf { it.exists() }?.toItem()

    override fun openRead(path: String): InputStream = FileInputStream(f(path))

    override fun openWrite(path: String, size: Long): OutputStream {
        val file = f(path)
        file.parentFile?.mkdirs()
        return FileOutputStream(file)
    }

    override fun mkdirs(path: String) {
        val file = f(path)
        if (file.exists()) {
            if (!file.isDirectory) throw UserError("Ya existe un archivo llamado “${file.name}”")
            return
        }
        if (!file.mkdirs()) throw UserError("No se pudo crear la carpeta “${file.name}”")
    }

    override fun createFile(path: String) {
        val file = f(path)
        if (file.exists()) throw UserError("Ya existe “${file.name}”")
        file.parentFile?.mkdirs()
        if (!file.createNewFile()) throw UserError("No se pudo crear “${file.name}”")
    }

    override fun delete(path: String) {
        val file = f(path)
        if (!file.exists()) return
        if (!file.deleteRecursively()) {
            throw UserError("No se pudo borrar “${file.name}” del todo (puede estar protegido)")
        }
    }

    override fun rename(path: String, newName: String) {
        val file = f(path)
        val target = File(file.parentFile, newName)
        if (target.exists()) throw UserError("Ya existe “$newName” en esta carpeta")
        if (!file.renameTo(target)) throw UserError("No se pudo renombrar “${file.name}”")
    }

    override fun moveWithin(from: String, to: String): Boolean {
        val src = f(from)
        val dst = f(to)
        if (dst.exists()) return false
        dst.parentFile?.mkdirs()
        return src.renameTo(dst)
    }

    override fun freeSpace(): Long = runCatching { File(rootPath).usableSpace }.getOrDefault(-1)

    override fun totalSpace(): Long = runCatching { File(rootPath).totalSpace }.getOrDefault(-1)
}
