package com.bdfiles

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bdfiles.core.Archives
import com.bdfiles.core.CancelledException
import com.bdfiles.core.Conflict
import com.bdfiles.core.FileItem
import com.bdfiles.core.FileOperations
import com.bdfiles.core.FileSorting
import com.bdfiles.core.FileSource
import com.bdfiles.core.LocalSource
import com.bdfiles.core.OpProgress
import com.bdfiles.core.OpResult
import com.bdfiles.core.ShizukuBridge
import com.bdfiles.core.SafSource
import com.bdfiles.core.ShizukuSource
import com.bdfiles.core.StorageVolumes
import com.bdfiles.core.UserError
import com.bdfiles.core.joinPath
import com.bdfiles.core.nameOf
import com.bdfiles.core.parentOf
import com.bdfiles.data.Prefs
import com.bdfiles.data.Shortcut
import com.bdfiles.net.FtpServerConfig
import com.bdfiles.net.FtpSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Una pestaña abierta. */
class Tab(sourceId: String, path: String, title: String, atHome: Boolean = true) {
    var sourceId by mutableStateOf(sourceId)
    var path by mutableStateOf(path)
    var title by mutableStateOf(title)
    /** true si esta pestaña está en la pantalla de inicio en vez de en una carpeta. */
    var atHome by mutableStateOf(atHome)
    var items = mutableStateListOf<FileItem>()
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** Posición de la lista al volver de una subcarpeta. */
    val scrollMemory = HashMap<String, Int>()
}

/** Lo que se ha copiado o cortado. */
class Clipboard(val sourceId: String, val items: List<FileItem>, val isMove: Boolean)

/** Pantallas aparte del explorador. */
enum class Screen { HOME, BROWSER, SHORTCUTS, CLOUD, SETTINGS, APP_PERMISSIONS }

class ConfirmRequest(
    val title: String,
    val text: String,
    val confirmLabel: String,
    val danger: Boolean = false,
    val onConfirm: () -> Unit,
)

/** Datos del diálogo de borrado: cancelar / papelera / borrar definitivamente. */
class DeleteRequest(val items: List<FileItem>, val canUseTrash: Boolean)

class NameRequest(
    val title: String,
    val initial: String,
    val confirmLabel: String,
    val hint: String = "",
    val onConfirm: (String) -> Unit,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    val prefs = Prefs(ctx)

    // ---------------------------------------------------------- orígenes

    private val sources = HashMap<String, FileSource>()

    val internalRoot: String = Environment.getExternalStorageDirectory().absolutePath

    var volumes by mutableStateOf(emptyList<StorageVolumes.Volume>())
        private set

    /** Volumen que está esperando a que el usuario conceda el permiso. */
    var pendingVolume: StorageVolumes.Volume? = null

    fun source(id: String): FileSource = sources.getOrPut(id) {
        when {
            id == "local" -> LocalSource("local", "Almacenamiento interno", internalRoot)
            id == "shizuku" -> ShizukuSource()
            id.startsWith("vol:") -> {
                val path = id.removePrefix("vol:")
                val v = volumes.firstOrNull { it.path == path }
                LocalSource(id, v?.title ?: nameOf(path), path)
            }
            id.startsWith("saf:") -> {
                val uri = Uri.parse(id.removePrefix("saf:"))
                val vol = volumes.firstOrNull { prefs.grantedVolumes[it.key] == uri.toString() }
                SafSource(ctx, uri, vol?.title ?: StorageVolumes.titleFromUri(uri), id)
            }
            id.startsWith("ftp:") -> {
                val name = id.removePrefix("ftp:")
                val cfg = prefs.servers.firstOrNull { it.name == name }
                    ?: throw UserError("No está guardado el servidor “$name”")
                FtpSource(cfg)
            }
            else -> LocalSource("local", "Almacenamiento interno", internalRoot)
        }
    }

    // ---------------------------------------------------------- estado general

    var screen by mutableStateOf(Screen.HOME)
    val tabs = mutableStateListOf<Tab>()
    var activeTabIndex by mutableIntStateOf(0)
        private set

    val activeTab: Tab? get() = tabs.getOrNull(activeTabIndex)

    val selected = mutableStateListOf<FileItem>()
    var clipboard by mutableStateOf<Clipboard?>(null)
    var searchQuery by mutableStateOf("")
    var searching by mutableStateOf(false)
    var searchResults = mutableStateListOf<FileItem>()
    var searchRunning by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
    var confirm by mutableStateOf<ConfirmRequest?>(null)
    var deleteRequest by mutableStateOf<DeleteRequest?>(null)
    var nameRequest by mutableStateOf<NameRequest?>(null)
    var infoItem by mutableStateOf<FileItem?>(null)
    var itemMenu by mutableStateOf<FileItem?>(null)
    var archiveToBrowse by mutableStateOf<FileItem?>(null)
    var archiveEntries by mutableStateOf<List<com.bdfiles.core.ArchiveEntry>?>(null)
    var archiveError by mutableStateOf<String?>(null)
    var result by mutableStateOf<OpResult?>(null)

    /** Refresco de la interfaz cuando cambian favoritos o anclados. */
    var uiRevision by mutableIntStateOf(0)
        private set

    private val _progress = MutableStateFlow<OpProgress?>(null)
    val progress: StateFlow<OpProgress?> = _progress
    private var job: Job? = null
    private var cancelRequested = false

    var shizukuReady by mutableStateOf(false)
        private set

    init {
        volumes = runCatching { StorageVolumes.list(ctx) }.getOrDefault(emptyList())
        restoreTabs()
        refreshShizuku()
    }

    /** Lo que hay que hacer para abrir un almacenamiento. */
    sealed class VolumeAction {
        class Open(val sourceId: String, val path: String) : VolumeAction()
        class AskPermission(val volume: StorageVolumes.Volume) : VolumeAction()
    }

    fun refreshVolumes() {
        volumes = runCatching { StorageVolumes.list(ctx) }.getOrDefault(emptyList())
        bump()
    }

    /** Dice si un almacenamiento se puede abrir ya o hay que pedir permiso antes. */
    fun volumeAction(v: StorageVolumes.Volume): VolumeAction {
        val granted = prefs.grantedVolumes[v.key]
        if (granted != null && hasUriPermission(granted)) {
            return VolumeAction.Open("saf:$granted", "/")
        }
        if (v.path != null) {
            val sourceId = if (v.path == internalRoot) "local" else "vol:${v.path}"
            return VolumeAction.Open(sourceId, v.path)
        }
        return VolumeAction.AskPermission(v)
    }

    fun openVolume(v: StorageVolumes.Volume, onNeedPermission: (StorageVolumes.Volume) -> Unit) {
        when (val a = volumeAction(v)) {
            is VolumeAction.Open -> navigate(a.path, a.sourceId)
            is VolumeAction.AskPermission -> onNeedPermission(a.volume)
        }
    }

    private fun hasUriPermission(uri: String): Boolean =
        ctx.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uri && it.isReadPermission
        }

    /** Guarda el acceso que acaba de conceder el usuario y entra en el almacenamiento. */
    fun onVolumeAccessGranted(uri: Uri, volumeKey: String?) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            message = "Android no dejó guardar el permiso: ${it.message}"
            return
        }
        val key = volumeKey ?: StorageVolumes.titleFromUri(uri)
        prefs.grantVolume(key, uri.toString())
        refreshVolumes()
        sources.remove("saf:$uri")?.let { runCatching { it.close() } }
        navigate("/", "saf:$uri")
        message = "Acceso concedido. Ya puedes entrar y copiar archivos."
    }

    fun forgetVolume(key: String) {
        val uri = prefs.grantedVolumes[key]
        prefs.forgetVolume(key)
        if (uri != null) sources.remove("saf:$uri")?.let { runCatching { it.close() } }
        refreshVolumes()
    }

    /** Pasa a la pestaña anterior o siguiente, dando la vuelta al llegar al final. */
    fun nextTab() {
        if (tabs.size <= 1) return
        selectTab((activeTabIndex + 1) % tabs.size)
    }

    fun prevTab() {
        if (tabs.size <= 1) return
        selectTab((activeTabIndex - 1 + tabs.size) % tabs.size)
    }

    fun refreshShizuku() {
        shizukuReady = ShizukuBridge.isReady
    }

    private fun bump() {
        uiRevision++
    }

    // ---------------------------------------------------------- pestañas

    private fun restoreTabs() {
        val saved = prefs.openTabs
        val count = if (prefs.tabsEnabled) prefs.maxTabs else 1
        for (k in 0 until count) {
            val (sid, path) = saved.getOrNull(k) ?: ("local" to internalRoot)
            val ok = sid == "local" || sid.startsWith("vol:") || sid == "shizuku"
            tabs.add(
                if (ok) Tab(sid, path, nameOf(path)) else Tab("local", internalRoot, "Inicio"),
            )
        }
        activeTabIndex = 0
        reload()
    }

    private fun saveTabs() {
        prefs.openTabs = tabs.map { it.sourceId to it.path }
    }

    /** Ajusta cuántas pestañas hay, sin perder las que ya estaban. */
    fun applyTabCount(count: Int) {
        val n = count.coerceIn(1, Prefs.MAX_TABS_LIMIT)
        while (tabs.size > n) tabs.removeAt(tabs.lastIndex)
        while (tabs.size < n) tabs.add(Tab("local", internalRoot, "Inicio"))
        if (activeTabIndex >= tabs.size) activeTabIndex = tabs.lastIndex
        prefs.maxTabs = n
        saveTabs()
        bump()
    }

    /** Texto que se enseña en la pestaña: dónde está. */
    fun tabLabel(tab: Tab): String = when {
        tab.atHome -> "Inicio"
        tab.path.trimEnd('/') == runCatching { source(tab.sourceId).rootPath }.getOrNull()?.trimEnd('/') ->
            runCatching { source(tab.sourceId).title }.getOrDefault("Carpeta")
        else -> nameOf(tab.path)
    }

    fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        selected.clear()
        clearSearch()
        val tab = tabs[index]
        screen = if (tab.atHome) Screen.HOME else Screen.BROWSER
        if (!tab.atHome) reload()
    }

    /** Abre algo en la primera pestaña que esté en el inicio, o en la siguiente. */
    fun newTab(sourceId: String = "local", path: String = internalRoot) {
        val free = tabs.indexOfFirst { it.atHome && it !== activeTab }
        val index = when {
            free >= 0 -> free
            tabs.size < prefs.maxTabs -> {
                tabs.add(Tab("local", internalRoot, "Inicio"))
                prefs.maxTabs = tabs.size
                tabs.lastIndex
            }
            else -> {
                message = "Ya tienes las ${tabs.size} pestañas ocupadas. " +
                    "Puedes subir el número en Ajustes → Interfaz y estilo."
                return
            }
        }
        val tab = tabs[index]
        tab.sourceId = sourceId
        tab.path = path
        tab.atHome = false
        activeTabIndex = index
        selected.clear()
        screen = Screen.BROWSER
        saveTabs()
        reload()
    }

    /** Devuelve una pestaña al inicio (no la quita: las pestañas son fijas). */
    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        tab.atHome = true
        tab.sourceId = "local"
        tab.path = internalRoot
        if (index == activeTabIndex) screen = Screen.HOME
        selected.clear()
        saveTabs()
        bump()
    }

    // ---------------------------------------------------------- navegación

    fun reload() {
        val tab = activeTab ?: return
        tab.loading = true
        tab.error = null
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { source(tab.sourceId).list(tab.path) }
            }
            tab.loading = false
            list.onSuccess { items ->
                tab.items.clear()
                tab.items.addAll(visible(items))
                tab.title = nameOf(tab.path).ifEmpty { source(tab.sourceId).title }
            }.onFailure { e ->
                tab.items.clear()
                tab.error = e.message ?: e.javaClass.simpleName
            }
            saveTabs()
        }
    }

    private fun visible(items: List<FileItem>): List<FileItem> {
        val filtered = if (prefs.showHidden) items else items.filterNot { it.isHidden }
        return FileSorting.sort(filtered, prefs.sortBy, prefs.sortDescending, prefs.foldersFirst, prefs.pinned)
    }

    /** Vuelve a ordenar lo que ya está cargado (al cambiar un ajuste). */
    fun resort() {
        val tab = activeTab ?: return
        val current = tab.items.toList()
        tab.items.clear()
        tab.items.addAll(FileSorting.sort(current, prefs.sortBy, prefs.sortDescending, prefs.foldersFirst, prefs.pinned))
        bump()
    }

    fun navigate(path: String, sourceId: String = activeTab?.sourceId ?: "local") {
        val tab = activeTab ?: return
        tab.sourceId = sourceId
        tab.path = path
        tab.atHome = false
        selected.clear()
        clearSearch()
        screen = Screen.BROWSER
        reload()
    }

    /**
     * Vuelve atrás: quita la selección, cierra la búsqueda, sube una carpeta
     * y, si ya estamos en la raíz, va a la pantalla de inicio.
     * Devuelve false solo si no quedaba nada que hacer.
     */
    fun goUp(): Boolean {
        val tab = activeTab ?: return false
        if (selected.isNotEmpty()) {
            selected.clear()
            return true
        }
        if (searching) {
            clearSearch()
            return true
        }
        val root = source(tab.sourceId).rootPath
        if (tab.path.trimEnd('/') == root.trimEnd('/')) {
            tab.atHome = true
            screen = Screen.HOME
            return true
        }
        val parent = parentOf(tab.path) ?: run {
            tab.atHome = true
            screen = Screen.HOME
            return true
        }
        tab.path = parent
        reload()
        return true
    }

    fun open(item: FileItem) {
        if (item.isDir) {
            navigate(item.path, item.sourceId)
            return
        }
        if (Archives.isArchive(item.name) && item.sourceId == "local") {
            browseArchive(item)
            return
        }
        openExternally(item)
    }

    fun openExternally(item: FileItem) {
        if (item.sourceId != "local" && !item.sourceId.startsWith("vol:")) {
            message = "Para abrir este archivo, cópialo antes al móvil."
            return
        }
        runCatching {
            val file = File(item.path)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(item.extension))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, "Abrir con").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            message = "No hay ninguna app que pueda abrir “${item.name}”."
        }
    }

    fun share(items: List<FileItem>) {
        val locals = items.filter { !it.isDir && (it.sourceId == "local" || it.sourceId.startsWith("vol:")) }
        if (locals.isEmpty()) {
            message = "Solo se pueden compartir archivos del móvil."
            return
        }
        runCatching {
            val uris = ArrayList<Uri>(
                locals.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(it.path)) },
            )
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeOf(locals[0].extension)
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { message = "No se pudo compartir: ${it.message}" }
    }

    private fun mimeOf(ext: String): String = when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> "image/*"
        "mp4", "mkv", "avi", "mov", "3gp", "webm" -> "video/*"
        "mp3", "wav", "ogg", "flac", "m4a", "aac" -> "audio/*"
        "pdf" -> "application/pdf"
        "txt", "log", "xml", "json", "md", "ini", "cfg", "dat", "meta" -> "text/plain"
        "apk" -> "application/vnd.android.package-archive"
        "zip", "jar" -> "application/zip"
        else -> "*/*"
    }

    // ---------------------------------------------------------- selección

    fun toggleSelect(item: FileItem) {
        val i = selected.indexOfFirst { it.key == item.key }
        if (i >= 0) selected.removeAt(i) else selected.add(item)
    }

    fun isSelected(item: FileItem) = selected.any { it.key == item.key }

    fun selectAll() {
        val tab = activeTab ?: return
        val list = if (searching) searchResults else tab.items
        selected.clear()
        selected.addAll(list)
    }

    fun clearSelection() = selected.clear()

    // ---------------------------------------------------------- favoritos y anclados

    fun toggleFavorite(item: FileItem) {
        val added = prefs.toggleFavorite(Shortcut(item.name, item.path, item.sourceId, if (item.isDir) "📁" else "📄"))
        message = if (added) "“${item.name}” añadido a favoritos." else "“${item.name}” quitado de favoritos."
        bump()
    }

    fun isFavorite(item: FileItem) = prefs.isFavorite(item.path, item.sourceId)

    fun togglePinned(item: FileItem) {
        val added = prefs.togglePinned(item.key)
        message = if (added) "“${item.name}” anclado arriba del todo." else "“${item.name}” desanclado."
        resort()
    }

    fun isPinned(item: FileItem) = item.key in prefs.pinned

    // ---------------------------------------------------------- tareas

    private fun runTask(block: suspend CoroutineScope.() -> Unit) {
        if (job?.isActive == true) {
            message = "Espera a que termine la tarea que hay en marcha."
            return
        }
        cancelRequested = false
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancelledException) {
                withContext(Dispatchers.Main + NonCancellable) { message = "Operación cancelada." }
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main + NonCancellable) { message = "No hay memoria suficiente." }
            } catch (e: Exception) {
                val text = e.message ?: e.javaClass.simpleName
                withContext(Dispatchers.Main + NonCancellable) { message = text }
            } finally {
                _progress.value = null
                withContext(Dispatchers.Main + NonCancellable) { reload() }
            }
        }
    }

    fun cancelTask() {
        cancelRequested = true
    }

    private fun operations() = FileOperations(
        report = { _progress.value = it },
        isCancelled = { cancelRequested },
        verify = prefs.verifyCopies,
    )

    // ---------------------------------------------------------- copiar, mover, pegar

    fun copySelection(move: Boolean) {
        val tab = activeTab ?: return
        if (selected.isEmpty()) return
        clipboard = Clipboard(tab.sourceId, selected.toList(), move)
        message = if (move) {
            "${selected.size} elemento(s) cortados. Entra en la carpeta de destino y pulsa «Pegar»."
        } else {
            "${selected.size} elemento(s) copiados. Entra en la carpeta de destino y pulsa «Pegar»."
        }
        selected.clear()
    }

    fun paste() {
        val clip = clipboard ?: return
        val tab = activeTab ?: return
        val dest = tab.path
        if (clip.items.any { it.isDir && dest.startsWith(it.path) && clip.sourceId == tab.sourceId }) {
            message = "No se puede pegar una carpeta dentro de sí misma."
            return
        }
        runTask {
            val r = operations().copy(
                from = source(clip.sourceId),
                items = clip.items,
                to = source(tab.sourceId),
                destDir = dest,
                move = clip.isMove,
                conflict = Conflict.KEEP_BOTH,
            )
            withContext(Dispatchers.Main + NonCancellable) {
                if (clip.isMove) clipboard = null
                result = r
            }
        }
    }

    // ---------------------------------------------------------- borrar

    fun askDelete(items: List<FileItem>) {
        if (items.isEmpty()) return
        val local = items.all { it.sourceId == "local" || it.sourceId.startsWith("vol:") }
        deleteRequest = DeleteRequest(items, canUseTrash = local && prefs.useTrash)
    }

    fun doDelete(items: List<FileItem>, toTrash: Boolean) {
        deleteRequest = null
        val tab = activeTab ?: return
        runTask {
            val src = source(tab.sourceId)
            val r = if (toTrash) {
                operations().moveToTrash(src, items, joinPath(src.rootPath, TRASH_DIR))
            } else {
                operations().delete(src, items)
            }
            withContext(Dispatchers.Main + NonCancellable) {
                selected.clear()
                result = r
            }
        }
    }

    fun emptyTrash() {
        val src = source("local")
        val trash = joinPath(src.rootPath, TRASH_DIR)
        val items = runCatching { src.list(trash) }.getOrDefault(emptyList())
        if (items.isEmpty()) {
            message = "La papelera ya está vacía."
            return
        }
        confirm = ConfirmRequest(
            "Vaciar papelera",
            "Se borrarán ${items.size} elemento(s) para siempre.",
            "Vaciar",
            danger = true,
        ) {
            runTask {
                val r = operations().delete(src, items)
                withContext(Dispatchers.Main + NonCancellable) { result = r }
            }
        }
    }

    fun openTrash() {
        val src = source("local")
        val trash = joinPath(src.rootPath, TRASH_DIR)
        runCatching { src.mkdirs(trash) }
        navigate(trash, "local")
    }

    // ---------------------------------------------------------- crear y renombrar

    fun askCreateFolder() {
        val tab = activeTab ?: return
        nameRequest = NameRequest(
            "Nueva carpeta", "", "Crear",
            hint = "Se creará en ${nameOf(tab.path)}",
        ) { name ->
            nameRequest = null
            runCatching { source(tab.sourceId).mkdirs(joinPath(tab.path, name.trim())) }
                .onSuccess { reload(); message = "Carpeta “$name” creada." }
                .onFailure { message = it.message }
        }
    }

    fun askCreateFile() {
        val tab = activeTab ?: return
        nameRequest = NameRequest(
            "Nuevo archivo", "", "Crear",
            hint = "Por ejemplo: notas.txt",
        ) { name ->
            nameRequest = null
            runCatching { source(tab.sourceId).createFile(joinPath(tab.path, name.trim())) }
                .onSuccess { reload(); message = "Archivo “$name” creado." }
                .onFailure { message = it.message }
        }
    }

    fun askRename(item: FileItem) {
        nameRequest = NameRequest("Renombrar", item.name, "Renombrar") { name ->
            nameRequest = null
            val clean = name.trim()
            if (clean.isEmpty() || clean == item.name) return@NameRequest
            runCatching { source(item.sourceId).rename(item.path, clean) }
                .onSuccess { selected.clear(); reload(); message = "Renombrado a “$clean”." }
                .onFailure { message = it.message }
        }
    }

    // ---------------------------------------------------------- comprimir y extraer

    fun askCompress(items: List<FileItem>) {
        val tab = activeTab ?: return
        if (items.isEmpty()) return
        if (items.any { it.sourceId != "local" && !it.sourceId.startsWith("vol:") }) {
            message = "Solo se puede comprimir lo que está en el móvil."
            return
        }
        val suggested = if (items.size == 1) "${items[0].name.substringBeforeLast('.', items[0].name)}.zip"
        else "${nameOf(tab.path)}.zip"
        nameRequest = NameRequest("Comprimir en ZIP", suggested, "Comprimir", "Se guardará en esta carpeta") { name ->
            nameRequest = null
            val target = File(tab.path, if (name.endsWith(".zip", true)) name else "$name.zip")
            runTask {
                val r = Archives.createZip(
                    items.map { File(it.path) }, target,
                    report = { _progress.value = it },
                    isCancelled = { cancelRequested },
                )
                withContext(Dispatchers.Main + NonCancellable) {
                    selected.clear()
                    result = r
                }
            }
        }
    }

    fun extractArchive(item: FileItem, intoNewFolder: Boolean) {
        val tab = activeTab ?: return
        closeArchive()
        val base = item.name.substringBeforeLast('.', item.name)
        val dest = if (intoNewFolder) File(tab.path, base) else File(tab.path)
        runTask {
            val r = Archives.extract(
                File(item.path), dest, null,
                report = { _progress.value = it },
                isCancelled = { cancelRequested },
            )
            withContext(Dispatchers.Main + NonCancellable) { result = r }
        }
    }

    fun browseArchive(item: FileItem) {
        archiveToBrowse = item
        archiveEntries = null
        archiveError = null
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { Archives.listEntries(File(item.path)) }
            withContext(Dispatchers.Main) {
                r.onSuccess { archiveEntries = it }.onFailure { archiveError = it.message }
            }
        }
    }

    fun closeArchive() {
        archiveToBrowse = null
        archiveEntries = null
        archiveError = null
    }

    fun addToArchive(archive: FileItem, files: List<FileItem>) {
        runTask {
            val r = Archives.addToZip(
                File(archive.path), files.map { File(it.path) }, "",
                report = { _progress.value = it }, isCancelled = { cancelRequested },
            )
            withContext(Dispatchers.Main + NonCancellable) { result = r }
        }
    }

    fun removeFromArchive(archive: FileItem, paths: Set<String>) {
        closeArchive()
        runTask {
            val r = Archives.removeFromZip(
                File(archive.path), paths,
                report = { _progress.value = it }, isCancelled = { cancelRequested },
            )
            withContext(Dispatchers.Main + NonCancellable) { result = r }
        }
    }

    fun extractOne(archive: FileItem, entryPath: String) {
        val tab = activeTab ?: return
        closeArchive()
        runTask {
            val target = File(tab.path, entryPath.substringAfterLast('/'))
            _progress.value = OpProgress("Extrayendo", entryPath, indeterminate = true)
            Archives.extractSingle(File(archive.path), entryPath, target)
            withContext(Dispatchers.Main + NonCancellable) {
                result = OpResult("Extraído", "“${target.name}” sacado a esta carpeta.")
            }
        }
    }

    // ---------------------------------------------------------- búsqueda

    fun startSearch() {
        searching = true
    }

    fun clearSearch() {
        searching = false
        searchQuery = ""
        searchResults.clear()
        searchJob?.cancel()
        searchRunning = false
    }

    private var searchJob: Job? = null

    /** Busca por todo el almacenamiento, no solo en la carpeta actual. */
    fun runSearch(wholeDevice: Boolean) {
        val tab = activeTab ?: return
        val q = searchQuery.trim()
        if (q.length < 2) {
            message = "Escribe al menos 2 letras para buscar."
            return
        }
        searchJob?.cancel()
        searchResults.clear()
        searchRunning = true
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val src = source(tab.sourceId)
            val roots = if (wholeDevice) {
                buildList {
                    add(src.rootPath)
                    if (tab.sourceId == "local") {
                        // Las memorias que sí se pueden leer por ruta.
                        volumes.forEach { v ->
                            val p = v.path
                            if (p != null && p != internalRoot) add(p)
                        }
                    }
                }
            } else {
                listOf(tab.path)
            }
            val found = ArrayList<FileItem>()
            val seen = HashSet<String>()
            try {
                for (root in roots) {
                    walkSearch(src, root, q.lowercase(), found, seen, 0)
                }
            } catch (_: CancelledException) {
                // búsqueda parada: se queda lo encontrado
            }
            withContext(Dispatchers.Main) {
                searchResults.clear()
                searchResults.addAll(found.take(2000))
                searchRunning = false
            }
        }
    }

    private suspend fun walkSearch(
        src: FileSource,
        path: String,
        query: String,
        out: ArrayList<FileItem>,
        seen: HashSet<String>,
        depth: Int,
    ) {
        if (depth > 24 || out.size >= 2000) return
        if (searchJob?.isActive != true) throw CancelledException()
        val items = runCatching { src.list(path) }.getOrNull() ?: return
        for (item in items) {
            if (item.isHidden && !prefs.showHidden) continue
            if (item.name.lowercase().contains(query) && seen.add(item.path)) {
                out.add(item)
                if (out.size % 20 == 0) {
                    val snapshot = out.toList()
                    withContext(Dispatchers.Main) {
                        searchResults.clear()
                        searchResults.addAll(snapshot)
                    }
                }
            }
        }
        for (item in items) {
            if (item.isDir && !(item.isHidden && !prefs.showHidden)) {
                walkSearch(src, item.path, query, out, seen, depth + 1)
            }
        }
    }

    // ---------------------------------------------------------- red

    /** Detalle de la última conexión FTP que falló, para poder enseñarlo. */
    var lastFtpLog by mutableStateOf<String?>(null)

    fun connectServer(config: FtpServerConfig) {
        runTask {
            _progress.value = OpProgress("Conectando", "${config.host}:${config.port}", indeterminate = true)
            // Si ya había una conexión con ese nombre, se cierra antes.
            sources.remove(config.id)?.let { runCatching { it.close() } }
            val s = FtpSource(config)
            val info = try {
                s.testConnection()
            } catch (e: Exception) {
                val log = runCatching { s.connectionLog }.getOrDefault("")
                runCatching { s.close() }
                withContext(Dispatchers.Main + NonCancellable) {
                    lastFtpLog = log.ifBlank { null }
                }
                throw e
            }
            sources[config.id] = s
            withContext(Dispatchers.Main + NonCancellable) {
                prefs.saveServer(config)
                lastFtpLog = null
                _progress.value = null
                newTab(config.id, config.startPath)
                screen = Screen.BROWSER
                message = info
            }
        }
    }

    /** Prueba la conexión y devuelve el resultado y el detalle de lo hablado con el servidor. */
    fun testServer(config: FtpServerConfig, onDone: (String, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val source = FtpSource(config)
            val text = runCatching { source.testConnection() }
                .getOrElse { "No se pudo conectar:\n${it.message}" }
            val log = runCatching { source.connectionLog }.getOrDefault("")
            runCatching { source.close() }
            withContext(Dispatchers.Main) { onDone(text, log) }
        }
    }

    /** Descarga un enlace directo en la carpeta que esté abierta. */
    fun downloadFromUrl(url: String, suggestedName: String) {
        val tab = activeTab ?: return
        if (tab.sourceId.startsWith("ftp:")) {
            message = "Entra antes en una carpeta del móvil: las descargas se guardan ahí."
            return
        }
        runTask {
            val file = com.bdfiles.net.Downloader.download(
                url, File(tab.path),
                report = { _progress.value = it },
                isCancelled = { cancelRequested },
                suggestedName = suggestedName,
            )
            withContext(Dispatchers.Main + NonCancellable) {
                result = OpResult("Descargado", com.bdfiles.net.Downloader.describe(file) + " en esta carpeta.")
            }
        }
    }

    fun removeServer(name: String) {
        prefs.removeServer(name)
        sources.remove("ftp:$name")?.let { runCatching { it.close() } }
        bump()
    }

    // ---------------------------------------------------------- permisos

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    override fun onCleared() {
        sources.values.forEach { runCatching { it.close() } }
        super.onCleared()
    }

    companion object {
        const val TRASH_DIR = ".BDFiles/Papelera"
    }
}
