package com.libertyv.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

@Composable
fun AppRoot(vm: AppViewModel) {
    var pendingAwcExport by remember { mutableStateOf(false) }

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
        if (pendingAwcExport) {
            pendingAwcExport = false
            if (uri != null) vm.exportAwcToFolder(uri)
            return@rememberLauncherForActivityResult
        }
        val e = vm.pendingExport
        vm.pendingExport = null
        if (uri != null && e != null) vm.exportToFolder(e, uri)
    }

    val actions = remember(vm) {
        BrowserActions(
            exportFile = { e ->
                vm.pendingExport = e
                if (vm.needsFolder(e)) pickFolder.launch(null) else createFile.launch(vm.suggestedName(e))
            },
            exportFolder = { d ->
                vm.pendingExport = d
                pickFolder.launch(null)
            },
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
            pendingAwcExport = true
            pickFolder.launch(null)
        })
    }

    vm.textPreview?.let { (name, text) -> PreviewDialog(name, text) { vm.textPreview = null } }

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
                "Explorador de archivos RPF de Grand Theft Auto V para PlayStation 3 y Xbox 360.",
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
                                .selectable(selected = vm.resourceMode == m, onClick = { vm.setResourceMode(m) })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = vm.resourceMode == m, onClick = { vm.setResourceMode(m) })
                            Column {
                                Text(m.label)
                                Text(m.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    "Fase 1: ver, extraer y escuchar audio. La edición (importar, reemplazar, borrar y guardar) llegará en la fase 2.",
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
    var searching by remember { mutableStateOf(false) }

    BackHandler {
        if (searching && vm.search.isEmpty()) {
            searching = false
        } else {
            vm.back()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                },
                title = {
                    Column {
                        Text(open.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${open.archive.platform.displayName} · /${dir.path.takeIf { dir.parent != null } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.search = "" }) {
                        Icon(Icons.Filled.Search, "Buscar")
                    }
                    IconButton(onClick = { vm.closeAll() }) { Icon(Icons.Filled.Close, "Cerrar RPF") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            if (searching) {
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
            val entries: List<RpfEntry> = remember(dir, query) {
                if (query.isEmpty()) {
                    dir.children
                } else {
                    open.archive.root.allFiles().filter { it.name.contains(query, ignoreCase = true) }.take(1000)
                }
            }

            if (query.isEmpty()) {
                val files = remember(dir) { dir.allFiles() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${dir.children.size} elementos · ${files.size} archivos en total · ${formatSize(files.sumOf { it.size })}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { actions.exportFolder(dir) }, enabled = files.isNotEmpty()) {
                        Text("Exportar carpeta")
                    }
                }
            } else {
                Text(
                    if (entries.size >= 1000) "Más de 1000 resultados (se muestran 1000)" else "${entries.size} resultados",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider()

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries) { e ->
                    EntryRow(e, showPath = query.isNotEmpty()) {
                        when (e) {
                            is RpfDirectory -> vm.enter(e)
                            is RpfFileEntry -> vm.selected = e
                        }
                    }
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

@Composable
private fun EntryRow(e: RpfEntry, showPath: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(iconFor(e), fontSize = 24.sp, modifier = Modifier.size(32.dp))
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
                }
            }
            Text(
                if (showPath) "${e.path} · $sub" else sub,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            InfoLine("Ocupa en el RPF", formatSize(e.storedSize))
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
