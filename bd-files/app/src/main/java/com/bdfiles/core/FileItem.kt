package com.bdfiles.core

import java.util.Locale

/** Un archivo o carpeta, venga de donde venga (almacenamiento, tarjeta, Shizuku o FTP). */
data class FileItem(
    val name: String,
    /** Ruta completa dentro de su origen. */
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val isHidden: Boolean = name.startsWith("."),
    /** true si no se puede escribir en él. */
    val readOnly: Boolean = false,
    /** Identificador del origen al que pertenece. */
    val sourceId: String = "local",
) {
    val extension: String
        get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    val parentPath: String
        get() = path.substringBeforeLast('/', "").ifEmpty { "/" }

    val key: String get() = "$sourceId|$path"
}

enum class SortBy(val label: String) {
    NAME("Nombre"),
    SIZE("Tamaño"),
    DATE("Fecha"),
    TYPE("Tipo"),
}

object FileSorting {
    /**
     * Ordena la lista: primero las carpetas ancladas, luego el resto de carpetas,
     * luego los archivos. Dentro de cada grupo, según [by].
     */
    fun sort(
        items: List<FileItem>,
        by: SortBy,
        descending: Boolean,
        foldersFirst: Boolean,
        pinned: Set<String>,
    ): List<FileItem> {
        val base = Comparator<FileItem> { a, b ->
            val r = when (by) {
                SortBy.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                SortBy.SIZE -> a.size.compareTo(b.size)
                SortBy.DATE -> a.lastModified.compareTo(b.lastModified)
                SortBy.TYPE -> a.extension.compareTo(b.extension).let {
                    if (it != 0) it else a.name.compareTo(b.name, ignoreCase = true)
                }
            }
            if (descending) -r else r
        }
        val full = Comparator<FileItem> { a, b ->
            val pa = if (a.key in pinned) 0 else 1
            val pb = if (b.key in pinned) 0 else 1
            when {
                pa != pb -> pa - pb
                foldersFirst && a.isDir != b.isDir -> if (a.isDir) -1 else 1
                else -> base.compare(a, b)
            }
        }
        return items.sortedWith(full)
    }
}

/** Tamaños legibles: 1.4 MB, 820 KB... */
fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
}

fun formatSpeed(bytesPerSecond: Double): String = when {
    bytesPerSecond <= 0 -> "—"
    bytesPerSecond < 1024 -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
    bytesPerSecond < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024)
    else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1048576)
}

/** Duración corta: 45 s, 3:20, 1:05:00 */
fun formatDuration(seconds: Long): String {
    if (seconds < 0) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        m > 0 -> String.format(Locale.US, "%d:%02d", m, s)
        else -> "$s s"
    }
}

fun formatDate(millis: Long): String {
    if (millis <= 0) return ""
    val d = java.util.Date(millis)
    return java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(d)
}
