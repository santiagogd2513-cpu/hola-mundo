package com.libertyv.app

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libertyv.app.audio.NativeAudio
import com.libertyv.core.ConsolePlatform
import com.libertyv.core.audio.AwcFile
import com.libertyv.core.audio.AwcTrack
import com.libertyv.core.audio.WavWriter
import com.libertyv.core.io.DataSource
import com.libertyv.core.io.copyCount
import com.libertyv.core.rpf.ExportProgress
import com.libertyv.core.rpf.ResourceExportMode
import com.libertyv.core.rpf.Rpf7Archive
import com.libertyv.core.rpf.RpfDirectory
import com.libertyv.core.rpf.RpfEntry
import com.libertyv.core.rpf.RpfException
import com.libertyv.core.rpf.RpfExporter
import com.libertyv.core.rpf.RpfFileEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Un RPF abierto (puede ser un RPF dentro de otro). */
class OpenArchive(val archive: Rpf7Archive, val title: String, private val tempFile: File?) {
    fun close() {
        runCatching { archive.close() }
        tempFile?.delete()
    }
}

data class BusyState(val title: String, val detail: String = "", val progress: Float? = null)

class AwcState(val entry: RpfFileEntry, val awc: AwcFile, val platform: ConsolePlatform, val source: DataSource, val tempFile: File)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val keyStore = KeyStore(ctx)
    private val settings = Settings(ctx)

    var keys by mutableStateOf(keyStore.loadAll())
        private set
    var resourceMode by mutableStateOf(settings.resourceMode)
        private set

    val stack = mutableStateListOf<OpenArchive>()
    var currentDir by mutableStateOf<RpfDirectory?>(null)
        private set
    var search by mutableStateOf("")
    var selected by mutableStateOf<RpfFileEntry?>(null)
    var message by mutableStateOf<String?>(null)
    var textPreview by mutableStateOf<Pair<String, String>?>(null)
    var awcState by mutableStateOf<AwcState?>(null)
        private set
    var playingTrack by mutableStateOf<Int?>(null)
        private set

    /** Lo que se exportará cuando el usuario elija destino en el selector de Android. */
    var pendingExport: RpfEntry? = null

    private val _busy = MutableStateFlow<BusyState?>(null)
    val busy: StateFlow<BusyState?> = _busy
    private var job: Job? = null
    private var player: MediaPlayer? = null

    val current: OpenArchive? get() = stack.lastOrNull()

    fun chooseResourceMode(m: ResourceExportMode) {
        resourceMode = m
        settings.resourceMode = m
    }

    // ---------- tareas en segundo plano ----------

    private fun runTask(title: String, block: suspend CoroutineScope.() -> Unit) {
        if (job?.isActive == true) return
        _busy.value = BusyState(title)
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { message = "Operación cancelada." }
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) { message = "No hay memoria suficiente para esta operación." }
            } catch (e: Exception) {
                val text = when (e) {
                    is UserMessage, is RpfException -> e.message
                    else -> "${e.message ?: e.javaClass.simpleName}"
                }
                withContext(Dispatchers.Main) { message = text }
            } finally {
                _busy.value = null
            }
        }
    }

    fun cancelTask() {
        job?.cancel()
    }

    private var lastUpdate = 0L

    private fun progress(title: String, detail: String, fraction: Float?) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 80 && fraction != null && fraction < 1f) return
        lastUpdate = now
        _busy.value = BusyState(title, detail, fraction)
    }

    // ---------- claves ----------

    fun loadKey(uri: Uri) = runTask("Cargando clave") {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("No se pudo leer el archivo")
        val p = keyStore.save(bytes)
        withContext(Dispatchers.Main) {
            keys = keyStore.loadAll()
            message = "Clave de ${p.displayName} cargada correctamente."
        }
    }

    fun deleteKey(p: ConsolePlatform) {
        keyStore.delete(p)
        keys = keyStore.loadAll()
    }

    // ---------- abrir RPF ----------

    fun openRpf(uri: Uri) = runTask("Abriendo RPF") {
        val name = ctx.contentResolver.displayName(uri)
        val src = ctx.contentResolver.openDataSource(uri)
        try {
            val archive = openArchive(src, name)
            withContext(Dispatchers.Main) {
                closeAll()
                push(OpenArchive(archive, name, null))
            }
        } catch (e: Throwable) {
            src.close()
            throw e
        }
    }

    private fun openArchive(src: DataSource, name: String): Rpf7Archive {
        val platform = Rpf7Archive.detectPlatform(src)
            ?: throw UserMessage("“$name” no es un archivo RPF7 de PS3 o Xbox 360.")
        val key = keys[platform]
            ?: throw UserMessage(
                "“$name” es de ${platform.displayName} y falta su clave. " +
                    "Carga ${platform.keyFileName} en la pantalla principal.",
            )
        return Rpf7Archive.open(src, platform, key, name)
    }

    private fun push(a: OpenArchive) {
        stack.add(a)
        currentDir = a.archive.root
        search = ""
    }

    fun openNestedRpf(entry: RpfFileEntry) {
        val parent = current ?: return
        runTask("Abriendo ${entry.name}") {
            val slice = parent.archive.plainSlice(entry)
            if (slice != null) {
                val a = openArchive(slice, entry.name)
                withContext(Dispatchers.Main) { push(OpenArchive(a, entry.name, null)) }
            } else {
                val tmp = File.createTempFile("nested", ".rpf", ctx.cacheDir)
                try {
                    extractTo(parent.archive, entry, tmp, "Extrayendo ${entry.name}")
                    val a = openArchive(fileDataSource(tmp), entry.name)
                    withContext(Dispatchers.Main) { push(OpenArchive(a, entry.name, tmp)) }
                } catch (e: Throwable) {
                    tmp.delete()
                    throw e
                }
            }
        }
    }

    private suspend fun extractTo(archive: Rpf7Archive, entry: RpfFileEntry, file: File, title: String) {
        val scope = coroutineContext
        BufferedOutputStream(file.outputStream(), 256 * 1024).use { out ->
            archive.openContent(entry).use { input ->
                input.copyCount(out) { n ->
                    scope.ensureActive()
                    progress(title, formatSize(n), if (entry.size > 0) n.toFloat() / entry.size else null)
                }
            }
        }
    }

    // ---------- navegación ----------

    fun enter(dir: RpfDirectory) {
        currentDir = dir
        search = ""
    }

    /** Vuelve atrás. Devuelve false si ya no hay nada abierto. */
    fun back(): Boolean {
        val dir = currentDir ?: return false
        if (search.isNotEmpty()) {
            search = ""
            return true
        }
        val parent = dir.parent
        if (parent != null) {
            currentDir = parent
            return true
        }
        val top = stack.removeLastOrNull() ?: return false
        top.close()
        currentDir = current?.archive?.root
        return true
    }

    fun closeAll() {
        stopAudio()
        while (stack.isNotEmpty()) stack.removeAt(stack.lastIndex).close()
        currentDir = null
        search = ""
    }

    // ---------- exportar ----------

    private fun exporter(a: Rpf7Archive) = RpfExporter(a, resourceMode)

    /** true si el archivo se exporta en varios archivos (y por tanto necesita una carpeta). */
    fun needsFolder(entry: RpfFileEntry): Boolean {
        val a = current?.archive ?: return false
        return exporter(a).outputNames(entry).size > 1
    }

    fun suggestedName(entry: RpfFileEntry): String {
        val a = current?.archive ?: return entry.name
        return exporter(a).outputNames(entry).first()
    }

    fun exportFileTo(entry: RpfFileEntry, uri: Uri) {
        val a = current?.archive ?: return
        runTask("Exportando ${entry.name}") {
            val scope = coroutineContext
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No se pudo escribir")
            BufferedOutputStream(out, 256 * 1024).use { o ->
                exporter(a).write(entry, o) { n ->
                    scope.ensureActive()
                    progress("Exportando ${entry.name}", formatSize(n), if (entry.size > 0) n.toFloat() / entry.size else null)
                }
            }
            withContext(Dispatchers.Main) { message = "“${entry.name}” exportado." }
        }
    }

    fun exportToFolder(entry: RpfEntry, treeUri: Uri) {
        val a = current?.archive ?: return
        runTask("Exportando") {
            val scope = coroutineContext
            val target = DocumentTreeTarget(ctx, treeUri)
            when (entry) {
                is RpfFileEntry -> {
                    exporter(a).exportTo(entry, target) { n ->
                        scope.ensureActive()
                        progress("Exportando ${entry.name}", formatSize(n), if (entry.size > 0) n.toFloat() / entry.size else null)
                    }
                    withContext(Dispatchers.Main) { message = "“${entry.name}” exportado." }
                }
                is RpfDirectory -> {
                    val errors = exporter(a).exportDirectory(entry, target) { p: ExportProgress ->
                        scope.ensureActive()
                        progress(
                            "Exportando ${minOf(p.doneFiles + 1, p.totalFiles)} de ${p.totalFiles}",
                            p.currentName,
                            if (p.totalBytes > 0) p.doneBytes.toFloat() / p.totalBytes else null,
                        )
                    }
                    val n = entry.allFiles().size
                    withContext(Dispatchers.Main) {
                        message = if (errors.isEmpty()) {
                            "Exportados $n archivos."
                        } else {
                            "Exportados ${n - errors.size} de $n archivos. Fallaron:\n" +
                                errors.take(15).joinToString("\n") { "• ${it.first}: ${it.second}" } +
                                if (errors.size > 15) "\n…y ${errors.size - 15} más" else ""
                        }
                    }
                }
            }
        }
    }

    // ---------- ver contenido ----------

    fun preview(entry: RpfFileEntry) {
        val a = current?.archive ?: return
        runTask("Leyendo ${entry.name}") {
            val limit = 256 * 1024
            val bytes = a.openContent(entry).use { input ->
                val buf = ByteArray(minOf(entry.size, limit.toLong()).toInt())
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                buf.copyOf(n)
            }
            val text = if (looksLikeText(bytes)) {
                String(bytes, Charsets.UTF_8) + if (entry.size > limit) "\n\n[… vista previa limitada a 256 KB]" else ""
            } else {
                hexDump(bytes, 4096) + if (bytes.size > 4096) "\n[… solo los primeros 4 KB]" else ""
            }
            withContext(Dispatchers.Main) { textPreview = entry.name to text }
        }
    }

    // ---------- audio AWC ----------

    fun openAwc(entry: RpfFileEntry) {
        val a = current?.archive ?: return
        runTask("Abriendo audio ${entry.name}") {
            val tmp = File(ctx.cacheDir, "audio.awc")
            withContext(Dispatchers.Main) { closeAwc() }
            extractTo(a, entry, tmp, "Leyendo ${entry.name}")
            val src = fileDataSource(tmp)
            try {
                val awc = AwcFile(src, a.platform)
                withContext(Dispatchers.Main) { awcState = AwcState(entry, awc, a.platform, src, tmp) }
            } catch (e: Throwable) {
                src.close()
                throw UserMessage("No se pudo leer el AWC: ${e.message}")
            }
        }
    }

    fun closeAwc() {
        stopAudio()
        awcState?.let {
            runCatching { it.source.close() }
            it.tempFile.delete()
        }
        awcState = null
    }

    private suspend fun decodeToWav(st: AwcState, track: AwcTrack, out: java.io.OutputStream, title: String) {
        val scope = coroutineContext
        var written = 0L
        val total = track.sampleCount * 2
        val counting = object : java.io.FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
                written += len
                progress(title, "Pista ${track.id}", if (total > 0) written.toFloat() / total else null)
            }
        }
        WavWriter.writeTrack(track, st.platform, NativeAudio.factory, counting) { scope.ensureActive() }
        counting.flush()
    }

    fun playTrack(track: AwcTrack) {
        val st = awcState ?: return
        stopAudio()
        runTask("Decodificando audio") {
            val wav = File(ctx.cacheDir, "play.wav")
            BufferedOutputStream(wav.outputStream(), 256 * 1024).use { decodeToWav(st, track, it, "Decodificando audio") }
            withContext(Dispatchers.Main) {
                val mp = MediaPlayer()
                mp.setDataSource(wav.absolutePath)
                mp.setOnCompletionListener { stopAudio() }
                mp.prepare()
                mp.start()
                player = mp
                playingTrack = track.id
            }
        }
    }

    fun stopAudio() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playingTrack = null
    }

    fun exportAwcToFolder(treeUri: Uri) {
        val st = awcState ?: return
        runTask("Exportando WAV") {
            val target = DocumentTreeTarget(ctx, treeUri)
            for (t in st.awc.tracks) {
                val name = "${st.entry.name}.${t.id}.wav"
                BufferedOutputStream(target.create(name), 256 * 1024).use { decodeToWav(st, t, it, "Exportando $name") }
            }
            withContext(Dispatchers.Main) { message = "Exportadas ${st.awc.tracks.size} pistas WAV." }
        }
    }

    override fun onCleared() {
        closeAwc()
        closeAll()
        super.onCleared()
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun looksLikeText(b: ByteArray): Boolean {
    if (b.isEmpty()) return true
    val sample = b.size.coerceAtMost(4096)
    var bad = 0
    for (i in 0 until sample) {
        val c = b[i].toInt() and 0xFF
        if (c == 0) return false
        if (c < 9 || (c in 14..31)) bad++
    }
    return bad * 20 < sample
}

private fun hexDump(b: ByteArray, max: Int): String {
    val sb = StringBuilder()
    val n = minOf(b.size, max)
    var i = 0
    while (i < n) {
        sb.append("%08X  ".format(i))
        for (j in 0 until 16) {
            if (i + j < n) sb.append("%02X ".format(b[i + j])) else sb.append("   ")
        }
        sb.append(' ')
        for (j in 0 until 16) {
            if (i + j >= n) break
            val c = b[i + j].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        sb.append('\n')
        i += 16
    }
    return sb.toString()
}
