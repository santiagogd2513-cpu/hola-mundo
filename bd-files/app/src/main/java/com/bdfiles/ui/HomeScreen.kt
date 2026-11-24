package com.bdfiles.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bdfiles.AppViewModel
import com.bdfiles.R
import com.bdfiles.Screen
import com.bdfiles.core.formatSize
import com.bdfiles.net.FtpDeviceType
import com.bdfiles.ui.theme.BD
import java.io.File

/**
 * Pantalla de inicio, toda en lista: dispositivos, accesos directos,
 * favoritos y nube/red. Los iconos van siempre a la izquierda.
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
                    }) { Icon(Icons.Filled.Search, "Buscar", tint = BD.Cyan) }
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
                val used = if (total > 0 && free >= 0) ((total - free).toFloat() / total).coerceIn(0f, 1f) else null
                HomeRow(
                    emoji = if (v.removable) "💽" else "📱",
                    title = v.title,
                    subtitle = if (used != null) "${formatSize(free)} libres de ${formatSize(total)}" else v.path,
                    tint = BD.Blue,
                    bar = used,
                ) { vm.navigate(v.path, sourceId) }
            }
            if (vm.shizukuReady) {
                item {
                    HomeRow(
                        emoji = "⚙️",
                        title = "Sistema de archivos (/)",
                        subtitle = "Con Shizuku: /data, /system…",
                        tint = BD.Success,
                    ) { vm.navigate("/", "shizuku") }
                }
            }

            // ------------------------------------------------ accesos directos
            item {
                SectionHeaderWithAction("Accesos directos", "🔗", "Gestionar") { vm.screen = Screen.SHORTCUTS }
            }
            items(shortcuts) { s ->
                HomeRow(emoji = s.icon, title = s.name, subtitle = shortPath(s.path, vm.internalRoot), tint = BD.Cyan) {
                    if (s.sourceId != "local" || File(s.path).isDirectory) {
                        vm.navigate(s.path, s.sourceId)
                    } else {
                        vm.message = "“${s.path}” ya no existe."
                    }
                }
            }
            item {
                HomeRow(
                    icon = Icons.Filled.Add,
                    title = "Añadir acceso directo",
                    subtitle = "Elige una carpeta y guárdala aquí",
                    tint = BD.Violet,
                    showChevron = false,
                ) { vm.screen = Screen.SHORTCUTS }
            }

            // ------------------------------------------------ favoritos
            item {
                SectionHeaderWithAction("Favoritos", "⭐", if (favorites.isEmpty()) "" else "Ver todos") {
                    vm.screen = Screen.SHORTCUTS
                }
            }
            if (favorites.isEmpty()) {
                item {
                    Text(
                        "Todavía no tienes favoritos.\n" +
                            "Abre el menú ⋮ de cualquier archivo o carpeta y pulsa «Añadir a favoritos».",
                        Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = BD.TextLow,
                    )
                }
            }
            items(favorites) { f ->
                HomeRow(
                    emoji = f.icon,
                    title = f.name,
                    subtitle = shortPath(f.path, vm.internalRoot),
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
                HomeRow(
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
                HomeRow(
                    drawable = R.drawable.ic_ftp,
                    title = "Servidor FTP",
                    subtitle = if (servers.isEmpty()) "Xbox 360, PS3, PC, router o NAS" else "Añadir otro servidor",
                    tint = BD.Cyan,
                ) { vm.screen = Screen.CLOUD }
            }
            item {
                HomeRow(
                    drawable = R.drawable.ic_mediafire,
                    title = "MediaFire",
                    subtitle = "Descarga por enlace directo",
                    tint = BD.Blue,
                ) { vm.screen = Screen.CLOUD }
            }
            item {
                HomeRow(
                    drawable = R.drawable.ic_mega,
                    title = "Mega",
                    subtitle = "Todavía no disponible: toca para ver por qué",
                    tint = BD.Danger,
                ) { vm.screen = Screen.CLOUD }
            }

            // ------------------------------------------------ más
            item { SectionHeader("Más", "⚡") }
            item {
                HomeRow("🗑️", "Papelera", "Lo borrado que aún se puede recuperar", BD.TextLow) { vm.openTrash() }
            }
            item {
                HomeRow("🔑", "Permisos de apps", "Dar acceso al almacenamiento a emuladores y demás", BD.Success) {
                    vm.screen = Screen.APP_PERMISSIONS
                }
            }
            item {
                HomeRow("⚙️", "Ajustes", "Interfaz, archivos, verificaciones y acerca de", BD.LightPurple) {
                    vm.screen = Screen.SETTINGS
                }
            }
            item { Spacer(Modifier.height(36.dp)) }
        }
    }
}

/** Acorta la ruta para que quepa: quita la parte del almacenamiento interno. */
private fun shortPath(path: String, root: String): String {
    val p = path.removePrefix(root).trim('/')
    return if (p.isEmpty()) "Carpeta principal" else p
}

/**
 * Fila de la pantalla de inicio: icono a la izquierda, texto y, si hace falta,
 * una barra de espacio debajo.
 */
@Composable
private fun HomeRow(
    emoji: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    drawable: Int? = null,
    title: String,
    subtitle: String,
    tint: Color,
    bar: Float? = null,
    showChevron: Boolean = true,
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(tint.copy(alpha = 0.16f), RoundedCornerShape(13.dp))
                .border(1.dp, tint.copy(alpha = 0.25f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                drawable != null -> Image(
                    painter = painterResource(drawable),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                icon != null -> Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                else -> Text(emoji ?: "📁", fontSize = 21.sp)
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = BD.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (bar != null) {
                Spacer(Modifier.height(7.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(BD.SurfaceHigh, RoundedCornerShape(50)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(bar.coerceAtLeast(0.02f))
                            .height(6.dp)
                            .background(BD.blueGradient, RoundedCornerShape(50)),
                    )
                }
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, "Quitar", tint = BD.TextLow, modifier = Modifier.size(18.dp))
            }
        } else if (showChevron) {
            Text("›", fontSize = 22.sp, color = tint)
        }
    }
    HorizontalDivider(Modifier.padding(start = 75.dp), color = BD.Outline.copy(alpha = 0.3f))
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
