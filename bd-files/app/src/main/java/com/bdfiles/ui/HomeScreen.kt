package com.bdfiles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bdfiles.AppViewModel
import com.bdfiles.Screen
import com.bdfiles.core.formatSize
import com.bdfiles.data.Shortcut
import com.bdfiles.net.FtpDeviceType
import com.bdfiles.ui.theme.BD
import java.io.File

/**
 * Pantalla de inicio: dispositivos, accesos directos, favoritos y nube/red.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel) {
    var revision by remember { mutableIntStateOf(0) }
    val shortcuts = remember(revision, vm.uiRevision) { vm.prefs.shortcuts }
    val favorites = remember(revision, vm.uiRevision) { vm.prefs.favorites }
    val servers = remember(revision, vm.uiRevision) { vm.prefs.servers }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                modifier = Modifier.background(BD.headerGradient()),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(BD.gradient, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text("📁", fontSize = 17.sp) }
                        Spacer(Modifier.width(10.dp))
                        Text("BD Files")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        vm.navigate(vm.internalRoot, "local")
                        vm.startSearch()
                    }) {
                        Icon(Icons.Filled.Search, "Buscar", tint = BD.Cyan)
                    }
                    IconButton(onClick = { vm.screen = Screen.SETTINGS }) {
                        Icon(Icons.Filled.Settings, "Ajustes", tint = BD.LightPurple)
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            // ------------------------------------------------ dispositivos
            item { SectionHeader("Dispositivos", "📱") }
            items(vm.volumes) { v ->
                val sourceId = if (v.path == vm.internalRoot) "local" else "vol:${v.path}"
                val src = remember(v.path, revision) { runCatching { vm.source(sourceId) }.getOrNull() }
                val free = src?.freeSpace() ?: -1
                val total = src?.totalSpace() ?: -1
                StorageCard(
                    emoji = if (v.removable) "💽" else "📱",
                    title = v.title,
                    free = free,
                    total = total,
                    path = v.path,
                ) { vm.navigate(v.path, sourceId) }
            }
            if (vm.shizukuReady) {
                item {
                    StorageCard(
                        emoji = "⚙️",
                        title = "Sistema de archivos (/)",
                        free = -1,
                        total = -1,
                        path = "Con Shizuku: /data, /system…",
                    ) { vm.navigate("/", "shizuku") }
                }
            }

            // ------------------------------------------------ accesos directos
            item {
                SectionHeaderWithAction("Accesos directos", "🔗", "Gestionar") {
                    vm.screen = Screen.SHORTCUTS
                }
            }
            item {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shortcuts) { s ->
                        ShortcutTile(s) {
                            if (s.sourceId != "local" || File(s.path).isDirectory) {
                                vm.navigate(s.path, s.sourceId)
                            } else {
                                vm.message = "“${s.path}” ya no existe."
                            }
                        }
                    }
                    item {
                        AddTile("Añadir") { vm.screen = Screen.SHORTCUTS }
                    }
                }
            }

            // ------------------------------------------------ favoritos
            item {
                SectionHeaderWithAction("Favoritos", "⭐", if (favorites.isEmpty()) "" else "Ver todos") {
                    vm.screen = Screen.SHORTCUTS
                }
            }
            if (favorites.isEmpty()) {
                item {
                    EmptyHint(
                        "Todavía no tienes favoritos.\n" +
                            "Abre el menú ⋮ de cualquier archivo o carpeta y pulsa «Añadir a favoritos».",
                    )
                }
            }
            items(favorites.take(8)) { f ->
                BigRow(
                    emoji = f.icon,
                    title = f.name,
                    subtitle = f.path,
                    tint = BD.Warning,
                    onRemove = { vm.prefs.toggleFavorite(f); revision++ },
                ) {
                    val target = if (File(f.path).isDirectory) f.path else File(f.path).parent ?: f.path
                    vm.navigate(target, f.sourceId)
                }
            }

            // ------------------------------------------------ nube y red
            item {
                SectionHeaderWithAction("Nube y red", "🌐", "Gestionar") { vm.screen = Screen.CLOUD }
            }
            items(servers) { s ->
                BigRow(
                    emoji = when (s.deviceType) {
                        FtpDeviceType.XBOX360, FtpDeviceType.PS3 -> "🎮"
                        FtpDeviceType.ROUTER_NAS -> "📡"
                        FtpDeviceType.PC_OTHER -> "💻"
                    },
                    title = s.name,
                    subtitle = "${s.host}:${s.port}  ·  ${s.deviceType.label}",
                    tint = BD.Cyan,
                ) { vm.connectServer(s) }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CloudTile("🌐", "FTP", "Xbox, PC, NAS", BD.Cyan, Modifier.weight(1f)) {
                        vm.screen = Screen.CLOUD
                    }
                    CloudTile("📦", "MediaFire", "Por enlace", BD.Blue, Modifier.weight(1f)) {
                        vm.screen = Screen.CLOUD
                    }
                    CloudTile("🔐", "Mega", "Ver nota", BD.Violet, Modifier.weight(1f)) {
                        vm.screen = Screen.CLOUD
                    }
                }
            }

            // ------------------------------------------------ atajos del sistema
            item { SectionHeader("Más", "⚡") }
            item {
                BigRow("🗑️", "Papelera", "Lo que has borrado y aún se puede recuperar", BD.TextLow) {
                    vm.openTrash()
                }
            }
            item {
                BigRow("🔑", "Permisos de apps", "Dar acceso al almacenamiento a emuladores y demás", BD.Success) {
                    vm.screen = Screen.APP_PERMISSIONS
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

/** Tarjeta de un almacenamiento, con barra de espacio en azul. */
@Composable
private fun StorageCard(
    emoji: String,
    title: String,
    free: Long,
    total: Long,
    path: String,
    onClick: () -> Unit,
) {
    val used = if (total > 0 && free >= 0) ((total - free).toFloat() / total).coerceIn(0f, 1f) else null
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BD.SurfaceCard),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(BD.Blue.copy(alpha = 0.20f), RoundedCornerShape(13.dp))
                        .border(1.dp, BD.Cyan.copy(alpha = 0.35f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 22.sp) }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (used != null) "${formatSize(free)} libres de ${formatSize(total)}" else path,
                        style = MaterialTheme.typography.bodySmall,
                        color = BD.TextLow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("›", fontSize = 22.sp, color = BD.Cyan)
            }
            if (used != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(BD.SurfaceHigh, RoundedCornerShape(50)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(used.coerceAtLeast(0.02f))
                            .height(8.dp)
                            .background(BD.blueGradient, RoundedCornerShape(50)),
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "${(used * 100).toInt()} % usado",
                    style = MaterialTheme.typography.bodySmall,
                    color = BD.CyanSoft,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** Cuadradito de acceso directo. */
@Composable
private fun ShortcutTile(s: Shortcut, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = BD.SurfaceCard,
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick),
    ) {
        Column(
            Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(BD.Blue.copy(alpha = 0.28f), BD.Cyan.copy(alpha = 0.18f)),
                        ),
                        RoundedCornerShape(13.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) { Text(s.icon, fontSize = 21.sp) }
            Spacer(Modifier.height(8.dp))
            Text(
                s.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddTile(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick)
            .border(1.dp, BD.Outline, RoundedCornerShape(18.dp)),
    ) {
        Column(
            Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(42.dp),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, null, tint = BD.Cyan) }
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = BD.TextLow)
        }
    }
}

@Composable
private fun CloudTile(
    emoji: String,
    name: String,
    note: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = BD.SurfaceCard,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            Modifier.padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(tint.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(emoji, fontSize = 20.sp) }
            Spacer(Modifier.height(8.dp))
            Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(note, style = MaterialTheme.typography.bodySmall, color = BD.TextLow, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = BD.TextLow,
    )
}

/** Cabecera de sección con un botón a la derecha. */
@Composable
fun SectionHeaderWithAction(text: String, emoji: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 10.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = BD.CyanSoft,
            fontSize = 12.sp,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.weight(1f))
        if (action.isNotEmpty()) {
            TextButton(onClick = onAction) {
                Text(action, style = MaterialTheme.typography.bodySmall, color = BD.Cyan)
            }
        }
    }
}
