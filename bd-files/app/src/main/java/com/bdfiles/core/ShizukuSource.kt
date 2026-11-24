package com.bdfiles.core

import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Acceso a cualquier ruta del sistema (/data, /system...) usando Shizuku.
 * Trabaja con órdenes de shell, así que es más lento que el acceso normal,
 * pero llega donde Android no deja.
 */
class ShizukuSource(
    override val id: String = "shizuku",
    override val title: String = "Sistema (Shizuku)",
    override val rootPath: String = "/",
) : FileSource {

    private val q = ShizukuBridge::shellQuote

    override fun list(path: String): List<FileItem> {
        // -A: oculta . y ..  -l: detalle  --full-time: fecha completa
        val r = ShizukuBridge.run("ls -lA --full-time ${q(path)} 2>&1")
        if (!r.ok && r.out.isBlank()) throw UserError("No se pudo leer “$path”:\n${r.text}")
        return parseLs(r.out, path)
    }

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun parseLs(text: String, parent: String): List<FileItem> {
        val out = ArrayList<FileItem>()
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("total ")) continue
            // drwxrwx--- 4 u0_a1 u0_a1 4096 2024-01-02 10:11:12.000000000 +0100 nombre
            val parts = l.split(Regex("\\s+"), limit = 9)
            if (parts.size < 9) continue
            val perms = parts[0]
            if (perms.length < 10) continue
            val size = parts[4].toLongOrNull() ?: 0L
            val date = runCatching { dateFmt.parse("${parts[5]} ${parts[6].substringBefore('.')}")?.time }
                .getOrNull() ?: 0L
            var name = parts[8]
            val isLink = perms[0] == 'l'
            if (isLink) name = name.substringBefore(" -> ")
            if (name == "." || name == "..") continue
            val isDir = perms[0] == 'd' || (isLink && isDirectory(joinPath(parent, name)))
            out.add(
                FileItem(
                    name = name,
                    path = joinPath(parent, name),
                    isDir = isDir,
                    size = if (isDir) 0 else size,
                    lastModified = date,
                    readOnly = false,
                    sourceId = id,
                ),
            )
        }
        return out
    }

    private fun isDirectory(path: String): Boolean =
        ShizukuBridge.run("[ -d ${q(path)} ] && echo d").out.trim() == "d"

    override fun stat(path: String): FileItem? {
        val r = ShizukuBridge.run("ls -ldA --full-time ${q(path)} 2>/dev/null")
        return parseLs(r.out, parentOf(path) ?: "/").firstOrNull()
    }

    override fun openRead(path: String): InputStream = ShizukuBridge.openRead("cat ${q(path)}")

    override fun openWrite(path: String, size: Long): OutputStream =
        ShizukuBridge.openWrite("cat > ${q(path)}")

    override fun mkdirs(path: String) {
        val r = ShizukuBridge.run("mkdir -p ${q(path)}")
        if (!r.ok) throw UserError("No se pudo crear la carpeta:\n${r.text}")
    }

    override fun createFile(path: String) {
        val r = ShizukuBridge.run("touch ${q(path)}")
        if (!r.ok) throw UserError("No se pudo crear el archivo:\n${r.text}")
    }

    override fun delete(path: String) {
        val r = ShizukuBridge.run("rm -rf ${q(path)}")
        if (!r.ok) throw UserError("No se pudo borrar:\n${r.text}")
    }

    override fun rename(path: String, newName: String) {
        val target = joinPath(parentOf(path) ?: "/", newName)
        val r = ShizukuBridge.run("mv ${q(path)} ${q(target)}")
        if (!r.ok) throw UserError("No se pudo renombrar:\n${r.text}")
    }

    override fun moveWithin(from: String, to: String): Boolean =
        ShizukuBridge.run("mv ${q(from)} ${q(to)}").ok
}
