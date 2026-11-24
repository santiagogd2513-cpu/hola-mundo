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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bdfiles.AppViewModel
import com.bdfiles.core.AppPermissions
import com.bdfiles.core.ShizukuBridge
import com.bdfiles.ui.theme.BD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Permisos de almacenamiento de las demás apps.
 * Con Shizuku se pueden dar y quitar desde aquí; sin él, solo se ve el estado
 * y se abre la pantalla del sistema.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPermissionsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppPermissions.AppInfo>?>(null) }
    var includeSystem by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<AppPermissions.AppInfo?>(null) }
    val shizukuOk = vm.shizukuReady

    LaunchedEffect(includeSystem, revision) {
        apps = null
        apps = withContext(Dispatchers.IO) { AppPermissions.listApps(context, includeSystem) }
    }

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
                title = { Text("Permisos de apps") },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            // Aviso sobre Shizuku
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (shizukuOk) BD.Success.copy(alpha = 0.12f) else BD.Warning.copy(alpha = 0.12f),
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (shizukuOk) "✅" else "⚠️", fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (shizukuOk) "Shizuku activo" else "Shizuku apagado",
                            fontWeight = FontWeight.SemiBold,
                            color = if (shizukuOk) BD.Success else BD.Warning,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (shizukuOk) {
                            "Puedes dar y quitar permisos a cualquier app desde aquí, igual que haría " +
                                "un ordenador conectado por USB."
                        } else {
                            "Android no deja que una app le dé permisos a otra por su cuenta. Con Shizuku " +
                                "encendido sí se puede. Sin él, solo puedes ver el estado y abrir los " +
                                "ajustes del sistema para darlos tú a mano."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = BD.TextLow,
                    )
                    if (!shizukuOk) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                if (ShizukuBridge.isRunning()) ShizukuBridge.requestPermission()
                                vm.refreshShizuku()
                                revision++
                            },
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(if (ShizukuBridge.isRunning()) "Pedir permiso a Shizuku" else "Comprobar Shizuku")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("Buscar app…") },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = BD.Cyan) },
            )

            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = includeSystem,
                    onClick = { includeSystem = !includeSystem },
                    label = { Text("Incluir apps del sistema") },
                    shape = RoundedCornerShape(50),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${apps?.size ?: 0} apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = BD.TextLow,
                )
            }
            HorizontalDivider(color = BD.Outline)

            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BD.Violet)
                }
            } else {
                val shown = list.filter { filter.isBlank() || it.label.contains(filter, true) || it.packageName.contains(filter, true) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown) { app ->
                        AppRow(app) { detail = app }
                    }
                }
            }
        }
    }

    detail?.let { app ->
        AppDetailDialog(
            app = app,
            shizukuOk = shizukuOk,
            onDismiss = { detail = null },
            onGrantAll = {
                runCatching { AppPermissions.grantAll(app) }
                    .onSuccess { vm.message = it }
                    .onFailure { vm.message = it.message }
                detail = null
                revision++
            },
            onToggle = { perm, grant ->
                runCatching {
                    if (grant) AppPermissions.grant(app.packageName, perm)
                    else AppPermissions.revoke(app.packageName, perm)
                }.onSuccess { vm.message = it }.onFailure { vm.message = it.message }
                detail = null
                revision++
            },
            onOpenSettings = { AppPermissions.openAppSettings(context, app.packageName) },
            onOpenAllFiles = { AppPermissions.openAllFilesSettings(context, app.packageName) },
        )
    }
}

@Composable
private fun AppRow(app: AppPermissions.AppInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon: ImageBitmap? = remember(app.packageName) {
        AppPermissions.icon(context, app.packageName)?.let { d ->
            runCatching {
                val size = 96
                val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                d.setBounds(0, 0, size, size)
                d.draw(c)
                bmp.asImageBitmap()
            }.getOrNull()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(BD.SurfaceHigh, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Text("📱", fontSize = 19.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                when {
                    app.allFiles -> "Acceso a todos los archivos"
                    app.granted.isNotEmpty() -> app.granted.joinToString(", ") { AppPermissions.shortName(it) }
                    else -> "Sin acceso al almacenamiento"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (app.hasAnyStorage) BD.Success else BD.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(if (app.hasAnyStorage) "✅" else "⬜", fontSize = 17.sp)
    }
    HorizontalDivider(Modifier.padding(start = 70.dp), color = BD.Outline.copy(alpha = 0.3f))
}

@Composable
private fun AppDetailDialog(
    app: AppPermissions.AppInfo,
    shizukuOk: Boolean,
    onDismiss: () -> Unit,
    onGrantAll: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAllFiles: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(app.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = BD.TextLow)
                Spacer(Modifier.height(14.dp))
                Text("Permisos que pide:", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                app.declared.forEach { perm ->
                    val has = perm in app.granted || (perm == AppPermissions.MANAGE && app.allFiles)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(AppPermissions.shortName(perm), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (has) "Concedido" else "No concedido",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (has) BD.Success else BD.TextLow,
                            )
                        }
                        Switch(
                            checked = has,
                            enabled = shizukuOk,
                            onCheckedChange = { onToggle(perm, it) },
                        )
                    }
                }
                if (!shizukuOk) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Para cambiarlos desde aquí hace falta Shizuku. Mientras tanto, usa los botones " +
                            "de abajo para abrir los ajustes del sistema.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BD.Warning,
                    )
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = onOpenSettings, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Abrir ajustes de la app")
                }
                if (AppPermissions.MANAGE in app.declared) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onOpenAllFiles, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("Acceso a todos los archivos")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onGrantAll, enabled = shizukuOk, shape = RoundedCornerShape(14.dp)) {
                Text("Dar todos los permisos")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
