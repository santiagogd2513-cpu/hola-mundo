package com.bdfiles.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bdfiles.AppViewModel
import com.bdfiles.Screen
import com.bdfiles.core.FileItem
import com.bdfiles.core.SortBy
import com.bdfiles.core.formatSize
import com.bdfiles.core.nameOf
import com.bdfiles.ui.theme.BD

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(vm: AppViewModel) {
    val tab = vm.activeTab ?: return
    val selecting = vm.selected.isNotEmpty()
    val revision = vm.uiRevision
    var quickMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var fabOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.background(BD.headerGradient())) {
                if (selecting) {
                    SelectionBar(vm, onMore = { moreMenu = true }, moreOpen = moreMenu, onCloseMore = { moreMenu = false })
                } else {
                    NormalBar(
                        vm = vm,
                        quickMenuOpen = quickMenu,
                        onQuickMenu = { quickMenu = it },
                    )
                }
                if (vm.searching) SearchField(vm)
                TabsBar(vm)
                if (vm.prefs.showPathBar && !vm.searching) PathBar(vm)
            }
        },
        floatingActionButton = {
            if (!selecting && !vm.searching) {
                CreateFab(
                    open = fabOpen,
                    hasClipboard = vm.clipboard != null,
                    onToggle = { fabOpen = !fabOpen },
                    onFolder = { fabOpen = false; vm.askCreateFolder() },
                    onFile = { fabOpen = false; vm.askCreateFile() },
                    onPaste = { fabOpen = false; vm.paste() },
                )
            }
        },
    ) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .swipeBetweenTabs(vm),
        ) {
            val showing: List<FileItem> = if (vm.searching) vm.searchResults else tab.items

            when {
                tab.loading && tab.items.isEmpty() -> CenterBox {
                    CircularProgressIndicator(color = BD.Violet)
                }
                tab.error != null -> CenterBox {
                    Column(
                        Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("🚫", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            tab.error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = { vm.reload() }) { Text("Reintentar") }
                    }
                }
                showing.isEmpty() && vm.searching && !vm.searchRunning -> CenterBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔍", fontSize = 40.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("Sin resultados", color = BD.TextLow)
                    }
                }
                showing.isEmpty() && !vm.searching -> CenterBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📂", fontSize = 44.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("Carpeta vacía", color = BD.TextLow)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Usa el botón + para crear algo",
                            style = MaterialTheme.typography.bodySmall,
                            color = BD.TextLow,
                        )
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    if (vm.searching) {
                        item {
                            Text(
                                if (vm.searchRunning) "Buscando… ${showing.size} encontrados"
                                else "${showing.size} resultado${if (showing.size == 1) "" else "s"}",
                                Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = BD.Cyan,
                            )
                        }
                    }
                    items(showing, key = { it.key + revision }) { item ->
                        FileRow(
                            item = item,
                            selected = vm.isSelected(item),
                            selectionMode = selecting,
                            showThumbnails = vm.prefs.showThumbnails,
                            colorfulIcons = vm.prefs.colorfulIcons,
                            compact = vm.prefs.compactRows,
                            pinned = vm.isPinned(item),
                            favorite = vm.isFavorite(item),
                            subtitleOverride = if (vm.searching) {
                                "${if (item.isDir) "Carpeta" else formatSize(item.size)}  ·  ${item.parentPath}"
                            } else null,
                            onClick = { if (selecting) vm.toggleSelect(item) else vm.open(item) },
                            onLongClick = { vm.toggleSelect(item) },
                            onMenu = { vm.itemMenu = item },
                        )
                        HorizontalDivider(
                            Modifier.padding(start = 70.dp),
                            color = BD.Outline.copy(alpha = 0.35f),
                        )
                    }
                }
            }

            if (vm.searchRunning) {
                CircularProgressIndicator(
                    color = BD.Cyan,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

// ------------------------------------------------------------------ barra normal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NormalBar(vm: AppViewModel, quickMenuOpen: Boolean, onQuickMenu: (Boolean) -> Unit) {
    val tab = vm.activeTab ?: return
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            IconButton(onClick = { vm.goUp() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = BD.LightPurple)
            }
        },
        title = {
            Column {
                Text(
                    nameOf(tab.path).ifEmpty { vm.source(tab.sourceId).title },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${tab.items.size} elementos",
                    style = MaterialTheme.typography.bodySmall,
                    color = BD.TextLow,
                )
            }
        },
        actions = {
            IconButton(onClick = { if (vm.searching) vm.clearSearch() else vm.startSearch() }) {
                Icon(
                    if (vm.searching) Icons.Filled.Close else Icons.Filled.Search,
                    "Buscar",
                    tint = BD.Cyan,
                )
            }
            IconButton(onClick = { vm.screen = Screen.SETTINGS }) {
                Icon(Icons.Filled.Settings, "Ajustes", tint = BD.LightPurple)
            }
            Box {
                IconButton(onClick = { onQuickMenu(true) }) {
                    Icon(Icons.Filled.MoreVert, "Ajustes rápidos", tint = BD.Pink)
                }
                QuickSettingsMenu(vm, quickMenuOpen) { onQuickMenu(false) }
            }
        },
    )
}

/** Los tres puntos de arriba: solo ajustes rápidos. */
@Composable
private fun QuickSettingsMenu(vm: AppViewModel, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            "AJUSTES RÁPIDOS",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = BD.Violet,
            fontSize = 11.sp,
        )
        DropdownMenuItem(
            text = { Text("Mostrar archivos ocultos") },
            leadingIcon = { Checkbox(checked = vm.prefs.showHidden, onCheckedChange = null) },
            onClick = {
                vm.prefs.showHidden = !vm.prefs.showHidden
                vm.reload()
            },
        )
        DropdownMenuItem(
            text = { Text("Verificar al copiar y mover") },
            leadingIcon = { Checkbox(checked = vm.prefs.verifyCopies, onCheckedChange = null) },
            onClick = { vm.prefs.verifyCopies = !vm.prefs.verifyCopies; onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Miniaturas") },
            leadingIcon = { Checkbox(checked = vm.prefs.showThumbnails, onCheckedChange = null) },
            onClick = { vm.prefs.showThumbnails = !vm.prefs.showThumbnails; vm.reload() },
        )
        DropdownMenuItem(
            text = { Text("Carpetas primero") },
            leadingIcon = { Checkbox(checked = vm.prefs.foldersFirst, onCheckedChange = null) },
            onClick = { vm.prefs.foldersFirst = !vm.prefs.foldersFirst; vm.resort() },
        )
        HorizontalDivider()
        Text(
            "ORDENAR POR",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = BD.Violet,
            fontSize = 11.sp,
        )
        SortBy.entries.forEach { s ->
            DropdownMenuItem(
                text = {
                    Text(
                        s.label + if (vm.prefs.sortBy == s) (if (vm.prefs.sortDescending) "  ↓" else "  ↑") else "",
                        color = if (vm.prefs.sortBy == s) BD.Cyan else MaterialTheme.colorScheme.onSurface,
                    )
                },
                onClick = {
                    if (vm.prefs.sortBy == s) {
                        vm.prefs.sortDescending = !vm.prefs.sortDescending
                    } else {
                        vm.prefs.sortBy = s
                        vm.prefs.sortDescending = false
                    }
                    vm.resort()
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Seleccionar todo") },
            onClick = { vm.selectAll(); onDismiss() },
        )
        if (vm.prefs.tabsEnabled) {
            DropdownMenuItem(
                text = { Text("Pestaña nueva") },
                onClick = { vm.newTab(); onDismiss() },
            )
        }
        DropdownMenuItem(text = { Text("Atajos y favoritos") }, onClick = { vm.screen = Screen.SHORTCUTS; onDismiss() })
        DropdownMenuItem(text = { Text("Nube y red") }, onClick = { vm.screen = Screen.CLOUD; onDismiss() })
        DropdownMenuItem(text = { Text("Permisos de apps") }, onClick = { vm.screen = Screen.APP_PERMISSIONS; onDismiss() })
        DropdownMenuItem(text = { Text("Papelera") }, onClick = { vm.openTrash(); onDismiss() })
    }
}

// ------------------------------------------------------------------ barra de selección

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(vm: AppViewModel, onMore: () -> Unit, moreOpen: Boolean, onCloseMore: () -> Unit) {
    val n = vm.selected.size
    val one = vm.selected.singleOrNull()
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            IconButton(onClick = { vm.clearSelection() }) {
                Icon(Icons.Filled.Close, "Quitar selección", tint = BD.TextHigh)
            }
        },
        title = {
            Text(
                "$n seleccionado${if (n == 1) "" else "s"}",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        actions = {
            IconButton(onClick = { vm.copySelection(move = false) }) {
                Icon(Icons.Filled.ContentCopy, "Copiar", tint = BD.Cyan)
            }
            IconButton(onClick = { vm.copySelection(move = true) }) {
                Icon(Icons.Filled.ContentCut, "Mover", tint = BD.Violet)
            }
            IconButton(onClick = { vm.askDelete(vm.selected.toList()) }) {
                Icon(Icons.Filled.Delete, "Borrar", tint = BD.Danger)
            }
            if (one != null) {
                IconButton(onClick = { vm.askRename(one) }) {
                    Icon(Icons.Filled.Edit, "Renombrar", tint = BD.PinkSoft)
                }
            }
            IconButton(onClick = { vm.askCompress(vm.selected.toList()) }) {
                Icon(Icons.Filled.FolderZip, "Comprimir", tint = BD.Warning)
            }
            if (one != null) {
                IconButton(onClick = { vm.infoItem = one }) {
                    Icon(Icons.Filled.Info, "Información", tint = BD.LightPurple)
                }
            }
            Box {
                IconButton(onClick = onMore) {
                    Icon(Icons.Filled.MoreVert, "Más", tint = BD.Pink)
                }
                DropdownMenu(expanded = moreOpen, onDismissRequest = onCloseMore) {
                    DropdownMenuItem(
                        text = { Text("Seleccionar todo") },
                        leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                        onClick = { vm.selectAll(); onCloseMore() },
                    )
                    DropdownMenuItem(
                        text = { Text("Compartir") },
                        onClick = { vm.share(vm.selected.toList()); onCloseMore() },
                    )
                    if (one != null) {
                        DropdownMenuItem(
                            text = { Text(if (vm.isPinned(one)) "Desanclar" else "Anclar arriba del todo") },
                            onClick = { vm.togglePinned(one); onCloseMore() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (vm.isFavorite(one)) "Quitar de favoritos" else "Añadir a favoritos") },
                            onClick = { vm.toggleFavorite(one); onCloseMore() },
                        )
                    }
                    if (vm.clipboard != null) {
                        DropdownMenuItem(
                            text = { Text("Pegar aquí") },
                            leadingIcon = { Icon(Icons.Filled.ContentPaste, null) },
                            onClick = { vm.paste(); onCloseMore() },
                        )
                    }
                }
            }
        },
    )
}

// ------------------------------------------------------------------ ruta

@Composable
private fun PathBar(vm: AppViewModel) {
    val tab = vm.activeTab ?: return
    val root = vm.source(tab.sourceId).rootPath
    val scroll = rememberScrollState()
    val relative = tab.path.removePrefix(root).trim('/')
    val parts = if (relative.isEmpty()) emptyList() else relative.split('/')

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            vm.source(tab.sourceId).title,
            style = MaterialTheme.typography.bodySmall,
            color = if (parts.isEmpty()) BD.Cyan else BD.TextLow,
            modifier = Modifier.clickable { vm.navigate(root, tab.sourceId) },
        )
        parts.forEachIndexed { i, p ->
            Text("  ›  ", style = MaterialTheme.typography.bodySmall, color = BD.Outline)
            val target = root.trimEnd('/') + "/" + parts.take(i + 1).joinToString("/")
            Text(
                p,
                style = MaterialTheme.typography.bodySmall,
                color = if (i == parts.lastIndex) BD.Cyan else BD.TextLow,
                fontWeight = if (i == parts.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clickable { vm.navigate(target, tab.sourceId) },
            )
        }
    }
}

// ------------------------------------------------------------------ búsqueda

@Composable
private fun SearchField(vm: AppViewModel) {
    var wholeDevice by remember { mutableStateOf(true) }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = vm.searchQuery,
            onValueChange = { vm.searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text("Buscar archivos y carpetas…") },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = BD.Cyan) },
            trailingIcon = {
                TextButton(onClick = { vm.runSearch(wholeDevice) }) { Text("Buscar") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.runSearch(wholeDevice) }),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = wholeDevice, onCheckedChange = { wholeDevice = it })
            Text(
                if (wholeDevice) "Buscar en todo el dispositivo" else "Buscar solo en esta carpeta",
                style = MaterialTheme.typography.bodySmall,
                color = BD.TextLow,
                modifier = Modifier.clickable { wholeDevice = !wholeDevice },
            )
        }
    }
}

// ------------------------------------------------------------------ botón +

@Composable
private fun CreateFab(
    open: Boolean,
    hasClipboard: Boolean,
    onToggle: () -> Unit,
    onFolder: () -> Unit,
    onFile: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.End) {
        AnimatedVisibility(visible = open) {
            Column(horizontalAlignment = Alignment.End) {
                if (hasClipboard) {
                    ExtendedFloatingActionButton(
                        onClick = onPaste,
                        containerColor = BD.Cyan,
                        contentColor = Color(0xFF00242B),
                        icon = { Icon(Icons.Filled.ContentPaste, null) },
                        text = { Text("Pegar aquí") },
                    )
                    Spacer(Modifier.height(10.dp))
                }
                ExtendedFloatingActionButton(
                    onClick = onFolder,
                    containerColor = BD.Violet,
                    contentColor = Color.White,
                    icon = { Text("📁", fontSize = 17.sp) },
                    text = { Text("Carpeta nueva") },
                )
                Spacer(Modifier.height(10.dp))
                ExtendedFloatingActionButton(
                    onClick = onFile,
                    containerColor = BD.Pink,
                    contentColor = Color.White,
                    icon = { Text("📄", fontSize = 17.sp) },
                    text = { Text("Archivo nuevo") },
                )
                Spacer(Modifier.height(14.dp))
            }
        }
        FloatingActionButton(
            onClick = onToggle,
            containerColor = BD.Purple,
            contentColor = Color.White,
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(if (open) Icons.Filled.Close else Icons.Filled.Add, "Crear")
        }
    }
}
