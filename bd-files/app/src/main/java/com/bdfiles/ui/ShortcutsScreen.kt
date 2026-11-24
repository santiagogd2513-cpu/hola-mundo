package com.bdfiles.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bdfiles.AppViewModel
import com.bdfiles.core.formatSize
import com.bdfiles.data.Shortcut
import com.bdfiles.ui.theme.BD
import java.io.File

/** Pestaña de atajos: accesos rápidos, favoritos y almacenamientos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutsScreen(vm: AppViewModel, onBack: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val shortcuts = remember(revision, vm.uiRevision) { vm.prefs.shortcuts }
    val favorites = remember(revision, vm.uiRevision) { vm.prefs.favorites }
    val pinned = remember(revision, vm.uiRevision) { vm.prefs.pinned.toList() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                modifier = Modifier.background(BD.headerGradient()),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = BD.LightPurple)
                    }
                },
                title = { Text("Atajos y favoritos") },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            item { SectionHeader("Almacenamientos", "💾") }
            items(vm.volumes) { v ->
                val sourceId = if (v.path == vm.internalRoot) "local" else "vol:${v.path}"
                val src = runCatching { vm.source(sourceId) }.getOrNull()
                val free = src?.freeSpace() ?: -1
                val total = src?.totalSpace() ?: -1
                BigRow(
                    emoji = if (v.removable) "💽" else "📱",
                    title = v.title,
                    subtitle = if (free >= 0 && total > 0) {
                        "${formatSize(free)} libres de ${formatSize(total)}"
                    } else v.path,
                    tint = if (v.removable) BD.Cyan else BD.Violet,
                ) {
                    vm.navigate(v.path, sourceId)
                    onBack()
                }
            }

            item { SectionHeader("Accesos rápidos", "🔗") }
            items(shortcuts) { s ->
                val exists = s.sourceId != "local" || File(s.path).exists()
                BigRow(
                    emoji = s.icon,
                    title = s.name,
                    subtitle = if (exists) s.path else "No existe (puedes quitarlo)",
                    tint = if (exists) BD.Pink else BD.TextLow,
                    onRemove = { vm.prefs.removeShortcut(s); revision++ },
                ) {
                    if (exists) {
                        vm.navigate(s.path, s.sourceId)
                        onBack()
                    } else {
                        vm.message = "“${s.path}” ya no existe."
                    }
                }
            }

            item { SectionHeader("Favoritos", "⭐") }
            if (favorites.isEmpty()) {
                item {
                    Text(
                        "Todavía no tienes favoritos.\nMantén pulsado un archivo o carpeta, o usa su menú ⋮, y elige «Añadir a favoritos».",
                        Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = BD.TextLow,
                    )
                }
            }
            items(favorites) { f ->
                BigRow(
                    emoji = f.icon,
                    title = f.name,
                    subtitle = f.path,
                    tint = BD.Warning,
                    onRemove = {
                        vm.prefs.toggleFavorite(f)
                        revision++
                    },
                ) {
                    val target = if (File(f.path).isDirectory) f.path else File(f.path).parent ?: f.path
                    vm.navigate(target, f.sourceId)
                    onBack()
                }
            }

            item { SectionHeader("Carpetas ancladas", "📌") }
            if (pinned.isEmpty()) {
                item {
                    Text(
                        "Las carpetas ancladas aparecen siempre arriba del todo en su lista.\n" +
                            "Para anclar una, abre su menú ⋮ y pulsa «Anclar arriba del todo».",
                        Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = BD.TextLow,
                    )
                }
            }
            items(pinned) { key ->
                val path = key.substringAfter('|')
                BigRow(
                    emoji = "📌",
                    title = path.substringAfterLast('/'),
                    subtitle = path,
                    tint = BD.Cyan,
                    onRemove = { vm.prefs.togglePinned(key); revision++ },
                ) {
                    vm.navigate(path, key.substringBefore('|'))
                    onBack()
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
fun BigRow(
    emoji: String,
    title: String,
    subtitle: String,
    tint: Color,
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
                .size(42.dp)
                .background(tint.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, fontSize = 21.sp) }
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
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, "Quitar", tint = BD.TextLow, modifier = Modifier.size(18.dp))
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 73.dp), color = BD.Outline.copy(alpha = 0.3f))
}
