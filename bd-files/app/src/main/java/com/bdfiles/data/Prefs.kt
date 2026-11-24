package com.bdfiles.data

import android.content.Context
import android.content.SharedPreferences
import com.bdfiles.core.SortBy
import com.bdfiles.net.FtpDeviceType
import com.bdfiles.net.FtpServerConfig
import org.json.JSONArray
import org.json.JSONObject

/** Un atajo: una carpeta guardada para llegar rápido. */
data class Shortcut(
    val name: String,
    val path: String,
    val sourceId: String = "local",
    /** Emoji que se enseña al lado. */
    val icon: String = "📁",
)

/** Ajustes, favoritos, anclados, atajos y servidores. Todo guardado en el móvil. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("bdfiles", Context.MODE_PRIVATE)

    // ---------------------------------------------------------- interfaz y estilo

    /** null = seguir al sistema. */
    var darkMode: Boolean?
        get() = if (!sp.contains("darkMode")) null else sp.getBoolean("darkMode", true)
        set(v) = sp.edit().apply { if (v == null) remove("darkMode") else putBoolean("darkMode", v) }.apply()

    var tabsEnabled: Boolean
        get() = sp.getBoolean("tabsEnabled", true)
        set(v) = sp.edit().putBoolean("tabsEnabled", v).apply()

    var maxTabs: Int
        get() = sp.getInt("maxTabs", MAX_TABS_LIMIT).coerceIn(2, MAX_TABS_LIMIT)
        set(v) = sp.edit().putInt("maxTabs", v.coerceIn(2, MAX_TABS_LIMIT)).apply()

    var showThumbnails: Boolean
        get() = sp.getBoolean("showThumbnails", true)
        set(v) = sp.edit().putBoolean("showThumbnails", v).apply()

    var compactRows: Boolean
        get() = sp.getBoolean("compactRows", false)
        set(v) = sp.edit().putBoolean("compactRows", v).apply()

    var showPathBar: Boolean
        get() = sp.getBoolean("showPathBar", true)
        set(v) = sp.edit().putBoolean("showPathBar", v).apply()

    /**
     * Escala del texto dentro de la app, sobre la del sistema.
     * 1.0 = igual que el sistema. Sirve para quien tiene la letra del móvil muy
     * grande y quiere que en BD Files sea algo más pequeña.
     */
    var textScale: Float
        get() = sp.getFloat("textScale", 1f).coerceIn(0.75f, 1.3f)
        set(v) = sp.edit().putFloat("textScale", v.coerceIn(0.75f, 1.3f)).apply()

    var colorfulIcons: Boolean
        get() = sp.getBoolean("colorfulIcons", true)
        set(v) = sp.edit().putBoolean("colorfulIcons", v).apply()

    // ---------------------------------------------------------- archivos y listado

    var showHidden: Boolean
        get() = sp.getBoolean("showHidden", false)
        set(v) = sp.edit().putBoolean("showHidden", v).apply()

    var foldersFirst: Boolean
        get() = sp.getBoolean("foldersFirst", true)
        set(v) = sp.edit().putBoolean("foldersFirst", v).apply()

    var sortBy: SortBy
        get() = runCatching { SortBy.valueOf(sp.getString("sortBy", "") ?: "") }.getOrDefault(SortBy.NAME)
        set(v) = sp.edit().putString("sortBy", v.name).apply()

    var sortDescending: Boolean
        get() = sp.getBoolean("sortDescending", false)
        set(v) = sp.edit().putBoolean("sortDescending", v).apply()

    var showFolderSizes: Boolean
        get() = sp.getBoolean("showFolderSizes", false)
        set(v) = sp.edit().putBoolean("showFolderSizes", v).apply()

    // ---------------------------------------------------------- verificaciones

    var verifyCopies: Boolean
        get() = sp.getBoolean("verifyCopies", true)
        set(v) = sp.edit().putBoolean("verifyCopies", v).apply()

    var useTrash: Boolean
        get() = sp.getBoolean("useTrash", true)
        set(v) = sp.edit().putBoolean("useTrash", v).apply()

    var confirmDelete: Boolean
        get() = sp.getBoolean("confirmDelete", true)
        set(v) = sp.edit().putBoolean("confirmDelete", v).apply()

    // ---------------------------------------------------------- favoritos y anclados

    var favorites: List<Shortcut>
        get() = readShortcuts("favorites")
        set(v) = writeShortcuts("favorites", v)

    fun toggleFavorite(s: Shortcut): Boolean {
        val list = favorites.toMutableList()
        val existing = list.indexOfFirst { it.path == s.path && it.sourceId == s.sourceId }
        val added: Boolean
        if (existing >= 0) {
            list.removeAt(existing)
            added = false
        } else {
            list.add(s)
            added = true
        }
        favorites = list
        return added
    }

    fun isFavorite(path: String, sourceId: String): Boolean =
        favorites.any { it.path == path && it.sourceId == sourceId }

    /** Claves "origen|ruta" de las carpetas que van siempre arriba del todo. */
    var pinned: Set<String>
        get() = sp.getStringSet("pinned", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("pinned", v).apply()

    fun togglePinned(key: String): Boolean {
        val set = pinned.toMutableSet()
        val added = set.add(key)
        if (!added) set.remove(key)
        pinned = set
        return added
    }

    // ---------------------------------------------------------- atajos

    var shortcuts: List<Shortcut>
        get() = readShortcuts("shortcuts").ifEmpty { defaultShortcuts() }
        set(v) = writeShortcuts("shortcuts", v)

    fun addShortcut(s: Shortcut) {
        val list = shortcuts.toMutableList()
        if (list.none { it.path == s.path && it.sourceId == s.sourceId }) list.add(s)
        shortcuts = list
    }

    fun removeShortcut(s: Shortcut) {
        shortcuts = shortcuts.filterNot { it.path == s.path && it.sourceId == s.sourceId }
    }

    private fun defaultShortcuts(): List<Shortcut> {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        return listOf(
            Shortcut("Descargas", "$root/Download", icon = "⬇️"),
            Shortcut("Imágenes", "$root/Pictures", icon = "🖼️"),
            Shortcut("Cámara", "$root/DCIM", icon = "📷"),
            Shortcut("Música", "$root/Music", icon = "🎵"),
            Shortcut("Vídeos", "$root/Movies", icon = "🎬"),
            Shortcut("Documentos", "$root/Documents", icon = "📄"),
        )
    }

    private fun readShortcuts(key: String): List<Shortcut> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Shortcut(
                    name = o.getString("name"),
                    path = o.getString("path"),
                    sourceId = o.optString("sourceId", "local"),
                    icon = o.optString("icon", "📁"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeShortcuts(key: String, list: List<Shortcut>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("path", it.path)
                    .put("sourceId", it.sourceId)
                    .put("icon", it.icon),
            )
        }
        sp.edit().putString(key, arr.toString()).apply()
    }

    // ---------------------------------------------------------- servidores FTP

    var servers: List<FtpServerConfig>
        get() {
            val raw = sp.getString("servers", null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val type = runCatching { FtpDeviceType.valueOf(o.optString("type")) }
                        .getOrDefault(FtpDeviceType.PC_OTHER)
                    FtpServerConfig(
                        name = o.getString("name"),
                        host = o.getString("host"),
                        port = o.optInt("port", 21),
                        user = o.optString("user", type.defaultUser),
                        password = o.optString("password", type.defaultPass),
                        deviceType = type,
                        passive = o.optBoolean("passive", type.defaultPassive),
                        startPath = o.optString("startPath", type.startPath),
                        connections = o.optInt("connections", type.defaultConnections),
                    )
                }
            }.getOrDefault(emptyList())
        }
        set(list) {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject()
                        .put("name", it.name).put("host", it.host).put("port", it.port)
                        .put("user", it.user).put("password", it.password)
                        .put("type", it.deviceType.name).put("passive", it.passive)
                        .put("startPath", it.startPath).put("connections", it.connections),
                )
            }
            sp.edit().putString("servers", arr.toString()).apply()
        }

    fun saveServer(config: FtpServerConfig) {
        val list = servers.toMutableList()
        val i = list.indexOfFirst { it.name == config.name }
        if (i >= 0) list[i] = config else list.add(config)
        servers = list
    }

    fun removeServer(name: String) {
        servers = servers.filterNot { it.name == name }
    }

    // ---------------------------------------------------------- pestañas abiertas

    /** Rutas de las pestañas abiertas, para recuperarlas al volver a entrar. */
    var openTabs: List<Pair<String, String>>
        get() {
            val raw = sp.getString("openTabs", null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    o.getString("sourceId") to o.getString("path")
                }
            }.getOrDefault(emptyList())
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("sourceId", it.first).put("path", it.second)) }
            sp.edit().putString("openTabs", arr.toString()).apply()
        }

    companion object {
        const val MAX_TABS_LIMIT = 14
    }
}
