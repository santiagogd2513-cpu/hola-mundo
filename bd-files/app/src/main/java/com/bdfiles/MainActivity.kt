package com.bdfiles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bdfiles.core.ShizukuBridge
import com.bdfiles.core.StorageVolumes
import com.bdfiles.ui.AppPermissionsScreen
import com.bdfiles.ui.ArchiveSheet
import com.bdfiles.ui.BrowserScreen
import com.bdfiles.ui.CloudScreen
import com.bdfiles.ui.HomeScreen
import com.bdfiles.ui.ConfirmDialog
import com.bdfiles.ui.DeleteDialog
import com.bdfiles.ui.InfoDialog
import com.bdfiles.ui.ItemMenuSheet
import com.bdfiles.ui.MessageDialog
import com.bdfiles.ui.NameDialog
import com.bdfiles.ui.ProgressOverlay
import com.bdfiles.ui.ResultDialog
import com.bdfiles.ui.SettingsScreen
import com.bdfiles.ui.ShortcutsScreen
import com.bdfiles.ui.theme.BDFilesTheme
import com.bdfiles.core.Archives
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        vm.refreshShizuku()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuListener) }

        setContent {
            val dark = vm.prefs.darkMode ?: isSystemInDarkTheme()
            BDFilesTheme(dark = dark) {
                // Permite achicar un poco la letra solo dentro de la app, sin tocar
                // el tamaño de texto de todo el móvil.
                val base = LocalDensity.current
                val scale = vm.prefs.textScale
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, base.fontScale * scale),
                ) {
                    AppRoot(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshShizuku()
        // Por si has conectado una memoria o acabas de dar el permiso de archivos.
        vm.refreshVolumes()
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) }
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    var askedPermission by remember { mutableStateOf(false) }

    // Para pedirle a Android acceso a una memoria USB o tarjeta.
    val volumeAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        val key = vm.pendingVolume?.key
        vm.pendingVolume = null
        if (uri != null) {
            vm.onVolumeAccessGranted(uri, key)
        }
    }

    fun askVolumeAccess(v: com.bdfiles.core.StorageVolumes.Volume) {
        vm.pendingVolume = v
        val intent = v.accessIntent ?: StorageVolumes.genericAccessIntent()
        intent.addFlags(
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        runCatching { volumeAccess.launch(intent) }.onFailure {
            vm.pendingVolume = null
            vm.message = "Este móvil no deja elegir almacenamientos desde aquí."
        }
    }

    BackHandler(enabled = true) {
        when {
            vm.archiveToBrowse != null -> vm.closeArchive()
            vm.itemMenu != null -> vm.itemMenu = null
            vm.screen == Screen.BROWSER -> vm.goUp()
            vm.screen != Screen.HOME -> vm.screen = Screen.HOME
            else -> vm.message = "Ya estás en el inicio. Usa el botón de inicio del móvil para salir."
        }
    }

    when (vm.screen) {
        Screen.HOME -> HomeScreen(vm) { v -> askVolumeAccess(v) }
        Screen.BROWSER -> BrowserScreen(vm)
        Screen.SETTINGS -> SettingsScreen(vm) { vm.screen = Screen.HOME }
        Screen.SHORTCUTS -> ShortcutsScreen(vm) { vm.screen = Screen.HOME }
        Screen.CLOUD -> CloudScreen(vm) { vm.screen = Screen.HOME }
        Screen.APP_PERMISSIONS -> AppPermissionsScreen(vm) { vm.screen = Screen.HOME }
    }

    // ------------------------------------------------------ diálogos

    vm.itemMenu?.let { item ->
        ItemMenuSheet(vm, item) { vm.itemMenu = null }
    }

    vm.archiveToBrowse?.let { item ->
        ArchiveSheet(
            item = item,
            entries = vm.archiveEntries,
            error = vm.archiveError,
            canEdit = Archives.formatOf(item.name)?.canWrite == true,
            onDismiss = { vm.closeArchive() },
            onExtractAll = { newFolder -> vm.extractArchive(item, newFolder) },
            onExtractOne = { path -> vm.extractOne(item, path) },
            onRemove = { paths -> vm.removeFromArchive(item, paths) },
        )
    }

    vm.nameRequest?.let { r ->
        NameDialog(
            title = r.title,
            initial = r.initial,
            confirmLabel = r.confirmLabel,
            hint = r.hint,
            onDismiss = { vm.nameRequest = null },
            onConfirm = r.onConfirm,
        )
    }

    vm.deleteRequest?.let { r ->
        DeleteDialog(
            items = r.items,
            canUseTrash = r.canUseTrash,
            onCancel = { vm.deleteRequest = null },
            onTrash = { vm.doDelete(r.items, toTrash = true) },
            onDeleteForever = { vm.doDelete(r.items, toTrash = false) },
        )
    }

    vm.confirm?.let { c ->
        ConfirmDialog(
            title = c.title,
            text = c.text,
            confirmLabel = c.confirmLabel,
            danger = c.danger,
            onCancel = { vm.confirm = null },
            onConfirm = {
                vm.confirm = null
                c.onConfirm()
            },
        )
    }

    vm.infoItem?.let { item ->
        val extra = remember(item.key) {
            buildMap {
                put("Origen", vm.source(item.sourceId).title)
                if (item.isDir) {
                    val kids = runCatching { vm.source(item.sourceId).list(item.path) }.getOrNull()
                    if (kids != null) {
                        put(
                            "Contenido",
                            "${kids.count { it.isDir }} carpetas y ${kids.count { !it.isDir }} archivos",
                        )
                    }
                }
            }
        }
        InfoDialog(item, extra) { vm.infoItem = null }
    }

    vm.result?.let { r -> ResultDialog(r) { vm.result = null } }
    vm.message?.let { m ->
        MessageDialog(m, details = vm.lastFtpLog) {
            vm.message = null
            vm.lastFtpLog = null
        }
    }

    val progress by vm.progress.collectAsStateWithLifecycle()
    progress?.let { p -> ProgressOverlay(p) { vm.cancelTask() } }

    // ------------------------------------------------------ permiso de almacenamiento

    if (!askedPermission && !vm.hasAllFilesAccess()) {
        AlertDialog(
            onDismissRequest = { askedPermission = true },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Permiso para ver tus archivos") },
            text = {
                Column {
                    Text(
                        "BD Files necesita el permiso «Acceso a todos los archivos» para poder entrar " +
                            "en todas las carpetas del móvil.",
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Al pulsar «Dar permiso» se abren los ajustes de Android: busca BD Files y " +
                            "activa el interruptor.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        askedPermission = true
                        vm.requestAllFilesAccess()
                    },
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Dar permiso") }
            },
            dismissButton = {
                TextButton(onClick = { askedPermission = true }) { Text("Ahora no") }
            },
        )
    }
}
