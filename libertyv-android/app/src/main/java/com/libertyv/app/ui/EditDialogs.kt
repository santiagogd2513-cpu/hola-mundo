package com.libertyv.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libertyv.app.AppViewModel
import com.libertyv.app.ImportPlan
import com.libertyv.app.formatSize
import com.libertyv.core.ConsolePlatform

/** Pide un nombre (renombrar / nueva carpeta). */
@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "Solo letras sin tilde, números y símbolos básicos. GTA V usa nombres en minúsculas.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Lista de archivos a importar, con Añadir/Reemplazar/Omitir y si se comprimen. */
@Composable
fun ImportDialog(vm: AppViewModel, plan: ImportPlan) {
    val platform = vm.current?.archive?.platform
    val selected = plan.items.count { it.include && it.problem == null }
    AlertDialog(
        onDismissRequest = { vm.cancelImport() },
        title = { Text("Importar en /${plan.dir.path.takeIf { plan.dir.parent != null } ?: ""}") },
        text = {
            Column {
                if (plan.folderName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Crear la carpeta «${plan.folderName}»", modifier = Modifier.weight(1f))
                        Switch(checked = plan.includeFolder, onCheckedChange = { plan.includeFolder = it })
                    }
                    Text(
                        if (plan.includeFolder) "Se importará como carpeta." else "Solo se importará su contenido aquí.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                if (platform == ConsolePlatform.XBOX360) {
                    Text(
                        "Xbox 360: deja «Comprimir» activado; el juego no carga archivos sin comprimir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(plan.items) { item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = item.include && item.problem == null,
                                    enabled = item.problem == null,
                                    onCheckedChange = { item.include = it },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(plan.targetPath(item), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    val kind = if (item.isResource) "recurso RSC7" else "archivo"
                                    val action = when {
                                        item.problem != null -> "No se puede: ${item.problem}"
                                        !item.include -> "Omitir"
                                        item.exists -> "Reemplazar"
                                        else -> "Añadir"
                                    }
                                    Text(
                                        "$action · $kind · ${formatSize(item.file.length())}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (item.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (!item.isResource && item.problem == null && item.include) {
                                Row(
                                    Modifier
                                        .padding(start = 48.dp)
                                        .clickable { item.compress = !item.compress },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = item.compress, onCheckedChange = { item.compress = it })
                                    Text("Comprimir (y cifrar)", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.applyImport() }, enabled = selected > 0) { Text("Importar ($selected)") }
        },
        dismissButton = { TextButton(onClick = { vm.cancelImport() }) { Text("Cancelar") } },
    )
}
