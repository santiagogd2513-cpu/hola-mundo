package com.libertyv.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.libertyv.app.AppViewModel
import com.libertyv.app.formatSize
import com.libertyv.core.ConsolePlatform
import com.libertyv.core.rpf.ResourceExportMode
import com.libertyv.core.rpf.RpfDirectory
import com.libertyv.core.rpf.RpfEntry
import com.libertyv.core.rpf.RpfFileEntry
import com.libertyv.core.rpf.RpfRegularFile
import com.libertyv.core.rpf.RpfResource

private enum class FolderPick { EXPORT, AWC, IMPORT }

@Composable
fun AppRoot(vm: AppViewModel) {
    var folderPick by remember { mutableStateOf(FolderPick.EXPORT) }

    val openRpf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openRpf(uri)
    }
    val openKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadKey(uri)
    }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val e = vm.pendingExport
        vm.pendingExport = null
        if (uri != null && e is RpfFileEntry) vm.exportFileTo(e, uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        when (folderPick) {
            FolderPick.AWC -> if (uri != null) vm.exportAwcToFolder(uri)
            FolderPick.IMPORT -> if (uri != null) vm.importFolder(uri)
            FolderPick.EXPORT -> {
                val e = vm.pendingExport
                vm.pendingExport = null
                if (uri != null && e != null) vm.exportToFolder(e, uri)
            }
        }
    }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importFiles(uris)
    }
    val replaceWith = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val e = vm.pendingReplace
        vm.pendingReplace = null
        if (uri != null && e != null) vm.replaceFile(e, uri)
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.saveAs(uri)
    }

    val actions = remember(vm) {
        BrowserActions(
            exportFile = { e ->
                vm.pendingExport = e
                if (vm.needsFolder(e)) {
                    folderPick = FolderPick.EXPORT
                    pickFolder.launch(null)
                } else {
                    createFile.launch(vm.suggestedName(e))
                }
            },
            exportFolder = { d ->
                vm.pendingExport = d
                folderPick = FolderPick.EXPORT
                pickFolder.launch(null)
            },
            importFiles = { importFiles.launch(arrayOf("*/*")) },
            importFolder = {
                folderPick = FolderPick.IMPORT
                pickFolder.launch(null)
            },
            replaceFile = { e ->
                vm.pendingReplace = e
                replaceWith.launch(arrayOf("*/*"))
            },
            saveAs = { name -> saveAs.launch(name) },
        )
    }

    if (vm.currentDir == null) {
        HomeScreen(
            vm = vm,
            onOpenRpf = { openRpf.launch(arrayOf("*/*")) },
            onLoadKey = { openKey.launch(arrayOf("*/*")) },
        )
    } else {
        BrowserScreen(vm, actions)
    }

    vm.selected?.let { FileSheet(vm, it, actions) }

    vm.awcState?.let { st ->
        AwcDialog(vm, st, onExportAll = {
            folderPick = FolderPick.AWC
            pickFolder.launch(null)
        })
    }

    vm.textPreview?.let { (name, text) -> PreviewDialog(name, text) { vm.textPreview = null } }

    vm.importPlan?.let { ImportDialog(vm, it) }
    vm.renaming?.let { e ->
        NameDialog("Renombrar", e.name, "Renombrar", onDismiss = { vm.renaming = null }) { vm.rename(e, it) }
    }
    if (vm.creatingFolder) {
        NameDialog("Nueva carpeta", "", "Crear", onDismiss = { vm.creatingFolder = false }) { vm.createFolder(it) }
    }
    vm.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = {
                TextButton(onClick = {
                    vm.confirm = null
                    c.onConfirm()
                }) { Text(c.confirmLabel) }
            },
            dismissButton = { TextButton(onClick = { vm.confirm = null }) { Text("Cancelar") } },
        )
    }

    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("Aceptar") } },
            text = { Text(msg, modifier = Modifier.verticalScroll(rememberScrollState())) },
        )
    }

    val busy by vm.busy.collectAsStateWithLifecycle()
    busy?.let { b ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (b.detail.isNotEmpty()) {
                        Text(b.detail, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    val p = b.progress
                    if (p == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${(p * 100).toInt().coerceIn(0, 100)} %", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.cancelTask() }) { Text("Cancelar") } },
        )
    }
}

class BrowserActions(
    val exportFile: (RpfFileEntry) -> Unit,
    val exportFolder: (RpfDirectory) -> Unit,
    val importFiles: () -> Unit,
    val importFolder: () -> Unit,
    val replaceFile: (RpfFileEntry) -> Unit,
    val saveAs: (String) -> Unit,
)

// ---------------------------------------------------------------- inicio

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(vm: AppViewModel, onOpenRpf: () -> Unit, onLoadKey: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("LibertyV") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Explorador y editor de archivos RPF de Grand Theft Auto V para PlayStation 3 y Xbox 360.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Claves de cifrado", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Los RPF de consola van cifrados. Carga tu key_xbox360.dat o key_ps3.dat " +
                            "(se guarda dentro de la app; se comprueba que sea la correcta).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    for (p in ConsolePlatform.entries) {
                        val ok = vm.keys.containsKey(p)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (ok) "✅" else "⬜", fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.displayName, fontWeight = FontWeight.SemiBold)
                                Text(if (ok) "Clave cargada" else "Falta ${p.keyFileName}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (ok) TextButton(onClick = { vm.deleteKey(p) }) { Text("Quitar") }
                        }
                    }
                    FilledTonalButton(onClick = onLoadKey, modifier = Modifier.fillMaxWidth()) { Text("Cargar clave (.dat)") }
                }
            }

            Button(
                onClick = onOpenRpf,
                enabled = vm.keys.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text("2. Abrir archivo RPF", fontSize = 18.sp) }
            if (vm.keys.isEmpty()) {
                Text("Primero carga al menos una clave.", style = MaterialTheme.typography.bodySmall)
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Al exportar recursos", style = MaterialTheme.typography.titleMedium)
                    Text("Modelos, texturas, scripts… (.xft, .xtd, .xsc, .cft…)", style = MaterialTheme.typography.bodySmall)
                    for (m in ResourceExportMode.entries) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = vm.resourceMode == m, onClick = { vm.chooseResourceMode(m) })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = vm.resourceMode == m, onClick = { vm.chooseResourceMode(m) })
                            Column {
                                Text(m.label)
                                Text(m.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text(
                        "Para editar un recurso y volver a meterlo, expórtalo en modo RSC7.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    "Mantén pulsado un archivo o carpeta para seleccionarlo (renombrar, borrar). " +
                        "En el menú ⋮ puedes importar, crear carpetas y guardar. " +
                        "Los cambios no se escriben hasta que pulsas «Guardar».",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- explorador

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(vm: AppViewModel, actions: BrowserActions) {
    val dir = vm.currentDir ?: return
    val open = vm.current ?: return
    val version = vm.treeVersion
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val selecting = vm.marked.isNotEmpty()
    val modified = vm.isModified

    BackHandler {
        if (searching && vm.search.isEmpty() && !selecting) {
            searching = false
        } else {
            vm.back()
        }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    navigationIcon = { IconButton(onClick = { vm.marked.clear() }) { Icon(Icons.Filled.Close, "Quitar selección") } },
                    title = { Text("${vm.marked.size} seleccionado${if (vm.marked.size == 1) "" else "s"}") },
                    actions = {
                        if (vm.marked.size == 1) {
                            IconButton(onClick = { vm.renaming = vm.marked[0] }) { Icon(Icons.Filled.Edit, "Renombrar") }
                        }
                        IconButton(onClick = { vm.askDelete(vm.marked.toList()) }) { Icon(Icons.Filled.Delete, "Borrar") }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                    },
                    title = {
                        Column {
                            Text((if (modified) "• " else "") + open.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (modified) "Sin guardar · " else "") +
                                    "${open.archive.platform.displayName} · /${dir.path.takeIf { dir.parent != null } ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (modified) {
                            IconButton(onClick = { vm.save() }) {
                                Icon(Icons.Filled.Check, if (open.isNested) "Aplicar al RPF padre" else "Guardar")
                            }
                        }
                        IconButton(onClick = { searching = !searching; if (!searching) vm.search = "" }) {
                            Icon(Icons.Filled.Search, "Buscar")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más opciones") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Importar archivos…") }, onClick = { menu = false; actions.importFiles() })
                                DropdownMenuItem(text = { Text("Importar carpeta…") }, onClick = { menu = false; actions.importFolder() })
                                DropdownMenuItem(text = { Text("Nueva carpeta") }, onClick = { menu = false; vm.creatingFolder = true })
                                HorizontalDivider()
                                if (open.isNested) {
                                    DropdownMenuItem(
                                        text = { Text("Aplicar cambios al RPF padre") },
                                        enabled = modified,
                                        onClick = { menu = false; vm.applyToParent() },
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Guardar") },
                                        enabled = modified && open.uri != null,
                                        onClick = { menu = false; vm.save() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Guardar como…") },
                                        onClick = { menu = false; actions.saveAs(open.title) },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Exportar esta carpeta…") }, onClick = { menu = false; actions.exportFolder(dir) })
                                DropdownMenuItem(text = { Text("Cerrar RPF") }, onClick = { menu = false; vm.closeAll() })
                            }
                        }
                    },
                )
            }
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            if (searching && !selecting) {
                OutlinedTextField(
                    value = vm.search,
                    onValueChange = { vm.search = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    singleLine = true,
                    placeholder = { Text("Buscar en todo el RPF…") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }

            val query = vm.search.trim()
            val entries: List<RpfEntry> = remember(dir, query, version) {
                if (query.isEmpty()) {
                    dir.children
                } else {
                    open.archive.root.allFiles().filter { it.name.contains(query, ignoreCase = true) }.take(1000)
                }
            }

            if (query.isEmpty()) {
                val files = remember(dir, version) { dir.allFiles() }
                Text(
                    "${dir.children.size} elementos · ${files.size} archivos en total · ${formatSize(files.sumOf { it.size })}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            } else {
                Text(
                    if (entries.size >= 1000) "Más de 1000 resultados (se muestran 1000)" else "${entries.size} resultados",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider()

            if (entries.isEmpty() && query.isEmpty()) {
                Text(
                    "Carpeta vacía. Usa el menú ⋮ para importar archivos.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries) { e ->
                    val isMarked = vm.marked.any { it === e }
                    EntryRow(
                        e,
                        showPath = query.isNotEmpty(),
                        marked = isMarked,
                        onClick = {
                            when {
                                selecting -> vm.toggleMark(e)
                                e is RpfDirectory -> vm.enter(e)
                                e is RpfFileEntry -> vm.selected = e
                            }
                        },
                        onLongClick = { vm.toggleMark(e) },
                    )
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}

private fun iconFor(e: RpfEntry): String = when {
    e is RpfDirectory -> "📁"
    e.extension == "rpf" -> "📦"
    e.extension == "awc" -> "🎵"
    e is RpfResource -> "🧩"
    e.extension in setOf("xml", "meta", "txt", "dat", "ide", "ipl", "ymt", "cfg", "ini", "csv", "lua") -> "📝"
    else -> "📄"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(e: RpfEntry, showPath: Boolean, marked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (marked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (marked) "☑️" else iconFor(e), fontSize = 24.sp, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val sub = when (e) {
                is RpfDirectory -> "${e.children.size} elementos"
                is RpfFileEntry -> buildString {
                    append(formatSize(e.size))
                    if (e is RpfResource) append(" · recurso v${e.version}")
                    if (e is RpfRegularFile && e.isCompressed) append(" · comprimido")
                    if ((e is RpfRegularFile && e.isEncrypted) || (e is RpfResource && e.isEncrypted)) append(" · cifrado")
                    if (e.isImported) append(" · nuevo")
                }
            }
            Text(
                if (showPath) "${e.path} · $sub" else sub,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (e is RpfFileEntry && e.isImported) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- hoja de archivo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSheet(vm: AppViewModel, e: RpfFileEntry, actions: BrowserActions) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { vm.selected = null }, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(iconFor(e), fontSize = 28.sp)
                Spacer(Modifier.width(8.dp))
                Text(e.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(e.path, style = MaterialTheme.typography.bodySmall)
            InfoLine("Tamaño", formatSize(e.size))
            InfoLine("Ocupa en el RPF", if (e.isImported) "se calcula al guardar" else formatSize(e.storedSize))
            when (e) {
                is RpfRegularFile -> {
                    InfoLine("Comprimido", if (e.isCompressed) "Sí" else "No")
                    InfoLine("Cifrado", if (e.isEncrypted) "Sí" else "No")
                }
                is RpfResource -> {
                    InfoLine("Recurso", "tipo ${e.resourceType}, versión ${e.version}")
                    InfoLine("System / Graphics", "${formatSize(e.systemSize)} / ${formatSize(e.graphicsSize)}")
                    InfoLine("Cifrado", if (e.isEncrypted) "Sí" else "No")
                }
            }
            if (e.isImported) {
                Text("Archivo nuevo: se escribirá al guardar el RPF.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))

            if (e.extension == "rpf") {
                Button(onClick = { vm.selected = null; vm.openNestedRpf(e) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Abrir este RPF")
                }
            }
            if (e.extension == "awc") {
                Button(onClick = { vm.selected = null; vm.openAwc(e) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Escuchar / exportar a WAV")
                }
            }
            FilledTonalButton(onClick = { vm.selected = null; actions.exportFile(e) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (e is RpfResource) "Exportar (${vm.resourceMode.label})" else "Exportar")
            }
            OutlinedButton(onClick = { vm.selected = null; vm.preview(e) }, modifier = Modifier.fillMaxWidth()) {
                Text("Ver contenido")
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Editar", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { vm.selected = null; actions.replaceFile(e) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (e is RpfResource) "Reemplazar (archivo RSC7)…" else "Reemplazar por otro archivo…")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.selected = null; vm.renaming = e }, modifier = Modifier.weight(1f)) {
                    Text("Renombrar")
                }
                OutlinedButton(onClick = { vm.askDelete(listOf(e)) }, modifier = Modifier.weight(1f)) {
                    Text("Borrar", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------- audio

@Composable
private fun AwcDialog(vm: AppViewModel, st: com.libertyv.app.AwcState, onExportAll: () -> Unit) {
    AlertDialog(
        onDismissRequest = { vm.closeAwc() },
        title = { Text(st.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn {
                item {
                    Text(
                        "${st.awc.tracks.size} pista(s)" + if (st.awc.isMultiChannel) " · multicanal" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(st.awc.tracks, key = { it.id }) { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Pista ${t.id}", fontWeight = FontWeight.Medium)
                            val secs = t.durationSeconds.toInt()
                            Text("%d:%02d · %d Hz".format(secs / 60, secs % 60, t.sampleRate), style = MaterialTheme.typography.bodySmall)
                        }
                        if (vm.playingTrack == t.id) {
                            TextButton(onClick = { vm.stopAudio() }) { Text("⏹ Parar") }
                        } else {
                            TextButton(onClick = { vm.playTrack(t) }) { Text("▶ Escuchar") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onExportAll) { Text("Exportar WAV") } },
        dismissButton = { TextButton(onClick = { vm.closeAwc() }) { Text("Cerrar") } },
    )
}

// ---------------------------------------------------------------- vista previa

@Composable
private fun PreviewDialog(name: String, text: String, onClose: () -> Unit) {
    val lines = remember(text) { text.lines() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxSize().padding(12.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cerrar") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(lines.size) { i ->
                        Text(lines[i], fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = true)
                    }
                }
            }
        }
    }
}
