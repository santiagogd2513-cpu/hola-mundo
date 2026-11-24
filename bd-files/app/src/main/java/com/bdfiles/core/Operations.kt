package com.bdfiles.core

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** Lo que se está haciendo ahora mismo, para la barra de progreso. */
data class OpProgress(
    /** "Copiando", "Moviendo", "Borrando", "Comprimiendo", "Extrayendo", "Verificando"... */
    val action: String,
    val currentName: String = "",
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val doneFiles: Int = 0,
    val totalFiles: Int = 0,
    val bytesPerSecond: Double = 0.0,
    val elapsedSeconds: Long = 0,
    /** Segundos que faltan, o -1 si todavía no se puede saber. */
    val remainingSeconds: Long = -1,
    /** Entre 0 y 1, o null si no se puede calcular. */
    val fraction: Float? = null,
    val indeterminate: Boolean = false,
)

/** Resultado de una operación, para enseñárselo al usuario al terminar. */
class OpResult(
    val title: String,
    val detail: String,
    val errors: List<String> = emptyList(),
) {
    val ok: Boolean get() = errors.isEmpty()
}

class CancelledException : Exception("Operación cancelada")

/** Qué hacer cuando el archivo ya existe en el destino. */
enum class Conflict { ASK, SKIP, OVERWRITE, KEEP_BOTH }

/**
 * Copiar, mover y borrar, con verificación opcional.
 *
 * Al copiar o mover se calcula la huella (CRC32) de lo que se va leyendo y,
 * al terminar cada archivo, se vuelve a leer el destino para comprobar que
 * coincide. En "mover", el original solo se borra si la comprobación sale bien.
 */
class FileOperations(
    private val report: (OpProgress) -> Unit,
    private val isCancelled: () -> Boolean,
    private val verify: Boolean = true,
) {
    private var startTime = 0L
    private var totalBytes = 0L
    private var doneBytes = 0L
    private var totalFiles = 0
    private var doneFiles = 0
    private var lastReport = 0L
    private val errors = ArrayList<String>()

    private fun checkCancelled() {
        if (isCancelled()) throw CancelledException()
    }

    private fun emit(action: String, name: String, extraBytes: Long = 0, force: Boolean = false, indeterminate: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastReport < 100) return
        lastReport = now
        val elapsedMs = (now - startTime).coerceAtLeast(1)
        val done = doneBytes + extraBytes
        val speed = done * 1000.0 / elapsedMs
        val remaining = if (speed > 1 && totalBytes > 0 && done <= totalBytes) {
            ((totalBytes - done) / speed).toLong()
        } else -1
        report(
            OpProgress(
                action = action,
                currentName = name,
                doneBytes = done,
                totalBytes = totalBytes,
                doneFiles = doneFiles,
                totalFiles = totalFiles,
                bytesPerSecond = speed,
                elapsedSeconds = elapsedMs / 1000,
                remainingSeconds = remaining,
                fraction = if (indeterminate || totalBytes <= 0) null
                else (done.toFloat() / totalBytes).coerceIn(0f, 1f),
                indeterminate = indeterminate,
            ),
        )
    }

    /**
     * Suma el tamaño y el número de archivos. Va avisando del recuento para que
     * no parezca que la app se ha quedado colgada.
     *
     * En servidores de red no se mide nada: recorrer carpetas por FTP es muy lento
     * y haría esperar un buen rato antes de empezar a copiar.
     */
    private fun measure(source: FileSource, items: List<FileItem>, action: String): Pair<Long, Int> {
        if (source.isRemote) {
            // Lo único que se sabe sin recorrer nada: lo que hay a la vista.
            val known = items.filter { !it.isDir }
            return 0L to known.size
        }
        var bytes = 0L
        var count = 0
        fun walk(item: FileItem) {
            checkCancelled()
            if (item.isDir) {
                report(
                    OpProgress(
                        action = action,
                        currentName = "Calculando tamaño… $count archivos (${formatSize(bytes)})",
                        indeterminate = true,
                    ),
                )
                runCatching { source.list(item.path) }.getOrDefault(emptyList()).forEach { walk(it) }
            } else {
                bytes += item.size
                count++
                if (count % 40 == 0) {
                    report(
                        OpProgress(
                            action = action,
                            currentName = "Calculando tamaño… $count archivos (${formatSize(bytes)})",
                            indeterminate = true,
                        ),
                    )
                }
            }
        }
        items.forEach { walk(it) }
        return bytes to count
    }

    // ------------------------------------------------------------ copiar y mover

    fun copy(
        from: FileSource,
        items: List<FileItem>,
        to: FileSource,
        destDir: String,
        move: Boolean,
        conflict: Conflict = Conflict.KEEP_BOTH,
    ): OpResult {
        startTime = System.currentTimeMillis()
        val action = if (move) "Moviendo" else "Copiando"
        emit(action, "Preparando…", force = true, indeterminate = true)
        val (bytes, count) = measure(from, items, action)
        totalBytes = bytes
        totalFiles = count
        doneBytes = 0
        doneFiles = 0

        if (from.isRemote) {
            totalBytes = items.filter { !it.isDir }.sumOf { it.size }
        }
        val sameSource = from.id == to.id
        for (item in items) {
            checkCancelled()
            copyEntry(from, item, to, destDir, move, sameSource, conflict, action)
        }

        emit(action, "", force = true)
        val what = if (count == 1) "1 archivo" else "$count archivos"
        return OpResult(
            title = if (move) "Movido" else "Copiado",
            detail = buildString {
                append("$what (${formatSize(bytes)})")
                if (verify) append(", verificado")
                append('.')
            },
            errors = errors.toList(),
        )
    }

    private fun copyEntry(
        from: FileSource,
        item: FileItem,
        to: FileSource,
        destDir: String,
        move: Boolean,
        sameSource: Boolean,
        conflict: Conflict,
        action: String,
    ) {
        checkCancelled()
        val existing = runCatching { to.list(destDir).map { it.name }.toSet() }.getOrDefault(emptySet())
        val targetName = when {
            item.name !in existing -> item.name
            conflict == Conflict.SKIP -> {
                errors.add("${item.name}: ya existía, se omitió")
                return
            }
            conflict == Conflict.OVERWRITE -> item.name
            else -> uniqueName(existing, item.name)
        }
        val targetPath = joinPath(destDir, targetName)

        if (item.isDir) {
            to.mkdirs(targetPath)
            emit(action, "Leyendo ${item.name}…", force = true, indeterminate = true)
            val children = runCatching { from.list(item.path) }.getOrElse {
                errors.add("${item.path}: no se pudo leer (${it.message})")
                return
            }
            if (from.isRemote) {
                // No se midió antes: el total crece según se va descubriendo.
                totalFiles += children.count { !it.isDir }
                totalBytes += children.filter { !it.isDir }.sumOf { it.size }
            }
            for (c in children) copyEntry(from, c, to, targetPath, move, sameSource, conflict, action)
            if (move) runCatching { from.delete(item.path) }
            return
        }

        // Mover dentro del mismo sitio: si se puede renombrar, es instantáneo.
        if (move && sameSource && from.moveWithin(item.path, targetPath)) {
            doneBytes += item.size
            doneFiles++
            emit(action, item.name, force = true)
            return
        }

        try {
            val crcSource = transfer(from, item, to, targetPath, action)
            if (verify) {
                val crcDest = verifyCopy(to, targetPath, item.size, item.name)
                if (crcDest != crcSource) {
                    runCatching { to.delete(targetPath) }
                    errors.add("${item.name}: la copia no coincide con el original, se descartó")
                    doneBytes += item.size
                    doneFiles++
                    return
                }
            }
            if (move) {
                // El original solo se borra con la comprobación hecha.
                runCatching { from.delete(item.path) }
                    .onFailure { errors.add("${item.name}: copiado, pero no se pudo borrar el original") }
            }
        } catch (e: CancelledException) {
            runCatching { to.delete(targetPath) }
            throw e
        } catch (e: Exception) {
            errors.add("${item.name}: ${e.message ?: e.javaClass.simpleName}")
        }
        doneBytes += item.size
        doneFiles++
        emit(action, item.name, force = true)
    }

    /** Copia un archivo y devuelve la huella CRC32 de lo que se leyó. */
    private fun transfer(
        from: FileSource,
        item: FileItem,
        to: FileSource,
        targetPath: String,
        action: String,
    ): Long {
        val crc = CRC32()
        val buf = ByteArray(256 * 1024)
        var written = 0L
        from.openRead(item.path).use { input ->
            to.openWrite(targetPath, item.size).use { output ->
                while (true) {
                    checkCancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    crc.update(buf, 0, n)
                    written += n
                    emit(action, item.name, extraBytes = written)
                }
                output.flush()
            }
        }
        return crc.value
    }

    /** Vuelve a leer el archivo ya escrito y devuelve su huella. */
    private fun verifyCopy(to: FileSource, path: String, expectedSize: Long, name: String): Long {
        emit("Verificando", name, force = true, indeterminate = true)
        val stat = to.stat(path)
        if (stat != null && expectedSize > 0 && stat.size != expectedSize && stat.size > 0) {
            return -1 // tamaño distinto: seguro que no coincide
        }
        val crc = CRC32()
        val buf = ByteArray(256 * 1024)
        to.openRead(path).use { input ->
            while (true) {
                checkCancelled()
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    // ------------------------------------------------------------ borrar

    fun delete(source: FileSource, items: List<FileItem>): OpResult {
        startTime = System.currentTimeMillis()
        emit("Borrando", "Preparando…", force = true, indeterminate = true)
        val (bytes, count) = measure(source, items, "Borrando")
        totalBytes = bytes
        totalFiles = count
        doneFiles = 0
        doneBytes = 0
        for (item in items) {
            checkCancelled()
            emit("Borrando", item.name, force = true, indeterminate = true)
            try {
                source.delete(item.path)
                doneFiles++
                doneBytes += item.size
            } catch (e: Exception) {
                errors.add("${item.name}: ${e.message}")
            }
        }
        emit("Borrando", "", force = true)
        return OpResult(
            "Borrado",
            "${items.size} elemento${if (items.size == 1) "" else "s"} ($count archivos, ${formatSize(bytes)}).",
            errors.toList(),
        )
    }

    /** Mueve a la papelera en vez de borrar. */
    fun moveToTrash(source: FileSource, items: List<FileItem>, trashDir: String): OpResult {
        source.mkdirs(trashDir)
        val result = copy(source, items, source, trashDir, move = true, conflict = Conflict.KEEP_BOTH)
        return OpResult("En la papelera", result.detail + "\nPuedes recuperarlo desde la papelera.", result.errors)
    }
}

/** Copia un flujo a otro avisando del avance. Devuelve los bytes copiados. */
fun InputStream.copyWithProgress(
    out: OutputStream,
    onProgress: (Long) -> Unit,
    checkCancelled: () -> Unit = {},
): Long {
    val buf = ByteArray(256 * 1024)
    var total = 0L
    while (true) {
        checkCancelled()
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
        onProgress(total)
    }
    out.flush()
    return total
}
