package com.bdfiles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bdfiles.core.FileItem
import com.bdfiles.core.OpProgress
import com.bdfiles.core.OpResult
import com.bdfiles.core.formatDate
import com.bdfiles.core.formatDuration
import com.bdfiles.core.formatSize
import com.bdfiles.core.formatSpeed
import com.bdfiles.ui.theme.BD

/**
 * Fila de botones de un diálogo. Si no caben en una línea (por ejemplo con el
 * texto del sistema puesto en grande), bajan a la siguiente en vez de aplastarse
 * o salirse de la pantalla.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogActions(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/**
 * Barra de progreso en el centro de la pantalla, con el fondo oscurecido.
 * Enseña qué se está haciendo, la velocidad, el tiempo que lleva y el que falta.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressOverlay(p: OpProgress, onCancel: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xE60A0614)),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                Modifier
                    .fillMaxWidth(0.92f)
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = BD.SurfaceCard),
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text(
                        p.action,
                        style = MaterialTheme.typography.titleLarge,
                        color = BD.LightPurple,
                    )
                    if (p.currentName.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            p.currentName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    GradientProgressBar(p.fraction, p.indeterminate)
                    Spacer(Modifier.height(10.dp))

                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            when {
                                p.totalBytes > 0 -> "${formatSize(p.doneBytes)} / ${formatSize(p.totalBytes)}"
                                p.doneBytes > 0 -> formatSize(p.doneBytes)
                                else -> "Preparando…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = BD.TextLow,
                        )
                        if (p.totalFiles > 0) {
                            Text(
                                "${minOf(p.doneFiles + 1, p.totalFiles)} de ${p.totalFiles}",
                                style = MaterialTheme.typography.bodySmall,
                                color = BD.TextLow,
                            )
                        }
                        if (p.fraction != null) {
                            Text(
                                "${(p.fraction * 100).toInt()} %",
                                style = MaterialTheme.typography.bodySmall,
                                color = BD.Cyan,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatBlock("Velocidad", formatSpeed(p.bytesPerSecond), BD.Pink)
                        StatBlock("Llevamos", formatDuration(p.elapsedSeconds), BD.Violet)
                        StatBlock("Falta", if (p.remainingSeconds >= 0) formatDuration(p.remainingSeconds) else "—", BD.Cyan)
                    }

                    Spacer(Modifier.height(18.dp))
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                        Text("Cancelar", color = BD.Danger)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatBlock(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = BD.TextLow, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** Pide un nombre (carpeta nueva, archivo nuevo, renombrar, comprimir...). */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                if (hint.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = BD.TextLow)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Borrar: cancelar a la izquierda, papelera en gris y borrar definitivamente en rojo. */
@Composable
fun DeleteDialog(
    items: List<FileItem>,
    canUseTrash: Boolean,
    onCancel: () -> Unit,
    onTrash: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val what = if (items.size == 1) "“${shortenName(items[0].name)}”" else "${items.size} elementos"
    val carpetas = items.count { it.isDir }
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(24.dp), color = BD.SurfaceCard) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 20.dp),
            ) {
                Text("¿Qué hacemos con $what?", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        if (carpetas > 0) {
                            append("Hay $carpetas carpeta${if (carpetas == 1) "" else "s"}: se borra todo lo que tengan dentro.\n\n")
                        }
                        append(
                            if (canUseTrash) {
                                "Puedes mandarlo a la papelera y recuperarlo luego, o borrarlo para siempre."
                            } else {
                                "Aquí no hay papelera, así que se borrará para siempre."
                            },
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(22.dp))
                DialogActions {
                    TextButton(onClick = onCancel) { Text("Cancelar", color = BD.TextLow) }
                    if (canUseTrash) {
                        Button(
                            onClick = onTrash,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4A4357),
                                contentColor = Color(0xFFE8E2F0),
                            ),
                        ) { Text("Papelera") }
                    }
                    Button(
                        onClick = onDeleteForever,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BD.Danger,
                            contentColor = Color.White,
                        ),
                    ) { Text("Borrar") }
                }
            }
        }
    }
}

/** Ficha con todos los datos del archivo. */
@Composable
fun InfoDialog(item: FileItem, extra: Map<String, String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(FileLook.emoji(item), fontSize = 26.sp)
                Spacer(Modifier.width(10.dp))
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                InfoLine("Tipo", if (item.isDir) "Carpeta" else "Archivo ${item.extension.uppercase().ifEmpty { "sin extensión" }}")
                InfoLine("Tamaño", if (item.isDir) extra["Contenido"] ?: "calculando…" else formatSize(item.size))
                if (!item.isDir) InfoLine("Bytes exactos", "${item.size}")
                InfoLine("Modificado", formatDate(item.lastModified).ifEmpty { "desconocido" })
                InfoLine("Solo lectura", if (item.readOnly) "Sí" else "No")
                InfoLine("Oculto", if (item.isHidden) "Sí" else "No")
                extra.forEach { (k, v) -> if (k != "Contenido") InfoLine(k, v) }
                Spacer(Modifier.height(10.dp))
                Text("Ruta", style = MaterialTheme.typography.bodySmall, color = BD.Violet)
                Text(
                    item.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    // En columna, para que con el texto grande no se corte ni el nombre ni el dato.
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = BD.TextLow)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Recorta por el medio los nombres larguísimos: "archivo…0000.bin". */
fun shortenName(name: String, max: Int = 34): String {
    if (name.length <= max) return name
    val keep = (max - 1) / 2
    return name.take(keep) + "…" + name.takeLast(max - 1 - keep)
}

/** Una opción dentro de una hoja: emoji, texto y descripción. */
@Composable
fun SheetAction(emoji: String, title: String, subtitle: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 20.sp, modifier = Modifier.width(36.dp))
        Column {
            Text(title, color = if (danger) BD.Danger else MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BD.TextLow)
            }
        }
    }
}

/** Resultado de una operación. */
@Composable
fun ResultDialog(r: OpResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (r.ok) "✅" else "⚠️", fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Text(r.title)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(r.detail)
                if (r.errors.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("No se pudo con:", color = BD.Danger, fontWeight = FontWeight.SemiBold)
                    r.errors.take(20).forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = BD.TextLow)
                    }
                    if (r.errors.size > 20) {
                        Text("…y ${r.errors.size - 20} más", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Aceptar") } },
    )
}

/** Mensaje sencillo, con un detalle técnico que se puede desplegar. */
@Composable
fun MessageDialog(text: String, details: String? = null, onDismiss: () -> Unit) {
    var showDetails by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text)
                if (!details.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = { showDetails = !showDetails },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            if (showDetails) "Ocultar el detalle de la conexión"
                            else "Ver el detalle de la conexión",
                            style = MaterialTheme.typography.bodySmall,
                            color = BD.Cyan,
                        )
                    }
                    if (showDetails) {
                        Surface(shape = RoundedCornerShape(10.dp), color = BD.Night, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                details,
                                Modifier
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = BD.TextLow,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Aceptar") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    danger: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(14.dp),
                colors = if (danger) {
                    ButtonDefaults.buttonColors(containerColor = BD.Danger, contentColor = Color.White)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
    )
}

/** Lista del contenido de un comprimido, para verlo y sacar cosas sueltas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveSheet(
    item: FileItem,
    entries: List<com.bdfiles.core.ArchiveEntry>?,
    error: String?,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onExtractAll: (newFolder: Boolean) -> Unit,
    onExtractOne: (String) -> Unit,
    onRemove: (Set<String>) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val marked = remember { mutableStateOf(setOf<String>()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = BD.SurfaceCard) {
        Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
            Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🗜️", fontSize = 26.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            error != null -> "No se pudo leer"
                            entries == null -> "Leyendo…"
                            else -> "${entries.count { !it.isDir }} archivos · ${formatSize(item.size)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = BD.TextLow,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = BD.Outline)

            if (error != null) {
                Text(error, Modifier.padding(22.dp), color = BD.Danger)
            }

            SheetAction("📂", "Extraer en una carpeta nueva", "Se crea una carpeta con el nombre del comprimido") {
                onExtractAll(true)
            }
            SheetAction("📤", "Extraer aquí mismo", "Deja el contenido en esta carpeta") { onExtractAll(false) }
            if (marked.value.isNotEmpty()) {
                SheetAction("⬇️", "Extraer lo marcado (${marked.value.size})") {
                    marked.value.forEach { onExtractOne(it) }
                }
                if (canEdit) {
                    SheetAction("🗑️", "Quitar lo marcado del comprimido", danger = true) {
                        onRemove(marked.value)
                    }
                }
            }
            HorizontalDivider(color = BD.Outline)

            if (entries != null) {
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(entries.filter { !it.isDir }.sortedBy { it.path }) { e ->
                        val isMarked = e.path in marked.value
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    marked.value = if (isMarked) marked.value - e.path else marked.value + e.path
                                }
                                .background(if (isMarked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .padding(horizontal = 22.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (isMarked) "☑" else "☐", Modifier.width(28.dp), fontSize = 16.sp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    e.path.substringAfterLast('/'),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${e.path}  ·  ${formatSize(e.size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BD.TextLow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
