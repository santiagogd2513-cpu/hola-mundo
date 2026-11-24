package com.bdfiles.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * Los almacenamientos del móvil: interno, tarjetas y memorias USB.
 *
 * Android ya no deja entrar a las memorias USB por su ruta, ni con el permiso de
 * "todos los archivos": hay que pedirle acceso al sistema una vez. Por eso cada
 * almacenamiento dice si se puede abrir directamente o si hace falta dar permiso.
 */
object StorageVolumes {

    class Volume(
        val title: String,
        /** Ruta de siempre, si se puede leer. null cuando hace falta permiso. */
        val path: String?,
        val removable: Boolean,
        val isUsb: Boolean,
        /** Identificador estable del almacenamiento (para recordar el permiso). */
        val key: String,
        /** Para pedirle acceso al sistema cuando no hay ruta. */
        val accessIntent: Intent?,
    ) {
        val needsPermission: Boolean get() = path == null
    }

    fun list(context: Context): List<Volume> {
        val out = ArrayList<Volume>()
        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        out.add(Volume("Almacenamiento interno", internalPath, false, false, "internal", null))

        val sm = runCatching { context.getSystemService(StorageManager::class.java) }.getOrNull()
        if (sm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            for (v in runCatching { sm.storageVolumes }.getOrDefault(emptyList())) {
                addVolume(context, v, internalPath, out)
            }
        }

        // Por si el sistema no las declara: rutas donde se suelen montar.
        for (base in listOf("/storage", "/mnt/media_rw")) {
            val kids = File(base).listFiles() ?: continue
            for (k in kids) {
                val p = k.absolutePath
                if (!k.isDirectory) continue
                if (k.name in setOf("emulated", "self", "sdcard0", "container", "knox-emulated")) continue
                if (out.any { it.path == p || it.key == k.name }) continue
                if (!k.canRead()) continue
                val looksLikeUsb = k.name.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))
                out.add(
                    Volume(
                        title = if (looksLikeUsb) "Memoria externa ${k.name}" else "Almacenamiento ${k.name}",
                        path = p,
                        removable = true,
                        isUsb = base == "/mnt/media_rw",
                        key = k.name,
                        accessIntent = null,
                    ),
                )
            }
        }
        return out
    }

    private fun addVolume(
        context: Context,
        v: StorageVolume,
        internalPath: String,
        out: ArrayList<Volume>,
    ) {
        if (runCatching { v.state }.getOrNull() != Environment.MEDIA_MOUNTED) return
        if (v.isPrimary) return // ya está como almacenamiento interno

        val description = runCatching { v.getDescription(context) }.getOrNull().orEmpty()
        val uuid = runCatching { v.uuid }.getOrNull()
        val key = uuid ?: description.ifEmpty { "volumen${out.size}" }
        if (out.any { it.key == key }) return

        // Ruta real, cuando el sistema la da y de verdad se puede leer.
        val dir: File? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> runCatching { v.directory }.getOrNull()
            else -> runCatching {
                val m = StorageVolume::class.java.getDeclaredMethod("getPathFile")
                m.isAccessible = true
                m.invoke(v) as? File
            }.getOrNull()
        }
        val usablePath = dir?.takeIf { it.isDirectory && it.canRead() && (it.listFiles() != null) }

        val looksUsb = description.contains("USB", true) ||
            description.contains("unidad", true) ||
            (uuid?.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) == true && !description.contains("SD", true))

        val title = when {
            description.isNotEmpty() -> description
            looksUsb -> "Memoria USB"
            else -> "Tarjeta de memoria"
        }

        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { v.createOpenDocumentTreeIntent() }.getOrNull()
        } else {
            null
        }

        out.add(
            Volume(
                title = title,
                path = usablePath?.absolutePath,
                removable = runCatching { v.isRemovable }.getOrDefault(true),
                isUsb = looksUsb,
                key = key,
                accessIntent = intent,
            ),
        )
    }

    /** Intención genérica para elegir un almacenamiento, si el sistema no da una concreta. */
    fun genericAccessIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

    /** Nombre bonito a partir de una dirección de documento, cuando no se sabe más. */
    fun titleFromUri(uri: Uri): String {
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val name = id?.substringBefore(':')?.takeIf { it.isNotBlank() }
        return when {
            name == null -> "Almacenamiento externo"
            name.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "Memoria externa $name"
            name == "primary" -> "Almacenamiento interno"
            else -> "Almacenamiento $name"
        }
    }
}
