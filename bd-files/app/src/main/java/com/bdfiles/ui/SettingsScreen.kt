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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bdfiles.AppViewModel
import com.bdfiles.BuildConfig
import com.bdfiles.Screen
import com.bdfiles.core.ShizukuBridge
import com.bdfiles.core.SortBy
import com.bdfiles.core.formatSize
import com.bdfiles.data.Prefs
import com.bdfiles.ui.theme.BD

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val prefs = vm.prefs
    // Un contador para redibujar cuando cambia un ajuste guardado.
    var revision by remember { mutableIntStateOf(0) }
    var tabCountDialog by remember { mutableStateOf(false) }
    fun changed() { revision++ }

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
                title = { Text("Ajustes") },
            )
        },
    ) { pad ->
        @Suppress("UNUSED_EXPRESSION") revision
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ---------------------------------------------- interfaz y estilo
            SectionHeader("Interfaz y estilo", "🎨")

            SettingRow("Tema", "Claro, oscuro o el del sistema") {}
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val modes = listOf<Pair<String, Boolean?>>(
                    "Sistema" to null, "Oscuro" to true, "Claro" to false,
                )
                modes.forEach { (label, value) ->
                    FilterChip(
                        selected = prefs.darkMode == value,
                        onClick = { prefs.darkMode = value; changed() },
                        label = { Text(label) },
                        shape = RoundedCornerShape(50),
                    )
                }
            }

            SettingRow(
                "Pestañas",
                if (prefs.tabsEnabled) {
                    "${prefs.maxTabs} pestañas. Toca aquí para cambiar cuántas quieres."
                } else {
                    "Apagadas. Toca el interruptor para encenderlas."
                },
                onClick = { if (prefs.tabsEnabled) tabCountDialog = true },
            ) {
                Switch(
                    checked = prefs.tabsEnabled,
                    onCheckedChange = {
                        prefs.tabsEnabled = it
                        vm.applyTabCount(if (it) prefs.maxTabs else 1)
                        changed()
                    },
                )
            }

            SettingRow("Miniaturas", "Fotos, vídeos, PDF, APK y iconos de los .jar") {
                Switch(checked = prefs.showThumbnails, onCheckedChange = { prefs.showThumbnails = it; changed(); vm.reload() })
            }
            SettingRow(
                "Tamaño del texto en la app",
                when {
                    prefs.textScale < 0.95f -> "Más pequeño que en el resto del móvil (${(prefs.textScale * 100).toInt()} %)"
                    prefs.textScale > 1.05f -> "Más grande que en el resto del móvil (${(prefs.textScale * 100).toInt()} %)"
                    else -> "Igual que en el resto del móvil"
                },
            ) {}
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0.8f to "80 %", 0.9f to "90 %", 1.0f to "Sistema", 1.1f to "110 %").forEach { (v, label) ->
                    FilterChip(
                        selected = kotlin.math.abs(prefs.textScale - v) < 0.01f,
                        onClick = { prefs.textScale = v; changed() },
                        label = { Text(label) },
                        shape = RoundedCornerShape(50),
                    )
                }
            }
            Text(
                "Si tienes la letra del móvil muy grande y aquí se te amontonan las cosas, " +
                    "bájala solo para BD Files.",
                Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = BD.TextLow,
            )

            SettingRow("Iconos de colores", "Cada tipo de archivo con su color") {
                Switch(checked = prefs.colorfulIcons, onCheckedChange = { prefs.colorfulIcons = it; changed() })
            }
            SettingRow("Filas compactas", "Caben más archivos en pantalla") {
                Switch(checked = prefs.compactRows, onCheckedChange = { prefs.compactRows = it; changed() })
            }
            SettingRow("Barra de ruta", "La línea de migas de arriba") {
                Switch(checked = prefs.showPathBar, onCheckedChange = { prefs.showPathBar = it; changed() })
            }

            // ---------------------------------------------- archivos y listado
            SectionHeader("Archivos y listado", "📂")

            SettingRow("Mostrar archivos y carpetas ocultos", "Los que empiezan por punto") {
                Switch(checked = prefs.showHidden, onCheckedChange = { prefs.showHidden = it; changed(); vm.reload() })
            }
            SettingRow("Carpetas primero", "Antes que los archivos") {
                Switch(checked = prefs.foldersFirst, onCheckedChange = { prefs.foldersFirst = it; changed(); vm.resort() })
            }
            SettingRow("Ordenar por") {}
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SortBy.entries.forEach { s ->
                    FilterChip(
                        selected = prefs.sortBy == s,
                        onClick = { prefs.sortBy = s; changed(); vm.resort() },
                        label = { Text(s.label) },
                        shape = RoundedCornerShape(50),
                    )
                }
            }
            SettingRow("Orden invertido", "De mayor a menor, de la Z a la A…") {
                Switch(checked = prefs.sortDescending, onCheckedChange = { prefs.sortDescending = it; changed(); vm.resort() })
            }

            // ---------------------------------------------- verificaciones
            SectionHeader("Verificaciones y seguridad", "🛡️")

            SettingRow(
                "Verificar al copiar y mover",
                "Se comprueba la copia antes de dar por buena la operación. Al mover, el original " +
                    "solo se borra si la comprobación sale bien. Va algo más lento, pero no se pierde nada.",
            ) {
                Switch(checked = prefs.verifyCopies, onCheckedChange = { prefs.verifyCopies = it; changed() })
            }
            SettingRow("Usar papelera", "Al borrar se puede mandar a la papelera en vez de borrar del todo") {
                Switch(checked = prefs.useTrash, onCheckedChange = { prefs.useTrash = it; changed() })
            }
            SettingRow("Vaciar la papelera ahora", "Borra para siempre lo que haya dentro", onClick = { vm.emptyTrash() }) {
                Text("🗑️", fontSize = 20.sp)
            }
            SettingRow("Abrir la papelera", onClick = { vm.openTrash(); onBack() }) {
                Text("›", fontSize = 22.sp, color = BD.TextLow)
            }

            // ---------------------------------------------- permisos
            SectionHeader("Permisos y acceso", "🔑")

            val allFiles = vm.hasAllFilesAccess()
            SettingRow(
                "Acceso a todos los archivos",
                if (allFiles) "Concedido: BD Files puede ver todo el almacenamiento"
                else "Hace falta para ver todas las carpetas. Tócalo para dárselo.",
                onClick = { if (!allFiles) vm.requestAllFilesAccess() },
            ) {
                Text(if (allFiles) "✅" else "⚠️", fontSize = 20.sp)
            }

            val shizukuRunning = ShizukuBridge.isRunning()
            val shizukuOk = vm.shizukuReady
            SettingRow(
                "Shizuku",
                when {
                    shizukuOk -> "Activo. Puedes dar permisos a otras apps y entrar en carpetas del sistema."
                    shizukuRunning -> "Instalado pero sin permiso. Tócalo para pedírselo."
                    else -> "No está en marcha. Abre la app Shizuku y arráncala."
                },
                onClick = {
                    if (shizukuRunning && !shizukuOk) ShizukuBridge.requestPermission()
                    vm.refreshShizuku()
                },
            ) {
                Text(if (shizukuOk) "✅" else if (shizukuRunning) "⚠️" else "⬜", fontSize = 20.sp)
            }
            SettingRow("Permisos de otras apps", "Dar acceso al almacenamiento a emuladores y demás", onClick = {
                vm.screen = Screen.APP_PERMISSIONS
            }) {
                Text("›", fontSize = 22.sp, color = BD.TextLow)
            }

            // ---------------------------------------------- almacenamiento
            SectionHeader("Almacenamiento", "💾")
            vm.volumes.forEach { v ->
                val action = vm.volumeAction(v)
                val src = (action as? AppViewModel.VolumeAction.Open)
                    ?.let { runCatching { vm.source(it.sourceId) }.getOrNull() }
                val free = src?.freeSpace() ?: -1
                val total = src?.totalSpace() ?: -1
                SettingRow(
                    v.title,
                    when {
                        action is AppViewModel.VolumeAction.AskPermission ->
                            "Hace falta dar acceso desde la pantalla de inicio"
                        free >= 0 && total > 0 -> "${formatSize(free)} libres de ${formatSize(total)}"
                        else -> v.path ?: "Listo"
                    },
                    onClick = {
                        if (action is AppViewModel.VolumeAction.Open) {
                            vm.navigate(action.path, action.sourceId)
                            onBack()
                        } else {
                            vm.screen = Screen.HOME
                        }
                    },
                ) {
                    Text("›", fontSize = 22.sp, color = BD.TextLow)
                }
            }
            // Accesos a memorias USB ya concedidos
            vm.prefs.grantedVolumes.forEach { (key, _) ->
                if (vm.volumes.none { it.key == key }) {
                    SettingRow(
                        key,
                        "Permiso guardado de un almacenamiento que ahora no está conectado",
                        onClick = { vm.forgetVolume(key) },
                    ) { Text("Olvidar", style = MaterialTheme.typography.bodySmall, color = BD.Danger) }
                }
            }
            if (vm.shizukuReady) {
                SettingRow("Sistema de archivos completo (/)", "Entrar con Shizuku a /data, /system…", onClick = {
                    vm.navigate("/", "shizuku")
                    onBack()
                }) { Text("›", fontSize = 22.sp, color = BD.TextLow) }
            }

            // ---------------------------------------------- acerca de
            SectionHeader("Acerca de", "ℹ️")
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BD.SurfaceCard),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(46.dp)
                                .background(BD.gradient, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text("📁", fontSize = 24.sp) }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("BD Files", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Gestor de archivos",
                                style = MaterialTheme.typography.bodySmall,
                                color = BD.TextLow,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = BD.Outline)
                    Spacer(Modifier.height(12.dp))
                    AboutLine("Versión", "v${BuildConfig.VERSION_NAME}")
                    AboutLine("Compilación", "número ${BuildConfig.BUILD_NUMBER}")
                    AboutLine("Autor", BuildConfig.AUTHOR)
                }
            }
            Spacer(Modifier.height(36.dp))
        }
    }

    if (tabCountDialog) {
        TabCountDialog(
            current = prefs.maxTabs,
            onDismiss = { tabCountDialog = false },
            onPick = {
                vm.applyTabCount(it)
                tabCountDialog = false
                changed()
            },
        )
    }
}

/** Menú para elegir cuántas pestañas se quieren, con botones de opción. */
@Composable
private fun TabCountDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("¿Cuántas pestañas?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (n in 2..Prefs.MAX_TABS_LIMIT) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(n) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = n == current, onClick = { onPick(n) })
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(BD.tabColor(n - 1), RoundedCornerShape(50)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("$n pestañas", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR") } },
    )
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = BD.TextLow)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = BD.LightPurple)
    }
}
