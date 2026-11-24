package com.bdfiles.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bdfiles.core.FileItem
import com.bdfiles.core.formatDate
import com.bdfiles.core.formatSize
import com.bdfiles.ui.theme.BD

/** Emoji e color según el tipo de archivo. */
object FileLook {
    private val byExt: Map<String, Pair<String, Color>> = buildMap {
        listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg", "avif").forEach {
            put(it, "🖼️" to BD.Pink)
        }
        listOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "m4v", "flv", "wmv").forEach {
            put(it, "🎬" to BD.Violet)
        }
        listOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus", "awc", "mid").forEach {
            put(it, "🎵" to BD.Cyan)
        }
        listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz").forEach {
            put(it, "🗜️" to BD.Warning)
        }
        listOf("txt", "log", "md", "ini", "cfg", "conf", "csv", "nfo").forEach {
            put(it, "📝" to BD.LightPurple)
        }
        listOf("xml", "json", "yml", "yaml", "html", "css", "js", "kt", "java", "py", "c", "cpp", "lua", "meta").forEach {
            put(it, "🧾" to BD.CyanSoft)
        }
        put("pdf", "📕" to BD.Danger)
        put("apk", "📦" to BD.Success)
        put("xapk", "📦" to BD.Success)
        put("jar", "☕" to BD.Warning)
        put("dat", "💾" to BD.LightPurple)
        put("rpf", "🧱" to BD.Violet)
        put("iso", "💿" to BD.CyanSoft)
        put("img", "💿" to BD.CyanSoft)
        put("exe", "⚙️" to BD.TextLow)
        put("ttf", "🔤" to BD.PinkSoft)
        put("otf", "🔤" to BD.PinkSoft)
    }

    fun emoji(item: FileItem): String =
        if (item.isDir) "📁" else byExt[item.extension]?.first ?: "📄"

    fun tint(item: FileItem): Color =
        if (item.isDir) BD.Violet else byExt[item.extension]?.second ?: BD.TextLow
}

/** Una fila de la lista: icono o miniatura, nombre y datos debajo. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    showThumbnails: Boolean,
    colorfulIcons: Boolean,
    compact: Boolean,
    pinned: Boolean,
    favorite: Boolean,
    subtitleOverride: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val context = LocalContext.current
    val thumb = rememberThumbnail(context, item, showThumbnails)
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "fila",
    )
    val vertical = if (compact) 7.dp else 11.dp

    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 14.dp, end = 4.dp, top = vertical, bottom = vertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (compact) 38.dp else 44.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    if (thumb != null) Color.Transparent
                    else if (colorfulIcons) FileLook.tint(item).copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(FileLook.emoji(item), fontSize = if (compact) 19.sp else 22.sp)
            }
            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (pinned) {
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Filled.PushPin, "Anclado", tint = BD.Cyan, modifier = Modifier.size(13.dp))
                }
                if (favorite) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Star, "Favorito", tint = BD.Warning, modifier = Modifier.size(13.dp))
                }
            }
            val subtitle = subtitleOverride ?: buildString {
                if (item.isDir) append("Carpeta") else append(formatSize(item.size))
                val d = formatDate(item.lastModified)
                if (d.isNotEmpty()) append("  ·  ").append(d)
                if (item.readOnly) append("  ·  solo lectura")
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!selectionMode) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .combinedClickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                Text("⋮", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** Barra de progreso con el degradado rosa → violeta → cian. */
@Composable
fun GradientProgressBar(fraction: Float?, indeterminate: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, BD.Outline.copy(alpha = 0.6f), shape),
    ) {
        if (indeterminate || fraction == null) {
            val width = maxWidth
            val transition = rememberInfiniteTransition(label = "indeterminado")
            val x by transition.animateFloat(
                initialValue = -0.5f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(tween(1300)),
                label = "desplazamiento",
            )
            Box(
                Modifier
                    .offset(x = width * x)
                    .fillMaxWidth(0.5f)
                    .fillMaxSize()
                    .clip(shape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, BD.Pink, BD.Violet, BD.Cyan, Color.Transparent),
                        ),
                    ),
            )
        } else {
            val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "progreso")
            Box(
                Modifier
                    .fillMaxWidth(animated.coerceAtLeast(0.015f))
                    .fillMaxSize()
                    .clip(shape)
                    .background(BD.gradient),
            )
        }
    }
}

/** Cabecera de sección en los ajustes. */
@Composable
fun SectionHeader(text: String, emoji: String = "") {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (emoji.isNotEmpty()) {
            Text(emoji, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = BD.Violet,
            fontSize = 12.sp,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(BD.gradientSoft),
        )
    }
}

/** Fila de ajuste con título, descripción y un control a la derecha. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.combinedClickableCompat(onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)
