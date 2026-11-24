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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bdfiles.AppViewModel
import com.bdfiles.net.FtpDeviceType
import com.bdfiles.net.FtpServerConfig
import com.bdfiles.ui.theme.BD

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(vm: AppViewModel, onBack: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val servers = remember(revision, vm.uiRevision) { vm.prefs.servers }
    var editing by remember { mutableStateOf<FtpServerConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    var infoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

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
                title = { Text("Nube y red") },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            item { SectionHeader("Servidores FTP", "🌐") }
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
                    onRemove = { vm.removeServer(s.name); revision++ },
                ) { vm.connectServer(s) }
            }
            item {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = { creating = true }, shape = RoundedCornerShape(14.dp)) {
                        Text("Añadir servidor")
                    }
                    if (servers.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { editing = servers.first() },
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("Editar el primero") }
                    }
                }
            }

            item { SectionHeader("Descargar de internet", "⬇️") }
            item {
                BigRow(
                    emoji = "🔗",
                    title = "Descargar desde un enlace",
                    subtitle = "Pega una dirección directa y se guarda en la carpeta actual",
                    tint = BD.Violet,
                ) { urlDialog = true }
            }

            item { SectionHeader("Servicios de nube", "☁️") }
            item {
                CloudCard(
                    drawable = com.bdfiles.R.drawable.ic_mediafire,
                    name = "MediaFire",
                    status = "Solo enlaces directos",
                    detail = "Entrar con tu cuenta necesita una clave de desarrollador que MediaFire da " +
                        "solo al registrar la aplicación, y todavía no la tengo. Mientras tanto, abre el " +
                        "enlace de MediaFire en el navegador, copia el enlace de descarga directa y pégalo " +
                        "en «Descargar desde un enlace».",
                ) { infoDialog = "MediaFire" to MEDIAFIRE_HELP }
            }
            item {
                CloudCard(
                    drawable = com.bdfiles.R.drawable.ic_mega,
                    name = "Mega",
                    status = "No disponible todavía",
                    detail = "Mega cifra todo en el móvil con su propio sistema. Para que funcione hay que " +
                        "escribir ese cifrado entero, y es un trabajo grande aparte. Si lo quieres, dímelo " +
                        "y lo hacemos en otra fase.",
                ) { infoDialog = "Mega" to MEGA_HELP }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    if (creating || editing != null) {
        ServerDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onTest = { cfg, onDone -> vm.testServer(cfg, onDone) },
            onSave = { cfg ->
                vm.prefs.saveServer(cfg)
                creating = false
                editing = null
                revision++
            },
            onConnect = { cfg ->
                creating = false
                editing = null
                vm.connectServer(cfg)
            },
        )
    }

    if (urlDialog) {
        UrlDownloadDialog(
            onDismiss = { urlDialog = false },
            onDownload = { url, name ->
                urlDialog = false
                vm.downloadFromUrl(url, name)
                onBack()
            },
        )
    }

    infoDialog?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { infoDialog = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text(title) },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { infoDialog = null }) { Text("Entendido") } },
        )
    }
}

@Composable
private fun CloudCard(drawable: Int, name: String, status: String, detail: String, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = BD.SurfaceCard),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(BD.Violet.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(drawable),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(50), color = BD.Warning.copy(alpha = 0.18f)) {
                        Text(
                            status,
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = BD.Warning,
                            fontSize = 11.sp,
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall, color = BD.TextLow)
            }
        }
    }
}

/** Formulario de servidor FTP, con el selector de tipo de aparato abajo a la izquierda. */
@Composable
private fun ServerDialog(
    initial: FtpServerConfig?,
    onDismiss: () -> Unit,
    onTest: (FtpServerConfig, (String, String) -> Unit) -> Unit,
    onSave: (FtpServerConfig) -> Unit,
    onConnect: (FtpServerConfig) -> Unit,
) {
    var type by remember { mutableStateOf(initial?.deviceType ?: FtpDeviceType.PC_OTHER) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 21).toString()) }
    var user by remember { mutableStateOf(initial?.user ?: type.defaultUser) }
    var pass by remember { mutableStateOf(initial?.password ?: type.defaultPass) }
    var passive by remember { mutableStateOf(initial?.passive ?: type.defaultPassive) }
    var startPath by remember { mutableStateOf(initial?.startPath ?: type.startPath) }
    var connections by remember { mutableStateOf(initial?.connections ?: type.defaultConnections) }
    var typeMenu by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testLog by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    /** Al elegir un aparato se rellena todo con sus valores típicos. */
    fun applyType(t: FtpDeviceType) {
        type = t
        user = t.defaultUser
        pass = t.defaultPass
        passive = t.defaultPassive
        startPath = t.startPath
        connections = t.defaultConnections
        port = "21"
        testResult = null
        testLog = null
    }

    fun build() = FtpServerConfig(
        name = name.trim().ifEmpty { host.trim() },
        host = host.trim(),
        port = port.toIntOrNull() ?: 21,
        user = user,
        password = pass,
        deviceType = type,
        passive = passive,
        startPath = startPath.trim().ifEmpty { "/" },
        connections = connections,
    )

    val valid = host.isNotBlank()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = BD.SurfaceCard) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text(
                    if (initial == null) "Servidor FTP nuevo" else "Editar servidor",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(14.dp))

                Field("Nombre", name, "Mi Xbox del salón") { name = it }
                Field("Dirección IP o nombre", host, "192.168.1.50") { host = it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        Field("Puerto", port, "21", keyboard = KeyboardType.Number) { port = it }
                    }
                    Box(Modifier.weight(2f)) {
                        Field("Carpeta de inicio", startPath, "/") { startPath = it }
                    }
                }
                Field("Usuario", user, "") { user = it }
                Field("Contraseña", pass, "") { pass = it }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Switch(checked = passive, onCheckedChange = { passive = it })
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Modo pasivo", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (passive) "Lo normal. Si no lista nada, pruébalo desactivado."
                            else "Modo activo: para equipos que no admiten el pasivo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BD.TextLow,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Conexiones a la vez", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (type.connectionsLocked) {
                                "Fijado en 1: las consolas se cuelgan si se les piden varias cosas a la vez."
                            } else {
                                "Más conexiones van más rápido, pero algunos servidores no lo admiten."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (type.connectionsLocked) BD.Warning else BD.TextLow,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!type.connectionsLocked) {
                            TextButton(
                                onClick = { if (connections > 1) connections-- },
                                enabled = connections > 1,
                            ) { Text("−", fontSize = 20.sp) }
                        }
                        Surface(shape = RoundedCornerShape(10.dp), color = BD.SurfaceHigh) {
                            Text(
                                "${if (type.connectionsLocked) 1 else connections}",
                                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                color = BD.Cyan,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (!type.connectionsLocked) {
                            TextButton(
                                onClick = { if (connections < 4) connections++ },
                                enabled = connections < 4,
                            ) { Text("+", fontSize = 18.sp) }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BD.SurfaceHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            type.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = BD.TextLow,
                        )
                        if (type.knownCredentials.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Si el usuario que pongas falla, se prueban solos estos:",
                                style = MaterialTheme.typography.bodySmall,
                                color = BD.CyanSoft,
                            )
                            type.knownCredentials.forEach { c ->
                                Text(
                                    "• ${c.user} / ${c.password}  (${c.from})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BD.TextLow,
                                )
                            }
                        }
                    }
                }

                testResult?.let {
                    val ok = it.startsWith("Conectado")
                    Spacer(Modifier.height(10.dp))
                    Surface(shape = RoundedCornerShape(14.dp), color = BD.SurfaceHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) BD.Success else BD.Danger,
                            )
                            if (!testLog.isNullOrBlank()) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = { showLog = !showLog },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                ) {
                                    Text(
                                        if (showLog) "Ocultar el detalle de la conexión"
                                        else "Ver el detalle de la conexión",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BD.Cyan,
                                    )
                                }
                                if (showLog) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = BD.Night,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            testLog!!,
                                            Modifier
                                                .heightIn(max = 220.dp)
                                                .verticalScroll(rememberScrollState())
                                                .padding(10.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = BD.TextLow,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Abajo: a la izquierda el tipo de aparato; a la derecha cancelar y aceptar.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { typeMenu = true }, shape = RoundedCornerShape(14.dp)) {
                            Text(
                                when (type) {
                                    FtpDeviceType.XBOX360 -> "🎮 Xbox 360"
                                    FtpDeviceType.PS3 -> "🎮 PS3"
                                    FtpDeviceType.ROUTER_NAS -> "📡 Router/NAS"
                                    FtpDeviceType.PC_OTHER -> "💻 PC u otro"
                                },
                                fontSize = 13.sp,
                            )
                        }
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            Text(
                                "TIPO DE DISPOSITIVO",
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = BD.Violet,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            FtpDeviceType.entries.forEach { t ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(t.label)
                                            Text(
                                                t.hint.take(60) + "…",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = BD.TextLow,
                                            )
                                        }
                                    },
                                    onClick = { applyType(t); typeMenu = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancelar", color = BD.TextLow) }
                    Button(
                        onClick = { onSave(build()); },
                        enabled = valid,
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Aceptar") }
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            testing = true
                            testResult = "Probando…"
                            testLog = null
                            onTest(build()) { text, logText ->
                                testResult = text
                                testLog = logText
                                testing = false
                            }
                        },
                        enabled = valid && !testing,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text("Probar conexión") }
                    Button(
                        onClick = { onConnect(build()) },
                        enabled = valid,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text("Conectar") }
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = BD.TextLow) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun UrlDownloadDialog(onDismiss: () -> Unit, onDownload: (String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Descargar desde un enlace") },
        text = {
            Column {
                Field("Enlace", url, "https://…") { url = it }
                Field("Nombre (opcional)", name, "Se coge del enlace si lo dejas vacío") { name = it }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Se guardará en la carpeta que tengas abierta. Tiene que ser un enlace directo al " +
                        "archivo; si lleva a una página web, la app te avisa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BD.TextLow,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onDownload(url, name) },
                enabled = url.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Descargar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private const val MEDIAFIRE_HELP =
    "Cómo bajar de MediaFire ahora mismo:\n\n" +
        "1. Abre el enlace de MediaFire en el navegador del móvil.\n" +
        "2. Mantén pulsado el botón verde de descarga y elige «Copiar enlace».\n" +
        "3. Vuelve aquí, entra en «Descargar desde un enlace» y pégalo.\n\n" +
        "Por qué no se puede entrar con la cuenta: MediaFire solo deja usar su sistema a las " +
        "aplicaciones registradas con una clave suya, y esta app no la tiene."

private const val MEGA_HELP =
    "Mega guarda todo cifrado y el descifrado se hace en el propio aparato, con un sistema " +
        "hecho por ellos.\n\n" +
        "Para que BD Files pudiera entrar en tu cuenta habría que programar ese cifrado entero " +
        "(claves, sesiones y descarga por trozos). Es un proyecto aparte y bastante largo, así que " +
        "prefiero no dejarlo a medias.\n\n" +
        "Si te interesa, se puede hacer en otra fase y empezando por los enlaces públicos, " +
        "que son la parte más sencilla."
