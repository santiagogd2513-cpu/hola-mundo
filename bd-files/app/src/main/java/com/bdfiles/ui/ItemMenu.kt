package com.bdfiles.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bdfiles.AppViewModel
import com.bdfiles.core.Archives
import com.bdfiles.core.FileItem
import com.bdfiles.core.formatSize
import com.bdfiles.data.Shortcut
import com.bdfiles.ui.theme.BD

/** Menú de los tres puntos de cada archivo o carpeta. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemMenuSheet(vm: AppViewModel, item: FileItem, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isArchive = Archives.isArchive(item.name) && item.sourceId == "local"
    val canEdit = Archives.formatOf(item.name)?.canWrite == true

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = BD.SurfaceCard) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            // Cabecera con el nombre
            Row(
                Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(FileLook.emoji(item), fontSize = 28.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (item.isDir) "Carpeta" else formatSize(item.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = BD.TextLow,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = BD.Outline)

            // Marcar la carpeta: anclar y favoritos
            if (item.isDir) {
                SheetAction(
                    if (vm.isPinned(item)) "📌" else "📍",
                    if (vm.isPinned(item)) "Desanclar" else "Anclar arriba del todo",
                    "Las carpetas ancladas salen siempre las primeras de la lista",
                ) { vm.togglePinned(item); onDismiss() }
            } else {
                SheetAction(
                    if (vm.isPinned(item)) "📌" else "📍",
                    if (vm.isPinned(item)) "Desanclar" else "Anclar arriba del todo",
                ) { vm.togglePinned(item); onDismiss() }
            }
            SheetAction(
                if (vm.isFavorite(item)) "⭐" else "☆",
                if (vm.isFavorite(item)) "Quitar de favoritos" else "Añadir a favoritos",
                "Los favoritos están en la pestaña de atajos",
            ) { vm.toggleFavorite(item); onDismiss() }

            if (item.isDir) {
                SheetAction("🔗", "Guardar como atajo", "Para llegar aquí de un toque") {
                    vm.prefs.addShortcut(Shortcut(item.name, item.path, item.sourceId, "📁"))
                    vm.message = "Atajo a “${item.name}” guardado."
                    onDismiss()
                }
                SheetAction("🗂️", "Abrir en una pestaña nueva") {
                    vm.newTab(item.sourceId, item.path)
                    onDismiss()
                }
            }

            HorizontalDivider(color = BD.Outline)

            if (isArchive) {
                SheetAction("🗜️", "Ver dentro del comprimido", "Extraer todo o solo algunas cosas") {
                    vm.archiveToBrowse = item
                    onDismiss()
                }
            }
            if (!item.isDir) {
                SheetAction("▶️", "Abrir con otra app") { vm.openExternally(item); onDismiss() }
                SheetAction("📤", "Compartir") { vm.share(listOf(item)); onDismiss() }
            }

            HorizontalDivider(color = BD.Outline)

            SheetAction("📋", "Copiar") {
                vm.selected.clear(); vm.selected.add(item); vm.copySelection(move = false); onDismiss()
            }
            SheetAction("✂️", "Mover") {
                vm.selected.clear(); vm.selected.add(item); vm.copySelection(move = true); onDismiss()
            }
            SheetAction("✏️", "Renombrar") { onDismiss(); vm.askRename(item) }
            SheetAction("🗜️", "Comprimir en ZIP") { onDismiss(); vm.askCompress(listOf(item)) }
            SheetAction("ℹ️", "Información") { vm.infoItem = item; onDismiss() }
            SheetAction("🗑️", "Borrar", danger = true) { onDismiss(); vm.askDelete(listOf(item)) }
        }
    }
}
