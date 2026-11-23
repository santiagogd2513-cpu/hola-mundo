package com.libertyv.app

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libertyv.app.audio.NativeAudio
import com.libertyv.core.ConsolePlatform
import com.libertyv.core.audio.AwcFile
import com.libertyv.core.audio.AwcTrack
import com.libertyv.core.audio.WavWriter
import com.libertyv.core.io.DataSource
import com.libertyv.core.io.FileSource
import com.libertyv.core.io.copyCount
import com.libertyv.core.rpf.ExportProgress
import com.libertyv.core.rpf.ResourceExportMode
import com.libertyv.core.rpf.Rpf7Archive
import com.libertyv.core.rpf.Rpf7Writer
import com.libertyv.core.rpf.RpfDirectory
import com.libertyv.core.rpf.RpfEntry
import com.libertyv.core.rpf.RpfException
import com.libertyv.core.rpf.RpfExporter
import com.libertyv.core.rpf.RpfFileEntry
import com.libertyv.core.rpf.RpfRegularFile
import com.libertyv.core.rpf.RpfResource
import com.libertyv.core.rpf.SaveProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/** Un RPF abierto (puede ser un RPF dentro de otro). */
class OpenArchive(
    val archive: Rpf7Archive,
    val title: String,
    private val tempFile: File?,
    /** Documento de Android del que viene (solo el RPF principal). null = no hay dónde "Guardar". */
    val uri: Uri?,
    /** Si es un RPF dentro de otro: la entrada del padre que hay que actualizar al aplicar cambios. */
    val parentEntry: RpfFileEntry?,
) {
    val isNested: Boolean get() = parentEntry != null

    fun close() {
        runCatching { archive.close() }
        tempFile?.delete()
    }
}

data class BusyState(val title: String, val detail: String = "", val progress: Float? = null)

class AwcState(val entry: RpfFileEntry, val awc: AwcFile, val platform: ConsolePlatform, val source: DataSource, val tempFile: File)

/** Un archivo elegido para importar. */
class ImportItem(
    /** Ruta relativa con "/" (incluye la carpeta elegida si se importó una carpeta). */
    val relPath: String,
    val file: File,
    val isResource: Boolean,
    val exists: Boolean,
    /** Motivo por el que no se puede importar (null = se puede). */
    val problem: String?,
    include: Boolean,
    compress: Boolean,
) {
    var include by mutableStateOf(include)
    var compress by mutableStateOf(compress)
}

class ImportPlan(val dir: RpfDirectory, val items: List<ImportItem>, val folderName: String?) {
    /** Para carpetas: si se crea la carpeta elegida o solo se importa su contenido. */
    var includeFolder by mutableStateOf(true)

    fun targetPath(item: ImportItem): String =
        if (folderName != null && !includeFolder) item.relPath.substringAfter('/') else item.relPath
}

class ConfirmRequest(val title: String, val text: String, val confirmLabel: String, val onConfirm: () -> Unit)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val keyStore = KeyStore(ctx)
    private val settings = Settings(ctx)
    private val importDir get() = File(ctx.cacheDir, "import").apply { mkdirs() }

    var keys by mutableStateOf(keyStore.loadAll())
        private set
    var resourceMode by mutableStateOf(settings.resourceMode)
        private set

    val stack = mutableStateListOf<OpenArchive>()
    var currentDir by mutableStateOf<RpfDirectory?>(null)
        private set
    var search by mutableStateOf("")
    var selected by mutableStateOf<RpfFileEntry?>(null)
    var message by mutableStateOf<String?>(null)
    var textPreview by mutableStateOf<Pair<String, String>?>(null)
    var awcState by mutableStateOf<AwcState?>(null)
        private set
    var playingTrack by mutableStateOf<Int?>(null)
        private set

    // Edición
    /** Sube cada vez que cambia el contenido del RPF, para que la interfaz se redibuje. */
    var treeVersion by mutableIntStateOf(0)
        private set
    val marked = mutableStateListOf<RpfEntry>()
    var importPlan by mutableStateOf<ImportPlan?>(null)
    var confirm by mutableStateOf<ConfirmRequest?>(null)
    var renaming by mutableStateOf<RpfEntry?>(null)
    var creatingFolder by mutableStateOf(false)

    /** Lo que se exportará cuando el usuario elija destino en el selector de Android. */
    var pendingExport: RpfEntry? = null

    /** Archivo a reemplazar cuando el usuario elija el nuevo. */
    var pendingReplace: RpfFileEntry? = null

    private val _busy = MutableStateFlow<BusyState?>(null)
    val busy: StateFlow<BusyState?> = _busy
    private var job: Job? = null
    private var player: MediaPlayer? = null

    val current: OpenArchive? get() = stack.lastOrNull()

    val isModified: Boolean get() {
        @Suppress("UNUSED_VARIABLE") val v = treeVersion // lectura para que Compose observe cambios
        return current?.archive?.isModified == true
    }

    private fun anyModified() = stack.any { it.archive.isModified }

    fun chooseResourceMode(m: ResourceExportMode) {
        resourceMode = m
        settings.resourceMode = m
    }

    // ---------- tareas en segundo plano ----------

    private fun runTask(title: String, block: suspend CoroutineScope.() -> Unit) {
        if (job?.isActive == true) return
        _busy.value = BusyState(title)
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main + NonCancellable) { message = "Operación cancelada." }
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) { message = "No hay memoria suficiente para esta operación." }
            } catch (e: Exception) {
                val text = when (e) {
                    is UserMessage, is RpfException -> e.message
                    else -> e.message ?: e.javaClass.simpleName
                }
                withContext(Dispatchers.Main) { message = text }
            } finally {
                _busy.value = null
            }
        }
    }

    fun cancelTask() {
        job?.cancel()
    }

    private var lastUpdate = 0L

    private fun progress(title: String, detail: String, fraction: Float?) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 80 && fraction != null && fraction < 1f) return
        lastUpdate = now
        _busy.value = BusyState(title, detail, fraction)
    }

    private fun changed() {
        treeVersion++
    }

    /** Pide confirmación si hay cambios sin guardar; si no, ejecuta [action] directamente. */
    private fun guardUnsaved(onlyCurrent: Boolean, action: () -> Unit) {
        val dirty = if (onlyCurrent) current?.archive?.isModified == true else anyModified()
        if (!dirty) return action()
        confirm = ConfirmRequest(
            "Cambios sin guardar",
            "Hay cambios que no has guardado y se perderán.",
            "Descartar cambios",
            action,
        )
    }

    // ---------- claves ----------

    fun loadKey(uri: Uri) = runTask("Cargando clave") {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("No se pudo leer el archivo")
        val p = keyStore.save(bytes)
        withContext(Dispatchers.Main) {
            keys = keyStore.loadAll()
            message = "Clave de ${p.displayName} cargada correctamente."
        }
    }

    fun deleteKey(p: ConsolePlatform) {
        keyStore.delete(p)
        keys = keyStore.loadAll()
    }

    // ---------- abrir RPF ----------

    fun openRpf(uri: Uri) = guardUnsaved(onlyCurrent = false) {
        runTask("Abriendo RPF") {
            val name = ctx.contentResolver.displayName(uri)
            val src = ctx.contentResolver.openDataSource(uri)
            try {
                val archive = openArchive(src, name)
                withContext(Dispatchers.Main) {
                    closeAllNow()
                    push(OpenArchive(archive, name, null, uri, null))
                }
            } catch (e: Throwable) {
                src.close()
                throw e
            }
        }
    }

    private fun openArchive(src: DataSource, name: String): Rpf7Archive {
        val platform = Rpf7Archive.detectPlatform(src)
            ?: throw UserMessage("“$name” no es un archivo RPF7 de PS3 o Xbox 360.")
        val key = keys[platform]
            ?: throw UserMessage(
                "“$name” es de ${platform.displayName} y falta su clave. " +
                    "Carga ${platform.keyFileName} en la pantalla principal.",
            )
        return Rpf7Archive.open(src, platform, key, name)
    }

    private fun push(a: OpenArchive) {
        stack.add(a)
        currentDir = a.archive.root
        search = ""
        marked.clear()
        changed()
    }

    fun openNestedRpf(entry: RpfFileEntry) {
        val parent = current ?: return
        runTask("Abriendo ${entry.name}") {
            val slice = parent.archive.plainSlice(entry)
            if (slice != null) {
                val a = openArchive(slice, entry.name)
                withContext(Dispatchers.Main) { push(OpenArchive(a, entry.name, null, null, entry)) }
            } else {
                val tmp = File.createTempFile("nested", ".rpf", ctx.cacheDir)
                try {
                    extractTo(parent.archive, entry, tmp, "Extrayendo ${entry.name}")
                    val a = openArchive(fileDataSource(tmp), entry.name)
                    withContext(Dispatchers.Main) { push(OpenArchive(a, entry.name, tmp, null, entry)) }
                } catch (e: Throwable) {
                    tmp.delete()
                    throw e
                }
            }
        }
    }

    private suspend fun extractTo(archive: Rpf7Archive, entry: RpfFileEntry, file: File, title: String) {
        val scope = coroutineContext
        BufferedOutputStream(file.outputStream(), 256 * 1024).use { out ->
            archive.openContent(entry).use { input ->
                input.copyCount(out) { n ->
                    scope.ensureActive()
                    progress(title, formatSize(n), if (entry.size > 0) n.toFloat() / entry.size else null)
                }
            }
        }
    }

    // ---------- navegación ----------

    fun enter(dir: RpfDirectory) {
        currentDir = dir
        search = ""
        marked.clear()
    }

    /** Vuelve atrás (carpeta padre, quitar búsqueda/selección o cerrar el RPF actual). */
    fun back() {
        val dir = currentDir ?: return
        if (marked.isNotEmpty()) {
            marked.clear()
            return
        }
        if (search.isNotEmpty()) {
            search = ""
            return
        }
        val parent = dir.parent
        if (parent != null) {
            currentDir = parent
            return
        }
        guardUnsaved(onlyCurrent = true) { popArchive() }
    }

    private fun popArchive() {
        val top = stack.removeLastOrNull() ?: return
        top.close()
        val now = current
        currentDir = if (now != null && top.parentEntry != null) top.parentEntry.parent ?: now.archive.root else now?.archive?.root
        marked.clear()
        if (stack.isEmpty()) cleanImports()
        changed()
    }

    fun closeAll() = guardUnsaved(onlyCurrent = false) { closeAllNow() }

    private fun closeAllNow() {
        stopAudio()
        while (stack.isNotEmpty()) stack.removeAt(stack.lastIndex).close()
        currentDir = null
        search = ""
        marked.clear()
        cleanImports()
    }

    private fun cleanImports() {
        importDir.listFiles()?.forEach { it.delete() }
    }

    // ---------- exportar ----------

    private fun exporter(a: Rpf7Archive) = RpfExporter(a, resourceMode)

    /** true si el archivo se exporta en varios archivos (y por tanto necesita una carpeta). */
    fun needsFolder(entry: RpfFileEntry): Boolean {
        val a = current?.archive ?: return false
        return exporter(a).outputNames(entry).size > 1
    }

    fun suggestedName(entry: RpfFileEntry): String {
        val a = current?.archive ?: return entry.name
        return exporter(a).outputNames(entry).first()
    }

    fun exportFileTo(entry: RpfFileEntry, uri: Uri) {
        val a = current?.archive ?: return
        runTask("Exportando ${entry.name}") {
            val scope = coroutineContext
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No se pudo escribir")
            BufferedOutputStream(out, 256 * 1024).use { o ->
                exporter(a).write(entry, o) { n ->
                    scope.ensureActive()
                    progress("Exportando ${entry.name}", formatSize(n), if (entry.size > 0) n.toFloat() / entry.size else null)
                }
            }
            withContext(Dispatchers.Main) { message = "“${entry.name}” exportado." }
        }
    }

    fun exportToFolder(entry: RpfEntry, treeUri: Uri) {
        val a = current?.archive ?: return
        runTask("Exportando") {
            val scope = coroutineContext
            val target = DocumentTreeTarget(ctx, treeUri)
            when (entry) {
                is RpfFileEntry -> {
                    exporter(a).exportTo(entry, target) { n ->
                        scope.ensureActive()
                        progress("Exportando ${entry.name}", formatSize(n), if (entry.size > 0) n.toFloat() / entry.size else null)
                    }
                    withContext(Dispatchers.Main) { message = "“${entry.name}” exportado." }
                }
                is RpfDirectory -> {
                    val errors = exporter(a).exportDirectory(entry, target) { p: ExportProgress ->
                        scope.ensureActive()
                        progress(
                            "Exportando ${minOf(p.doneFiles + 1, p.totalFiles)} de ${p.totalFiles}",
                            p.currentName,
                            if (p.totalBytes > 0) p.doneBytes.toFloat() / p.totalBytes else null,
                        )
                    }
                    val n = entry.allFiles().size
                    withContext(Dispatchers.Main) {
                        message = if (errors.isEmpty()) {
                            "Exportados $n archivos."
                        } else {
                            "Exportados ${n - errors.size} de $n archivos. Fallaron:\n" +
                                errors.take(15).joinToString("\n") { "• ${it.first}: ${it.second}" } +
                                if (errors.size > 15) "\n…y ${errors.size - 15} más" else ""
                        }
                    }
                }
            }
        }
    }

    // ---------- edición: importar ----------

    private suspend fun copyToCache(uri: Uri, name: String, scope: kotlin.coroutines.CoroutineContext): File {
        val f = File.createTempFile("imp", "_" + name.filter { it.isLetterOrDigit() || it in "._-" }.take(40), importDir)
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo leer $name")
        input.use { i -> BufferedOutputStream(f.outputStream(), 256 * 1024).use { o -> i.copyCount(o) { scope.ensureActive() } } }
        return f
    }

    private fun planItem(a: Rpf7Archive, dir: RpfDirectory, relPath: String, file: File): ImportItem {
        val src = FileSource(file)
        val isRes = RpfResource.parseRsc7(src, a.platform) != null
        val name = relPath.substringAfterLast('/')
        val existing = dir.resolve(relPath)
        var problem: String? = when {
            name.any { it.code < 32 || it.code > 126 } -> "El nombre tiene tildes o símbolos no permitidos"
            existing is RpfDirectory -> "Ya existe una carpeta con ese nombre"
            existing is RpfResource && !isRes -> "Es un recurso: hace falta el archivo en formato RSC7"
            else -> null
        }
        // Una carpeta intermedia con el nombre de un archivo existente.
        var d: RpfEntry? = dir
        for (part in relPath.split('/').dropLast(1)) {
            d = (d as? RpfDirectory)?.get(part) ?: break
            if (d !is RpfDirectory) problem = "“$part” ya existe como archivo"
        }
        val compress = (existing as? RpfRegularFile)?.isCompressed ?: RpfRegularFile.shouldCompress(name, file.length())
        return ImportItem(relPath, file, isRes, existing != null, problem, include = problem == null, compress = compress)
    }

    fun importFiles(uris: List<Uri>) {
        val open = current ?: return
        val dir = currentDir ?: return
        if (uris.isEmpty()) return
        runTask("Preparando archivos") {
            val scope = coroutineContext
            val items = uris.mapIndexed { i, uri ->
                val name = ctx.contentResolver.displayName(uri)
                progress("Preparando archivos", name, i.toFloat() / uris.size)
                planItem(open.archive, dir, name, copyToCache(uri, name, scope))
            }
            withContext(Dispatchers.Main) { importPlan = ImportPlan(dir, items, null) }
        }
    }

    fun importFolder(treeUri: Uri) {
        val open = current ?: return
        val dir = currentDir ?: return
        runTask("Preparando carpeta") {
            val scope = coroutineContext
            val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: throw IOException("Carpeta no válida")
            val rootName = root.name ?: "carpeta"
            val found = ArrayList<Pair<String, DocumentFile>>()
            fun walk(d: DocumentFile, prefix: String) {
                for (f in d.listFiles()) {
                    scope.ensureActive()
                    val n = f.name ?: continue
                    if (f.isDirectory) walk(f, "$prefix/$n") else found.add("$prefix/$n" to f)
                }
            }
            walk(root, rootName)
            if (found.isEmpty()) throw UserMessage("La carpeta “$rootName” está vacía.")
            val items = found.mapIndexed { i, (rel, doc) ->
                progress("Preparando carpeta", rel, i.toFloat() / found.size)
                planItem(open.archive, dir, rel, copyToCache(doc.uri, doc.name ?: "f", scope))
            }
            withContext(Dispatchers.Main) { importPlan = ImportPlan(dir, items, rootName) }
        }
    }

    fun cancelImport() {
        importPlan?.items?.forEach { it.file.delete() }
        importPlan = null
    }

    fun applyImport() {
        val plan = importPlan ?: return
        val a = current?.archive ?: return
        importPlan = null
        val errors = ArrayList<String>()
        var done = 0
        for (item in plan.items) {
            if (!item.include || item.problem != null) {
                item.file.delete()
                continue
            }
            val path = plan.targetPath(item)
            try {
                val folder = a.ensureFolders(plan.dir, path.substringBeforeLast('/', ""))
                a.putFile(folder, path.substringAfterLast('/'), FileSource(item.file), item.compress)
                done++
            } catch (e: Exception) {
                errors.add("• $path: ${e.message}")
            }
        }
        changed()
        message = buildString {
            append(if (done == 1) "1 archivo importado." else "$done archivos importados.")
            append(" Recuerda guardar el RPF.")
            if (errors.isNotEmpty()) append("\n\nNo se pudieron importar:\n").append(errors.take(15).joinToString("\n"))
        }
    }

    /** Reemplaza el contenido de un archivo por el que elija el usuario. */
    fun replaceFile(entry: RpfFileEntry, uri: Uri) {
        val a = current?.archive ?: return
        val dir = entry.parent ?: return
        runTask("Reemplazando ${entry.name}") {
            val scope = coroutineContext
            val f = copyToCache(uri, entry.name, scope)
            withContext(Dispatchers.Main) {
                try {
                    val compress = (entry as? RpfRegularFile)?.isCompressed
                    a.putFile(dir, entry.name, FileSource(f), compress)
                    changed()
                    message = "“${entry.name}” reemplazado. Recuerda guardar el RPF."
                } catch (e: Exception) {
                    f.delete()
                    message = e.message
                }
            }
        }
    }

    // ---------- edición: carpetas, renombrar, borrar ----------

    fun toggleMark(e: RpfEntry) {
        if (marked.any { it === e }) marked.removeAll { it === e } else marked.add(e)
    }

    fun createFolder(name: String) {
        val a = current?.archive ?: return
        val dir = currentDir ?: return
        creatingFolder = false
        try {
            a.createFolder(dir, name.trim())
            changed()
        } catch (e: Exception) {
            message = e.message
        }
    }

    fun rename(entry: RpfEntry, newName: String) {
        val a = current?.archive ?: return
        renaming = null
        try {
            a.rename(entry, newName.trim())
            marked.clear()
            changed()
        } catch (e: Exception) {
            message = e.message
        }
    }

    fun askDelete(entries: List<RpfEntry>) {
        if (entries.isEmpty()) return
        val files = entries.sumOf { if (it is RpfDirectory) it.allFiles().size else 1 }
        val what = if (entries.size == 1) "“${entries[0].name}”" else "${entries.size} elementos"
        confirm = ConfirmRequest(
            "Borrar",
            "¿Borrar $what ($files archivo${if (files == 1) "" else "s"})? Se aplicará al guardar el RPF.",
            "Borrar",
        ) { deleteNow(entries) }
    }

    private fun deleteNow(entries: List<RpfEntry>) {
        val a = current?.archive ?: return
        entries.forEach { e -> runCatching { a.delete(e) } }
        val dir = currentDir
        if (dir != null && entries.any { it is RpfDirectory && (it === dir || it.isAncestorOf(dir)) }) {
            currentDir = a.root
        }
        marked.clear()
        selected = null
        changed()
    }

    // ---------- guardar ----------

    private fun saveFile(name: String) = File(ctx.cacheDir, name)

    private suspend fun writeVerified(a: Rpf7Archive, file: File, title: String) {
        val scope = coroutineContext
        val key = keys[a.platform] ?: throw UserMessage("Falta la clave de ${a.platform.displayName}")
        RandomAccessFile(file, "rw").use { raf ->
            Rpf7Writer.write(a, raf.channel) { p: SaveProgress ->
                scope.ensureActive()
                progress(title, p.currentName, if (p.totalBytes > 0) p.doneBytes.toFloat() / p.totalBytes else null)
            }
        }
        progress("Comprobando el RPF guardado", "", null)
        val problems = Rpf7Writer.verify(a, FileSource(file), key) { p ->
            scope.ensureActive()
            progress("Comprobando el RPF guardado", p.currentName, if (p.totalBytes > 0) p.doneBytes.toFloat() / p.totalBytes else null)
        }
        if (problems.isNotEmpty()) {
            file.delete()
            throw UserMessage("No se guardó porque la comprobación falló:\n" + problems.take(10).joinToString("\n"))
        }
    }

    private suspend fun copyFileToUri(file: File, uri: Uri, title: String) {
        val scope = coroutineContext
        val total = file.length()
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No se pudo abrir el destino")
        BufferedOutputStream(out, 1 shl 20).use { o ->
            file.inputStream().use { i ->
                i.copyCount(o) { n ->
                    scope.ensureActive()
                    progress(title, formatSize(n), if (total > 0) n.toFloat() / total else null)
                }
            }
        }
    }

    /** "Guardar": encima del archivo original (o en el RPF padre si es un RPF anidado). */
    fun save() {
        val open = current ?: return
        when {
            open.isNested -> applyToParent()
            open.uri == null -> message = "Este RPF no tiene archivo de origen. Usa «Guardar como»."
            else -> saveTo(open.uri, overwriteOriginal = true)
        }
    }

    fun saveAs(uri: Uri) = saveTo(uri, overwriteOriginal = false)

    private fun saveTo(uri: Uri, overwriteOriginal: Boolean) {
        val open = current ?: return
        if (open.isNested || stack.size != 1) return
        runTask("Guardando") {
            val a = open.archive
            val temp = saveFile("guardando.rpf")
            writeVerified(a, temp, "Guardando ${open.title}")
            val name = if (overwriteOriginal) open.title else ctx.contentResolver.displayName(uri)
            withContext(Dispatchers.Main + NonCancellable) {
                stack.clear()
                open.close() // libera el original antes de escribir encima
                currentDir = null
            }
            try {
                withContext(NonCancellable) { copyFileToUri(temp, uri, "Escribiendo $name") }
            } catch (e: Exception) {
                // No se pudo escribir: se abre la copia guardada para no perder nada.
                val kept = saveFile("recuperado_${System.currentTimeMillis()}.rpf")
                temp.renameTo(kept)
                val a2 = openArchive(fileDataSource(kept), name)
                withContext(Dispatchers.Main + NonCancellable) {
                    push(OpenArchive(a2, name, kept, null, null))
                    message = "No se pudo escribir en “$name”: ${e.message}\n\n" +
                        "Los cambios están a salvo en una copia temporal. Usa «Guardar como» para elegir otro sitio."
                }
                return@runTask
            }
            temp.delete()
            val src = ctx.contentResolver.openDataSource(uri)
            val reopened = try {
                openArchive(src, name)
            } catch (e: Throwable) {
                src.close()
                throw e
            }
            withContext(Dispatchers.Main + NonCancellable) {
                cleanImports()
                push(OpenArchive(reopened, name, null, uri, null))
                message = "“$name” guardado correctamente."
            }
        }
    }

    /** Guarda un RPF anidado y lo mete en su RPF padre (que queda con cambios pendientes). */
    fun applyToParent() {
        val open = current ?: return
        val entry = open.parentEntry ?: return
        val parentOpen = stack.getOrNull(stack.size - 2) ?: return
        val dir = entry.parent ?: return
        runTask("Aplicando cambios") {
            val out = File.createTempFile("nested_out", ".rpf", importDir)
            try {
                writeVerified(open.archive, out, "Guardando ${open.title}")
            } catch (e: Throwable) {
                out.delete()
                throw e
            }
            withContext(Dispatchers.Main + NonCancellable) {
                parentOpen.archive.putFile(dir, entry.name, FileSource(out), compress = false)
                stack.removeAt(stack.lastIndex).close()
                currentDir = dir
                marked.clear()
                changed()
                message = "Cambios aplicados a “${entry.name}” dentro de “${parentOpen.title}”. " +
                    "Guarda ese RPF para conservarlos."
            }
        }
    }

    // ---------- ver contenido ----------

    fun preview(entry: RpfFileEntry) {
        val a = current?.archive ?: return
        runTask("Leyendo ${entry.name}") {
            val limit = 256 * 1024
            val bytes = a.openContent(entry).use { input ->
                val buf = ByteArray(minOf(entry.size, limit.toLong()).toInt())
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                buf.copyOf(n)
            }
            val text = if (looksLikeText(bytes)) {
                String(bytes, Charsets.UTF_8) + if (entry.size > limit) "\n\n[… vista previa limitada a 256 KB]" else ""
            } else {
                hexDump(bytes, 4096) + if (bytes.size > 4096) "\n[… solo los primeros 4 KB]" else ""
            }
            withContext(Dispatchers.Main) { textPreview = entry.name to text }
        }
    }

    // ---------- audio AWC ----------

    fun openAwc(entry: RpfFileEntry) {
        val a = current?.archive ?: return
        runTask("Abriendo audio ${entry.name}") {
            val tmp = File(ctx.cacheDir, "audio.awc")
            withContext(Dispatchers.Main) { closeAwc() }
            extractTo(a, entry, tmp, "Leyendo ${entry.name}")
            val src = fileDataSource(tmp)
            try {
                val awc = AwcFile(src, a.platform)
                withContext(Dispatchers.Main) { awcState = AwcState(entry, awc, a.platform, src, tmp) }
            } catch (e: Throwable) {
                src.close()
                throw UserMessage("No se pudo leer el AWC: ${e.message}")
            }
        }
    }

    fun closeAwc() {
        stopAudio()
        awcState?.let {
            runCatching { it.source.close() }
            it.tempFile.delete()
        }
        awcState = null
    }

    private suspend fun decodeToWav(st: AwcState, track: AwcTrack, out: java.io.OutputStream, title: String) {
        val scope = coroutineContext
        var written = 0L
        val total = track.sampleCount * 2
        val counting = object : java.io.FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
                written += len
                progress(title, "Pista ${track.id}", if (total > 0) written.toFloat() / total else null)
            }
        }
        WavWriter.writeTrack(track, st.platform, NativeAudio.factory, counting) { scope.ensureActive() }
        counting.flush()
    }

    fun playTrack(track: AwcTrack) {
        val st = awcState ?: return
        stopAudio()
        runTask("Decodificando audio") {
            val wav = File(ctx.cacheDir, "play.wav")
            BufferedOutputStream(wav.outputStream(), 256 * 1024).use { decodeToWav(st, track, it, "Decodificando audio") }
            withContext(Dispatchers.Main) {
                val mp = MediaPlayer()
                mp.setDataSource(wav.absolutePath)
                mp.setOnCompletionListener { stopAudio() }
                mp.prepare()
                mp.start()
                player = mp
                playingTrack = track.id
            }
        }
    }

    fun stopAudio() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playingTrack = null
    }

    fun exportAwcToFolder(treeUri: Uri) {
        val st = awcState ?: return
        runTask("Exportando WAV") {
            val target = DocumentTreeTarget(ctx, treeUri)
            for (t in st.awc.tracks) {
                val name = "${st.entry.name}.${t.id}.wav"
                BufferedOutputStream(target.create(name), 256 * 1024).use { decodeToWav(st, t, it, "Exportando $name") }
            }
            withContext(Dispatchers.Main) { message = "Exportadas ${st.awc.tracks.size} pistas WAV." }
        }
    }

    override fun onCleared() {
        closeAwc()
        closeAllNow()
        super.onCleared()
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun looksLikeText(b: ByteArray): Boolean {
    if (b.isEmpty()) return true
    val sample = b.size.coerceAtMost(4096)
    var bad = 0
    for (i in 0 until sample) {
        val c = b[i].toInt() and 0xFF
        if (c == 0) return false
        if (c < 9 || (c in 14..31)) bad++
    }
    return bad * 20 < sample
}

private fun hexDump(b: ByteArray, max: Int): String {
    val sb = StringBuilder()
    val n = minOf(b.size, max)
    var i = 0
    while (i < n) {
        sb.append("%08X  ".format(i))
        for (j in 0 until 16) {
            if (i + j < n) sb.append("%02X ".format(b[i + j])) else sb.append("   ")
        }
        sb.append(' ')
        for (j in 0 until 16) {
            if (i + j >= n) break
            val c = b[i + j].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        sb.append('\n')
        i += 16
    }
    return sb.toString()
}
