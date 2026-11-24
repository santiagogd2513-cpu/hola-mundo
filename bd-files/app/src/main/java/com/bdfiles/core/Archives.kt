package com.bdfiles.core

import com.github.junrar.Archive as RarArchive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

enum class ArchiveFormat(val label: String, val canWrite: Boolean) {
    ZIP("ZIP", true),
    SEVEN_Z("7z", false),
    RAR("RAR", false),
    TAR("TAR", false),
    TAR_GZ("TAR.GZ", false),
    TAR_BZ2("TAR.BZ2", false),
    TAR_XZ("TAR.XZ", false),
}

class ArchiveEntry(
    /** Ruta dentro del comprimido, con "/". */
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
)

object Archives {

    /** Extensiones que tratamos como comprimidos (los .jar y .apk también son ZIP). */
    private val ZIP_LIKE = setOf("zip", "jar", "apk", "xapk", "apks", "epub", "cbz")

    fun formatOf(name: String): ArchiveFormat? {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> ArchiveFormat.TAR_BZ2
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> ArchiveFormat.TAR_XZ
            lower.endsWith(".tar") -> ArchiveFormat.TAR
            lower.endsWith(".7z") -> ArchiveFormat.SEVEN_Z
            lower.endsWith(".rar") -> ArchiveFormat.RAR
            lower.substringAfterLast('.', "") in ZIP_LIKE -> ArchiveFormat.ZIP
            else -> null
        }
    }

    fun isArchive(name: String): Boolean = formatOf(name) != null

    /** Lista todo lo que hay dentro del comprimido. */
    fun listEntries(file: File): List<ArchiveEntry> {
        val format = formatOf(file.name) ?: throw UserError("No se reconoce el tipo de comprimido")
        return when (format) {
            ArchiveFormat.ZIP -> ZipFile.builder().setFile(file).get().use { zip ->
                zip.entries.asSequence().map {
                    ArchiveEntry(it.name.trimEnd('/'), it.isDirectory, it.size, it.time)
                }.toList()
            }
            ArchiveFormat.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { sz ->
                sz.entries.map {
                    ArchiveEntry(it.name.trimEnd('/'), it.isDirectory, it.size, it.lastModifiedDate?.time ?: 0)
                }
            }
            ArchiveFormat.RAR -> RarArchive(file).use { rar ->
                rar.fileHeaders.map {
                    val name = it.fileName.replace('\\', '/').trimEnd('/')
                    ArchiveEntry(name, it.isDirectory, it.fullUnpackSize, it.mTime?.time ?: 0)
                }
            }
            else -> tarStream(file, format).use { tar ->
                val out = ArrayList<ArchiveEntry>()
                while (true) {
                    val e = tar.nextEntry ?: break
                    out.add(ArchiveEntry(e.name.trimEnd('/'), e.isDirectory, e.size, e.lastModifiedDate?.time ?: 0))
                }
                out
            }
        }
    }

    private fun tarStream(file: File, format: ArchiveFormat): TarArchiveInputStream {
        val raw = BufferedInputStream(file.inputStream(), 64 * 1024)
        val decompressed: InputStream = when (format) {
            ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(raw)
            ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(raw)
            ArchiveFormat.TAR_XZ -> XZCompressorInputStream(raw)
            else -> raw
        }
        return TarArchiveInputStream(decompressed)
    }

    /**
     * Extrae el comprimido en [destDir].
     * Si [only] no es null, solo saca esas rutas de dentro.
     */
    fun extract(
        file: File,
        destDir: File,
        only: Set<String>? = null,
        report: (OpProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): OpResult {
        val format = formatOf(file.name) ?: throw UserError("No se reconoce el tipo de comprimido")
        destDir.mkdirs()
        val entries = listEntries(file).filter { !it.isDir && (only == null || it.path in only) }
        val total = entries.sumOf { it.size }.coerceAtLeast(1)
        val errors = ArrayList<String>()
        var done = 0L
        var count = 0
        val start = System.currentTimeMillis()

        fun progress(name: String, extra: Long) {
            val elapsed = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(1)
            val d = done + extra
            val speed = d.toDouble() / elapsed
            report(
                OpProgress(
                    action = "Extrayendo",
                    currentName = name,
                    doneBytes = d,
                    totalBytes = total,
                    doneFiles = count,
                    totalFiles = entries.size,
                    bytesPerSecond = speed,
                    elapsedSeconds = elapsed,
                    remainingSeconds = if (speed > 1) ((total - d) / speed).toLong() else -1,
                    fraction = (d.toFloat() / total).coerceIn(0f, 1f),
                ),
            )
        }

        /** Evita el truco de "../" que escribiría fuera de la carpeta destino. */
        fun safeTarget(entryPath: String): File? {
            val target = File(destDir, entryPath)
            val canonicalDest = destDir.canonicalPath
            return if (target.canonicalPath.startsWith(canonicalDest)) target else null
        }

        fun writeEntry(entryPath: String, input: InputStream, size: Long) {
            val target = safeTarget(entryPath)
            if (target == null) {
                errors.add("$entryPath: ruta no permitida, se omitió")
                return
            }
            target.parentFile?.mkdirs()
            BufferedOutputStream(target.outputStream(), 128 * 1024).use { out ->
                val buf = ByteArray(128 * 1024)
                var written = 0L
                while (true) {
                    if (isCancelled()) throw CancelledException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    progress(entryPath.substringAfterLast('/'), written)
                }
            }
            done += size
            count++
        }

        val wanted = entries.map { it.path }.toHashSet()
        when (format) {
            ArchiveFormat.ZIP -> ZipFile.builder().setFile(file).get().use { zip ->
                for (e in zip.entries) {
                    if (isCancelled()) throw CancelledException()
                    val name = e.name.trimEnd('/')
                    if (e.isDirectory) {
                        if (only == null) safeTarget(name)?.mkdirs()
                        continue
                    }
                    if (name !in wanted) continue
                    zip.getInputStream(e).use { writeEntry(name, it, e.size) }
                }
            }
            ArchiveFormat.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { sz ->
                while (true) {
                    if (isCancelled()) throw CancelledException()
                    val e = sz.nextEntry ?: break
                    val name = e.name.trimEnd('/')
                    if (e.isDirectory) {
                        if (only == null) safeTarget(name)?.mkdirs()
                        continue
                    }
                    if (name !in wanted) continue
                    writeEntry(name, sevenZStream(sz), e.size)
                }
            }
            ArchiveFormat.RAR -> RarArchive(file).use { rar ->
                for (h in rar.fileHeaders) {
                    if (isCancelled()) throw CancelledException()
                    val name = h.fileName.replace('\\', '/').trimEnd('/')
                    if (h.isDirectory) {
                        if (only == null) safeTarget(name)?.mkdirs()
                        continue
                    }
                    if (name !in wanted) continue
                    val target = safeTarget(name)
                    if (target == null) {
                        errors.add("$name: ruta no permitida, se omitió")
                        continue
                    }
                    target.parentFile?.mkdirs()
                    BufferedOutputStream(target.outputStream(), 128 * 1024).use { rar.extractFile(h, it) }
                    done += h.fullUnpackSize
                    count++
                    progress(name.substringAfterLast('/'), 0)
                }
            }
            else -> tarStream(file, format).use { tar ->
                while (true) {
                    if (isCancelled()) throw CancelledException()
                    val e = tar.nextEntry ?: break
                    val name = e.name.trimEnd('/')
                    if (e.isDirectory) {
                        if (only == null) safeTarget(name)?.mkdirs()
                        continue
                    }
                    if (name !in wanted) continue
                    writeEntry(name, tar, e.size)
                }
            }
        }
        return OpResult(
            "Extraído",
            "$count archivo${if (count == 1) "" else "s"} en “${destDir.name}”.",
            errors,
        )
    }

    /** SevenZFile da los datos con read(), hay que envolverlo en un flujo. */
    private fun sevenZStream(sz: SevenZFile): InputStream = object : InputStream() {
        override fun read(): Int = sz.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = sz.read(b, off, len)
        override fun close() {} // lo cierra quien abrió el 7z
    }

    /** Crea un ZIP con lo que se le pase. */
    fun createZip(
        items: List<File>,
        target: File,
        report: (OpProgress) -> Unit,
        isCancelled: () -> Boolean,
        level: Int = 6,
    ): OpResult {
        val files = ArrayList<Pair<File, String>>()
        fun walk(f: File, prefix: String) {
            if (isCancelled()) throw CancelledException()
            if (f.isDirectory) {
                val kids = f.listFiles()
                if (kids.isNullOrEmpty()) {
                    files.add(f to "$prefix${f.name}/")
                } else {
                    kids.forEach { walk(it, "$prefix${f.name}/") }
                }
            } else {
                files.add(f to "$prefix${f.name}")
            }
        }
        items.forEach { walk(it, "") }

        val total = files.sumOf { if (it.first.isDirectory) 0 else it.first.length() }.coerceAtLeast(1)
        var done = 0L
        var count = 0
        val start = System.currentTimeMillis()
        val errors = ArrayList<String>()

        target.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(target.outputStream(), 256 * 1024)).use { zip ->
            zip.setLevel(level)
            for ((file, entryName) in files) {
                if (isCancelled()) {
                    runCatching { zip.close() }
                    target.delete()
                    throw CancelledException()
                }
                try {
                    if (file.isDirectory) {
                        zip.putNextEntry(ZipEntry(entryName))
                        zip.closeEntry()
                        continue
                    }
                    val entry = ZipEntry(entryName)
                    entry.time = file.lastModified()
                    zip.putNextEntry(entry)
                    file.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        var written = 0L
                        while (true) {
                            if (isCancelled()) throw CancelledException()
                            val n = input.read(buf)
                            if (n < 0) break
                            zip.write(buf, 0, n)
                            written += n
                            val elapsed = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(1)
                            val d = done + written
                            val speed = d.toDouble() / elapsed
                            report(
                                OpProgress(
                                    action = "Comprimiendo",
                                    currentName = file.name,
                                    doneBytes = d,
                                    totalBytes = total,
                                    doneFiles = count,
                                    totalFiles = files.size,
                                    bytesPerSecond = speed,
                                    elapsedSeconds = elapsed,
                                    remainingSeconds = if (speed > 1) ((total - d) / speed).toLong() else -1,
                                    fraction = (d.toFloat() / total).coerceIn(0f, 1f),
                                ),
                            )
                        }
                        done += written
                    }
                    zip.closeEntry()
                    count++
                } catch (e: CancelledException) {
                    throw e
                } catch (e: Exception) {
                    errors.add("${file.name}: ${e.message}")
                }
            }
        }
        return OpResult("Comprimido", "$count archivos en “${target.name}” (${formatSize(target.length())}).", errors)
    }

    /** Añade archivos a un ZIP que ya existe (se reescribe entero, que es como funciona el formato). */
    fun addToZip(
        zipFile: File,
        newFiles: List<File>,
        insideDir: String,
        report: (OpProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): OpResult {
        if (formatOf(zipFile.name) != ArchiveFormat.ZIP) {
            throw UserError("Solo se puede añadir a comprimidos ZIP. Los 7z y RAR solo se pueden leer.")
        }
        val temp = File(zipFile.parentFile, zipFile.name + ".tmp")
        val prefix = insideDir.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val addedNames = newFiles.map { prefix + it.name }.toSet()
        var count = 0
        try {
            ZipOutputStream(BufferedOutputStream(temp.outputStream(), 256 * 1024)).use { out ->
                ZipFile.builder().setFile(zipFile).get().use { zip ->
                    for (e in zip.entries) {
                        if (isCancelled()) throw CancelledException()
                        if (e.name in addedNames) continue // se reemplaza
                        report(OpProgress("Actualizando comprimido", e.name, indeterminate = true))
                        out.putNextEntry(ZipEntry(e.name).also { it.time = e.time })
                        if (!e.isDirectory) zip.getInputStream(e).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
                for (f in newFiles) {
                    if (isCancelled()) throw CancelledException()
                    report(OpProgress("Añadiendo al comprimido", f.name, indeterminate = true))
                    out.putNextEntry(ZipEntry(prefix + f.name).also { it.time = f.lastModified() })
                    f.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                    count++
                }
            }
            if (!zipFile.delete() || !temp.renameTo(zipFile)) {
                throw UserError("No se pudo sustituir el comprimido original")
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        return OpResult("Añadido", "$count archivo${if (count == 1) "" else "s"} en “${zipFile.name}”.")
    }

    /** Quita entradas de un ZIP. */
    fun removeFromZip(
        zipFile: File,
        paths: Set<String>,
        report: (OpProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): OpResult {
        if (formatOf(zipFile.name) != ArchiveFormat.ZIP) {
            throw UserError("Solo se puede borrar dentro de comprimidos ZIP.")
        }
        val temp = File(zipFile.parentFile, zipFile.name + ".tmp")
        var removed = 0
        try {
            ZipOutputStream(BufferedOutputStream(temp.outputStream(), 256 * 1024)).use { out ->
                ZipFile.builder().setFile(zipFile).get().use { zip ->
                    for (e in zip.entries) {
                        if (isCancelled()) throw CancelledException()
                        val name = e.name.trimEnd('/')
                        if (name in paths || paths.any { name.startsWith("$it/") }) {
                            removed++
                            continue
                        }
                        report(OpProgress("Actualizando comprimido", e.name, indeterminate = true))
                        out.putNextEntry(ZipEntry(e.name).also { it.time = e.time })
                        if (!e.isDirectory) zip.getInputStream(e).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            if (!zipFile.delete() || !temp.renameTo(zipFile)) {
                throw UserError("No se pudo sustituir el comprimido original")
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        return OpResult("Borrado del comprimido", "$removed elemento${if (removed == 1) "" else "s"} quitados.")
    }

    /** Saca un archivo concreto del comprimido a un destino (para abrirlo o verlo). */
    fun extractSingle(archive: File, entryPath: String, target: File) {
        val format = formatOf(archive.name) ?: throw UserError("Comprimido no reconocido")
        target.parentFile?.mkdirs()
        when (format) {
            ArchiveFormat.ZIP -> ZipFile.builder().setFile(archive).get().use { zip ->
                val e = zip.getEntry(entryPath) ?: throw UserError("No está “$entryPath” dentro")
                zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            ArchiveFormat.RAR -> RarArchive(archive).use { rar ->
                val h = rar.fileHeaders.firstOrNull {
                    it.fileName.replace('\\', '/').trimEnd('/') == entryPath
                } ?: throw UserError("No está “$entryPath” dentro")
                target.outputStream().use { rar.extractFile(h, it) }
            }
            ArchiveFormat.SEVEN_Z -> SevenZFile.builder().setFile(archive).get().use { sz ->
                while (true) {
                    val e = sz.nextEntry ?: throw UserError("No está “$entryPath” dentro")
                    if (e.name.trimEnd('/') != entryPath) continue
                    target.outputStream().use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = sz.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    return
                }
            }
            else -> tarStream(archive, format).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: throw UserError("No está “$entryPath” dentro")
                    if (e.name.trimEnd('/') != entryPath) continue
                    target.outputStream().use { tar.copyTo(it) }
                    return
                }
            }
        }
    }
}
