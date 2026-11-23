package com.libertyv.core.rpf

import com.libertyv.core.io.copyCount
import java.io.OutputStream

/** Cómo se exportan los recursos (igual que las opciones de LibertyV). */
enum class ResourceExportMode(val label: String, val description: String) {
    RSC7("RSC7", "Cabecera RSC7 + datos descomprimidos (un archivo)"),
    SYS_GFX("SYS + GFX", "Páginas system y graphics en archivos separados"),
    RAW("Tal cual", "Bytes exactos del RPF (comprimidos)"),
}

/** Dónde se crean los archivos exportados (carpeta del sistema, almacenamiento de Android, etc.). */
fun interface ExportTarget {
    /** Crea (o sobrescribe) el archivo en la ruta relativa dada, con "/" como separador. */
    fun create(relativePath: String): OutputStream
}

class ExportProgress(val currentName: String, val doneBytes: Long, val totalBytes: Long, val doneFiles: Int, val totalFiles: Int)

class RpfExporter(
    private val archive: Rpf7Archive,
    private val resourceMode: ResourceExportMode,
) {
    /** Nombres de archivo que generará [entry] (más de uno solo con SYS + GFX). */
    fun outputNames(entry: RpfFileEntry): List<String> {
        if (entry is RpfResource && resourceMode == ResourceExportMode.SYS_GFX) {
            return when {
                entry.graphicsSize == 0L -> listOf(entry.name)
                entry.systemSize == 0L -> listOf("${entry.name}.gfx")
                else -> listOf("${entry.name}.sys", "${entry.name}.gfx")
            }
        }
        return listOf(entry.name)
    }

    /** Escribe un archivo en [out] (para SYS + GFX usa [exportTo]). */
    fun write(entry: RpfFileEntry, out: OutputStream, progress: ((Long) -> Unit)? = null) {
        when {
            entry is RpfResource && resourceMode == ResourceExportMode.RSC7 ->
                archive.openRsc7(entry).use { it.copyCount(out, progress = progress) }
            entry is RpfResource && resourceMode == ResourceExportMode.RAW ->
                archive.writeStored(entry, out, progress)
            entry is RpfResource && resourceMode == ResourceExportMode.SYS_GFX ->
                archive.openContent(entry).use { it.copyCount(out, progress = progress) }
            else -> archive.openContent(entry).use { it.copyCount(out, progress = progress) }
        }
    }

    /** Exporta un archivo a [target] dentro de la carpeta [dir] ("" = raíz). */
    fun exportTo(entry: RpfFileEntry, target: ExportTarget, dir: String = "", progress: ((Long) -> Unit)? = null) {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        if (entry is RpfResource && resourceMode == ResourceExportMode.SYS_GFX && entry.graphicsSize != 0L && entry.systemSize != 0L) {
            archive.openContent(entry).use { input ->
                target.create("$prefix${entry.name}.sys").use { input.copyCount(it, entry.systemSize, progress) }
                val base = entry.systemSize
                target.create("$prefix${entry.name}.gfx").use {
                    input.copyCount(it, entry.graphicsSize) { n -> progress?.invoke(base + n) }
                }
            }
            return
        }
        target.create(prefix + outputNames(entry).first()).use { write(entry, it, progress) }
    }

    /**
     * Exporta una carpeta completa manteniendo la estructura.
     * [onProgress] se llama a menudo; puede lanzar una excepción para cancelar.
     * Devuelve los nombres de los archivos que fallaron, con el motivo.
     */
    fun exportDirectory(
        dir: RpfDirectory,
        target: ExportTarget,
        onProgress: (ExportProgress) -> Unit,
    ): List<Pair<String, String>> {
        val files = dir.allFiles()
        val total = files.sumOf { it.size }
        var done = 0L
        val errors = ArrayList<Pair<String, String>>()
        files.forEachIndexed { i, f ->
            val rel = relativeDir(dir, f)
            onProgress(ExportProgress(f.name, done, total, i, files.size))
            val base = done
            try {
                exportTo(f, target, rel) { n -> onProgress(ExportProgress(f.name, base + n, total, i, files.size)) }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                if (e.javaClass.name.endsWith("CancellationException")) throw e
                errors.add(f.path to (e.message ?: e.javaClass.simpleName))
            }
            done = base + f.size
        }
        onProgress(ExportProgress("", total, total, files.size, files.size))
        return errors
    }

    private fun relativeDir(root: RpfDirectory, f: RpfEntry): String {
        val parts = ArrayList<String>()
        var p = f.parent
        while (p != null && p !== root) {
            parts.add(p.name)
            p = p.parent
        }
        val base = if (root.parent == null || root.name.isEmpty()) "" else root.name
        val inner = parts.reversed().joinToString("/")
        return listOf(base, inner).filter { it.isNotEmpty() }.joinToString("/")
    }
}
