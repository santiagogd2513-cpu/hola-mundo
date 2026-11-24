package com.bdfiles.core

import android.content.Context
import android.os.Environment
import java.io.File

/** Descubre los almacenamientos disponibles: interno, tarjetas SD y USB. */
object StorageVolumes {
    class Volume(val title: String, val path: String, val removable: Boolean)

    fun list(context: Context): List<Volume> {
        val out = ArrayList<Volume>()
        val internal = Environment.getExternalStorageDirectory().absolutePath
        out.add(Volume("Almacenamiento interno", internal, false))

        // Carpetas de la app en otros volúmenes: de ahí se saca la raíz de cada tarjeta.
        for (dir in context.getExternalFilesDirs(null).filterNotNull()) {
            val p = dir.absolutePath
            val cut = p.indexOf("/Android/data")
            if (cut <= 0) continue
            val root = p.substring(0, cut)
            if (root == internal || out.any { it.path == root }) continue
            val name = root.substringAfterLast('/')
            out.add(Volume("Tarjeta $name", root, true))
        }

        // Rutas habituales de USB OTG.
        for (p in listOf("/mnt/media_rw", "/storage")) {
            val dir = File(p)
            val kids = dir.listFiles() ?: continue
            for (k in kids) {
                val path = k.absolutePath
                if (!k.isDirectory || out.any { it.path == path }) continue
                if (k.name in setOf("emulated", "self", "sdcard0", "container", "knox-emulated")) continue
                if (!k.canRead()) continue
                out.add(Volume("USB / Tarjeta ${k.name}", path, true))
            }
        }
        return out
    }
}
