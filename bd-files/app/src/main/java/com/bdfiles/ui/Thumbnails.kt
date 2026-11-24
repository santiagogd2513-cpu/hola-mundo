package com.bdfiles.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.bdfiles.core.FileItem
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Miniaturas e iconos: fotos, vídeos, PDF, APK y JAR (los mods de Minecraft
 * suelen llevar su propio icono dentro).
 */
object Thumbnails {

    private const val SIZE = 128
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    /** Archivos de los que ya se intentó sacar miniatura y no había. */
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "m4v", "flv", "ts", "wmv")
    private val APK = setOf("apk", "xapk", "apks")
    private val JAR = setOf("jar")

    fun canHaveThumbnail(item: FileItem): Boolean {
        if (item.isDir || item.sourceId != "local") return false
        val e = item.extension
        return e in IMAGE || e in VIDEO || e == "pdf" || e in APK || e in JAR
    }

    private fun key(item: FileItem) = "${item.path}|${item.lastModified}|${item.size}"

    fun cached(item: FileItem): Bitmap? = cache.get(key(item))

    suspend fun load(context: Context, item: FileItem): Bitmap? {
        val k = key(item)
        cache.get(k)?.let { return it }
        if (k in failed) return null
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(item.path)
                if (!f.isFile || f.length() == 0L) return@runCatching null
                when (item.extension) {
                    in IMAGE -> imageThumb(f)
                    in VIDEO -> videoThumb(f)
                    "pdf" -> pdfThumb(f)
                    in APK -> apkIcon(context, f)
                    in JAR -> jarIcon(f)
                    else -> null
                }
            }.getOrNull()
        }
        if (bmp == null) failed.add(k) else cache.put(k, bmp)
        return bmp
    }

    // ------------------------------------------------------------ imágenes

    private fun imageThumb(f: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > SIZE * 2 && bounds.outHeight / sample > SIZE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(f.absolutePath, opts)?.let { square(it) }
    }

    private fun videoThumb(f: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val frame = r.getFrameAtTime(1_000_000) ?: r.frameAtTime
            frame?.let { square(it) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun pdfThumb(f: File): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val ratio = page.height.toFloat() / page.width
                val w = SIZE
                val h = (SIZE * ratio).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(bmp).drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                square(bmp)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }

    private fun apkIcon(context: Context, f: File): Bitmap? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(f.absolutePath, 0) ?: return null
        info.applicationInfo?.let { app ->
            app.sourceDir = f.absolutePath
            app.publicSourceDir = f.absolutePath
            drawableToBitmap(app.loadIcon(pm))
        }
    }.getOrNull()

    /** Icono de dentro de un .jar: los mods de Minecraft usan pack.png, logo.png o icon.png. */
    private fun jarIcon(f: File): Bitmap? = runCatching {
        ZipFile(f).use { zip ->
            val candidates = listOf("pack.png", "logo.png", "icon.png", "logoFile.png", "mod_logo.png")
            // Primero los nombres típicos en la raíz, luego cualquier png pequeño que parezca icono.
            val entry = candidates.firstNotNullOfOrNull { zip.getEntry(it) }
                ?: zip.entries().asSequence().firstOrNull { e ->
                    val n = e.name.lowercase(Locale.ROOT)
                    !e.isDirectory && n.endsWith(".png") && e.size in 1..600_000 &&
                        (n.contains("logo") || n.contains("icon") || n.endsWith("pack.png"))
                }
                ?: return null
            zip.getInputStream(entry).use { input ->
                val bytes = input.readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { square(it) }
            }
        }
    }.getOrNull()

    private fun drawableToBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return square(d.bitmap)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, c.width, c.height)
        d.draw(c)
        return bmp
    }

    /** Recorta al centro y deja la miniatura cuadrada y pequeña. */
    private fun square(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        if (side <= 0) return src
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = if (src.width == src.height) src else Bitmap.createBitmap(src, x, y, side, side)
        val scaled = if (cropped.width > SIZE) {
            Bitmap.createScaledBitmap(cropped, SIZE, SIZE, true)
        } else {
            cropped
        }
        if (scaled !== src && cropped !== src && cropped !== scaled) cropped.recycle()
        return scaled
    }
}

/** Carga la miniatura en segundo plano y la devuelve cuando esté lista. */
@Composable
fun rememberThumbnail(context: Context, item: FileItem, enabled: Boolean): ImageBitmap? {
    if (!enabled || !Thumbnails.canHaveThumbnail(item)) return null
    var bitmap by remember(item.path, item.lastModified) {
        mutableStateOf(Thumbnails.cached(item)?.asImageBitmap())
    }
    LaunchedEffect(item.path, item.lastModified) {
        if (bitmap == null) {
            bitmap = Thumbnails.load(context, item)?.asImageBitmap()
        }
    }
    return bitmap
}
