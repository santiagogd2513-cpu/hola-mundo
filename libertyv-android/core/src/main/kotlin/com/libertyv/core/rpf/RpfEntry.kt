package com.libertyv.core.rpf

import com.libertyv.core.ConsolePlatform
import com.libertyv.core.io.DataSource

sealed class RpfEntry(name: String) {
    var name: String = name
        internal set

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
    private val items = ArrayList<RpfEntry>()
    private var displayCache: List<RpfEntry>? = null

    init {
        children.forEach { add(it) }
    }

    /** Hijos ordenados para mostrar: carpetas primero y luego por nombre. */
    val children: List<RpfEntry>
        get() = displayCache ?: items.sortedWith(
            compareBy<RpfEntry> { it !is RpfDirectory }.thenBy { it.name.lowercase() },
        ).also { displayCache = it }

    /** Hijos en el orden en que se guardan en el RPF (orden ordinal de bytes, como LibertyV). */
    val ordinalChildren: List<RpfEntry>
        get() = items.sortedWith { a, b -> compareOrdinal(a.name, b.name) }

    fun get(name: String): RpfEntry? = items.firstOrNull { it.name == name }

    /** Busca por ruta relativa con "/" ("a/b/c.txt"). */
    fun resolve(path: String): RpfEntry? {
        var cur: RpfEntry = this
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            cur = (cur as? RpfDirectory)?.get(part) ?: return null
        }
        return cur
    }

    internal fun add(e: RpfEntry) {
        e.parent = this
        items.add(e)
        displayCache = null
    }

    internal fun remove(e: RpfEntry) {
        items.remove(e)
        e.parent = null
        displayCache = null
    }

    internal fun invalidate() {
        displayCache = null
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

    fun isAncestorOf(e: RpfEntry): Boolean {
        var p = e.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }

    companion object {
        fun compareOrdinal(a: String, b: String): Int {
            val n = minOf(a.length, b.length)
            for (i in 0 until n) {
                val d = a[i].code - b[i].code
                if (d != 0) return d
            }
            return a.length - b.length
        }
    }
}

/** Entrada con datos (archivo normal o recurso). */
sealed class RpfFileEntry(
    name: String,
    /** Posición de los datos en el RPF (bytes); -1 si el archivo se importó y aún no se guardó. */
    val offset: Long,
    /** Bytes que ocupa dentro del RPF; -1 si aún no se sabe (importado). */
    val storedSize: Long,
    /**
     * Datos importados del móvil (contenido ya listo, sin comprimir ni cifrar).
     * null = los datos están en el RPF original.
     */
    val imported: DataSource?,
) : RpfEntry(name) {
    /** Tamaño una vez extraído. */
    abstract val size: Long
    abstract val isCompressed: Boolean
    val isImported: Boolean get() = imported != null
}

class RpfRegularFile(
    name: String,
    offset: Long,
    storedSize: Long,
    val uncompressedSize: Long,
    override val isCompressed: Boolean,
    val isEncrypted: Boolean,
    imported: DataSource? = null,
) : RpfFileEntry(name, offset, storedSize, imported) {
    override val size: Long get() = uncompressedSize

    companion object {
        private val NEVER_COMPRESS = setOf("rpf", "xpm", "cpm", "bik", "awc")

        /** Regla de LibertyV: se comprime todo salvo archivos pequeños y algunos tipos. */
        fun shouldCompress(name: String, size: Long): Boolean {
            if (name == "introscrn.gfx") return false
            if (size <= 256) return false
            return name.substringAfterLast('.', "").lowercase() !in NEVER_COMPRESS
        }

        /** Archivo nuevo a partir de datos del móvil. Comprimido implica cifrado, como en LibertyV. */
        fun imported(name: String, data: DataSource, compress: Boolean) =
            RpfRegularFile(name, -1, -1, data.size, compress, compress, data)
    }
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
    /** Para recursos importados: los datos sin la cabecera RSC7. */
    imported: DataSource? = null,
) : RpfFileEntry(name, offset, storedSize, imported) {
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

        /**
         * Si [data] es un archivo RSC7 válido para [platform] (cabecera + páginas con el tamaño exacto),
         * devuelve (flag system, flag graphics). Si no, null.
         */
        fun parseRsc7(data: DataSource, platform: ConsolePlatform): Pair<Int, Int>? {
            if (data.size < 16) return null
            val h = data.readFully(0, 16)
            if (String(h, 0, 4, Charsets.ISO_8859_1) != "RSC7") return null
            fun be(o: Int) = ((h[o].toInt() and 0xFF) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or
                ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
            val version = be(4)
            val sys = be(8)
            val gfx = be(12)
            if (versionFromFlags(sys, gfx) != version) return null
            val expected = sizeFromFlag(sys, platform.systemPageBase) + sizeFromFlag(gfx, platform.graphicsPageBase)
            if (expected + 16 != data.size) return null
            return sys to gfx
        }
    }
}
