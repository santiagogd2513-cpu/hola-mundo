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
 * Primero se intenta siempre abrirlos por su ruta de toda la vida, que es lo que
 * funciona en la mayoría de móviles cuando la app tiene el permiso de "todos los
 * archivos". Solo si esa ruta no se puede leer de verdad se pide permiso al
 * sistema, que es lo que exigen algunos móviles con Android reciente.
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

    /** Nombres de carpeta que no son almacenamientos de verdad. */
    private val IGNORE = setOf(
        "emulated", "self", "sdcard0", "sdcard", "container", "knox-emulated",
        "enc_emulated", "system_ce", "system_de", "usbotg-bind",
    )

    /** Una ruta sirve si existe, es carpeta y de verdad se puede listar. */
    private fun usable(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val f = File(path)
            if (f.isDirectory && f.listFiles() != null) path else null
        }.getOrNull()
    }

    private fun firstUsable(vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull { usable(it) }

    fun list(context: Context): List<Volume> {
        val byKey = LinkedHashMap<String, Volume>()
        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        byKey["internal"] = Volume("Almacenamiento interno", internalPath, false, false, "internal", null)

        // 1. Lo que declara el sistema: nombres de verdad y pistas de dónde está.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = runCatching { context.getSystemService(StorageManager::class.java) }.getOrNull()
            for (v in runCatching { sm?.storageVolumes }.getOrNull().orEmpty()) {
                fromSystem(context, v)?.let { byKey.putIfAbsent(it.key, it) }
            }
        }

        // 2. Rebuscar por las carpetas donde se montan: puede encontrar lo que el
        //    sistema no declara, y también dar ruta a lo que se quedó sin ella.
        for (base in listOf("/storage", "/mnt/media_rw", "/mnt/usb")) {
            val kids = runCatching { File(base).listFiles() }.getOrNull() ?: continue
            for (k in kids) {
                val name = k.name
                if (name in IGNORE) continue
                val path = usable(k.absolutePath) ?: continue
                if (path == internalPath) continue

                val existing = byKey.values.firstOrNull { it.key == name || it.path == path }
                if (existing != null) {
                    // Si ya estaba pero sin ruta, ahora sí la tenemos.
                    if (existing.path == null) {
                        byKey[existing.key] = Volume(
                            existing.title, path, existing.removable, existing.isUsb,
                            existing.key, existing.accessIntent,
                        )
                    }
                    continue
                }
                val looksLikeUuid = name.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))
                byKey[name] = Volume(
                    title = if (looksLikeUuid) "Memoria externa $name" else "Almacenamiento $name",
                    path = path,
                    removable = true,
                    isUsb = base != "/storage" || looksLikeUuid,
                    key = name,
                    accessIntent = null,
                )
            }
        }

        return byKey.values.toList()
    }

    private fun fromSystem(context: Context, v: StorageVolume): Volume? {
        if (runCatching { v.state }.getOrNull() != Environment.MEDIA_MOUNTED) return null
        if (runCatching { v.isPrimary }.getOrDefault(false)) return null

        val description = runCatching { v.getDescription(context) }.getOrNull().orEmpty()
        val uuid = runCatching { v.uuid }.getOrNull()
        val key = uuid ?: description.ifEmpty { "volumen" }

        // Se prueban todos los sitios donde puede estar, por orden.
        val fromApi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { v.directory?.absolutePath }.getOrNull()
        } else {
            null
        }
        val fromReflection = reflectPath(v)
        val path = firstUsable(
            fromApi,
            fromReflection,
            uuid?.let { "/storage/$it" },
            uuid?.let { "/mnt/media_rw/$it" },
        )

        val looksUsb = description.contains("USB", true) ||
            description.contains("unidad", true) ||
            (uuid?.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) == true &&
                !description.contains("SD", true) && !description.contains("tarjeta", true))

        val title = when {
            description.isNotEmpty() -> description
            looksUsb -> "Memoria USB"
            else -> "Tarjeta de memoria"
        }

        val intent = if (path == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { v.createOpenDocumentTreeIntent() }.getOrNull()
        } else {
            null
        }

        return Volume(
            title = title,
            path = path,
            removable = runCatching { v.isRemovable }.getOrDefault(true),
            isUsb = looksUsb,
            key = key,
            accessIntent = intent,
        )
    }

    /** En móviles antiguos la ruta solo está en métodos internos. */
    private fun reflectPath(v: StorageVolume): String? {
        for (name in listOf("getPath", "getPathFile")) {
            val value = runCatching {
                val m = StorageVolume::class.java.getDeclaredMethod(name)
                m.isAccessible = true
                when (val r = m.invoke(v)) {
                    is String -> r
                    is File -> r.absolutePath
                    else -> null
                }
            }.getOrNull()
            if (value != null) return value
        }
        return null
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

    /** Texto de ayuda para cuando un almacenamiento no se puede leer por ruta. */
    fun whyPermission(v: Volume): String = buildString {
        append("Este móvil no deja que BD Files entre a “${v.title}” por su carpeta.\n\n")
        append("Pasa en algunos Android recientes con las memorias USB. ")
        append("Dándole acceso una vez, queda guardado y luego entras directo.")
    }
}
