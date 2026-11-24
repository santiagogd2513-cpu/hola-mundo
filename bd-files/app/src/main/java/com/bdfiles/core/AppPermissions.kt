package com.bdfiles.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Permisos de almacenamiento de las demás apps (emuladores, editores...).
 *
 * Importante: Android NO deja que una app le dé permisos a otra por las buenas.
 * Con Shizuku sí se puede, porque las órdenes se ejecutan con permisos del sistema
 * (lo mismo que haría `adb shell pm grant`). Sin Shizuku solo se puede mirar el
 * estado y abrir los ajustes del sistema para que lo hagas tú a mano.
 */
object AppPermissions {

    const val READ = "android.permission.READ_EXTERNAL_STORAGE"
    const val WRITE = "android.permission.WRITE_EXTERNAL_STORAGE"
    const val MANAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"
    const val MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"

    class AppInfo(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        /** Permisos de almacenamiento que la app declara en su manifiesto. */
        val declared: List<String>,
        /** Los que tiene concedidos ahora mismo. */
        val granted: Set<String>,
        /** true si tiene "Acceso a todos los archivos". */
        val allFiles: Boolean,
    ) {
        val hasAnyStorage: Boolean get() = allFiles || granted.isNotEmpty()
        val wantsStorage: Boolean get() = declared.isNotEmpty()
    }

    private val ALL = listOf(READ, WRITE, MANAGE, MEDIA_IMAGES, MEDIA_VIDEO, MEDIA_AUDIO)

    fun shortName(permission: String): String = when (permission) {
        READ -> "Leer almacenamiento"
        WRITE -> "Escribir almacenamiento"
        MANAGE -> "Acceso a todos los archivos"
        MEDIA_IMAGES -> "Imágenes"
        MEDIA_VIDEO -> "Vídeos"
        MEDIA_AUDIO -> "Audio"
        else -> permission.substringAfterLast('.')
    }

    /** Lista las apps instaladas que piden algún permiso de almacenamiento. */
    fun listApps(context: Context, includeSystem: Boolean): List<AppInfo> {
        val pm = context.packageManager
        val packages = runCatching {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }.getOrElse { return emptyList() }

        val out = ArrayList<AppInfo>()
        for (p in packages) {
            val app = p.applicationInfo ?: continue
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystem) continue
            if (p.packageName == context.packageName) continue

            val requested = p.requestedPermissions?.toList().orEmpty()
            val declared = ALL.filter { it in requested }
            if (declared.isEmpty()) continue

            val granted = HashSet<String>()
            val flags = p.requestedPermissionsFlags
            if (flags != null) {
                requested.forEachIndexed { i, perm ->
                    if (perm in ALL && i < flags.size &&
                        (flags[i] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    ) {
                        granted.add(perm)
                    }
                }
            }
            val allFiles = MANAGE in declared && hasAllFilesAccess(context, p.packageName)
            out.add(
                AppInfo(
                    packageName = p.packageName,
                    label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(p.packageName),
                    isSystem = isSystem,
                    declared = declared,
                    granted = granted,
                    allFiles = allFiles,
                ),
            )
        }
        return out.sortedWith(compareBy({ !it.hasAnyStorage }, { it.label.lowercase() }))
    }

    private fun hasAllFilesAccess(context: Context, pkg: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!ShizukuBridge.isReady) return false
        val r = runCatching {
            ShizukuBridge.run("appops get $pkg MANAGE_EXTERNAL_STORAGE 2>/dev/null", 8000)
        }.getOrNull() ?: return false
        return r.out.contains("allow", ignoreCase = true)
    }

    fun icon(context: Context, pkg: String): Drawable? =
        runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()

    /** Concede un permiso a otra app. Necesita Shizuku. */
    fun grant(pkg: String, permission: String): String {
        if (!ShizukuBridge.isReady) {
            throw UserError(
                "Para dar permisos a otras apps hace falta Shizuku.\n\n" +
                    "Actívalo en Ajustes → Shizuku, o dale el permiso a mano desde los " +
                    "ajustes del sistema con el botón «Abrir ajustes de la app».",
            )
        }
        val r = if (permission == MANAGE) {
            ShizukuBridge.run("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        } else {
            ShizukuBridge.run("pm grant $pkg $permission")
        }
        if (!r.ok) throw UserError("No se pudo dar el permiso:\n${r.text.ifBlank { "error desconocido" }}")
        return "Permiso concedido a $pkg"
    }

    /** Quita un permiso a otra app. Necesita Shizuku. */
    fun revoke(pkg: String, permission: String): String {
        if (!ShizukuBridge.isReady) throw UserError("Para quitar permisos hace falta Shizuku.")
        val r = if (permission == MANAGE) {
            ShizukuBridge.run("appops set $pkg MANAGE_EXTERNAL_STORAGE deny")
        } else {
            ShizukuBridge.run("pm revoke $pkg $permission")
        }
        if (!r.ok) throw UserError("No se pudo quitar el permiso:\n${r.text.ifBlank { "error desconocido" }}")
        return "Permiso quitado a $pkg"
    }

    /** Da de golpe todos los permisos de almacenamiento que la app pida. */
    fun grantAll(app: AppInfo): String {
        val errors = ArrayList<String>()
        var done = 0
        for (p in app.declared) {
            runCatching { grant(app.packageName, p) }
                .onSuccess { done++ }
                .onFailure { errors.add("• ${shortName(p)}: ${it.message}") }
        }
        if (done == 0 && errors.isNotEmpty()) throw UserError(errors.joinToString("\n"))
        return if (errors.isEmpty()) {
            "“${app.label}” ya tiene acceso al almacenamiento."
        } else {
            "Concedidos $done permisos. No se pudo con:\n${errors.joinToString("\n")}"
        }
    }

    fun openAppSettings(context: Context, pkg: String) {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    fun openAllFilesSettings(context: Context, pkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return openAppSettings(context, pkg)
        val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg"))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure { openAppSettings(context, pkg) }
    }
}
