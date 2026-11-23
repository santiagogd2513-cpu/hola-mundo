package com.libertyv.core.rpf

import com.libertyv.core.ConsolePlatform

sealed class RpfEntry(val name: String) {
    var parent: RpfDirectory? = null
        internal set

    val path: String
        get() {
            val p = parent ?: return name
            return if (p.parent == null) name else "${p.path}/$name"
        }

    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()
}

class RpfDirectory(name: String, children: List<RpfEntry>) : RpfEntry(name) {
    val children: List<RpfEntry> = children.sortedWith(
        compareBy<RpfEntry> { it !is RpfDirectory }.thenBy { it.name.lowercase() },
    )

    init {
        children.forEach { it.parent = this }
    }

    /** Todos los archivos que cuelgan de esta carpeta (recursivo). */
    fun allFiles(): List<RpfFileEntry> {
        val out = ArrayList<RpfFileEntry>()
        fun walk(d: RpfDirectory) {
            for (c in d.children) when (c) {
                is RpfDirectory -> walk(c)
                is RpfFileEntry -> out.add(c)
            }
        }
        walk(this)
        return out
    }
}

/** Entrada con datos (archivo normal o recurso). */
sealed class RpfFileEntry(
    name: String,
    /** Posición de los datos en el RPF (bytes). */
    val offset: Long,
    /** Bytes que ocupa dentro del RPF. */
    val storedSize: Long,
) : RpfEntry(name) {
    /** Tamaño una vez extraído. */
    abstract val size: Long
    abstract val isCompressed: Boolean
}

class RpfRegularFile(
    name: String,
    offset: Long,
    storedSize: Long,
    val uncompressedSize: Long,
    override val isCompressed: Boolean,
    val isEncrypted: Boolean,
) : RpfFileEntry(name, offset, storedSize) {
    override val size: Long get() = uncompressedSize
}

/**
 * Recurso RSC7 (modelos, texturas, scripts...). Los datos son páginas "system" + "graphics"
 * comprimidas; los scripts (.xsc/.csc) además van cifrados.
 */
class RpfResource(
    name: String,
    offset: Long,
    storedSize: Long,
    val systemFlag: Int,
    val graphicsFlag: Int,
    val platform: ConsolePlatform,
) : RpfFileEntry(name, offset, storedSize) {
    /** Tipo sin la letra de plataforma: "xtd" -> "td", "xsc" -> "sc". */
    val resourceType: String get() = if (extension.length > 1) extension.substring(1) else ""

    val isEncrypted: Boolean get() = resourceType == "sc"

    val version: Int get() = versionFromFlags(systemFlag, graphicsFlag)

    val systemSize: Long get() = sizeFromFlag(systemFlag, platform.systemPageBase)

    val graphicsSize: Long get() = sizeFromFlag(graphicsFlag, platform.graphicsPageBase)

    override val size: Long get() = systemSize + graphicsSize

    override val isCompressed: Boolean get() = true

    companion object {
        fun versionFromFlags(systemFlag: Int, graphicsFlag: Int): Int =
            ((graphicsFlag ushr 28) and 0xF) or (((systemFlag ushr 28) and 0xF) shl 4)

        fun sizeFromFlag(flag: Int, baseSizeIn: Int): Long {
            val f = flag.toLong() and 0xFFFFFFFFL
            val baseSize = baseSizeIn.toLong() shl (f and 0xF).toInt()
            var size = (((f ushr 17) and 0x7F) +
                (((f ushr 11) and 0x3F) shl 1) +
                (((f ushr 7) and 0xF) shl 2) +
                (((f ushr 5) and 0x3) shl 3) +
                (((f ushr 4) and 0x1) shl 4)) * baseSize
            for (i in 0 until 4) {
                if (((f ushr (24 + i)) and 1L) == 1L) size += baseSize shr (1 + i)
            }
            return size
        }
    }
}
