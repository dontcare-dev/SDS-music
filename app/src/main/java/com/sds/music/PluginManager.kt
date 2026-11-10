package com.sds.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MusicPlugin(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val type: String,
    val installedAt: Long = System.currentTimeMillis()
)

object PluginManager {
    private const val PREFS = "sds_music_plugins"
    private const val KEY = "installed"

    fun installed(context: Context): List<MusicPlugin> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                MusicPlugin(o.getString("id"), o.getString("name"), o.optString("version", "1.0.0"),
                    o.optString("description"), o.optString("author"), o.optString("type", "metadata"),
                    o.optLong("installedAt"))
            }
        }.getOrDefault(emptyList())
    }

    fun install(context: Context, json: String): MusicPlugin {
        val o = JSONObject(json)
        require(o.optInt("formatVersion", 1) == 1) { "Unsupported plugin package version." }
        val id = o.getString("id").trim()
        val name = o.getString("name").trim()
        require(id.matches(Regex("[a-zA-Z0-9._-]{2,64}"))) { "Invalid plugin ID." }
        require(name.isNotBlank() && name.length <= 80) { "Invalid plugin name." }
        val type = o.optString("type", "metadata")
        require(type in setOf("metadata", "lyrics", "catalog")) { "Unsupported plugin type." }
        val plugin = MusicPlugin(id, name, o.optString("version", "1.0.0"),
            o.optString("description").take(240), o.optString("author").take(80), type)
        val all = installed(context).filterNot { it.id == id } + plugin
        val array = JSONArray()
        all.forEach { p ->
            array.put(JSONObject().put("id", p.id).put("name", p.name).put("version", p.version)
                .put("description", p.description).put("author", p.author).put("type", p.type)
                .put("installedAt", p.installedAt))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
        return plugin
    }

    fun remove(context: Context, id: String) {
        val array = JSONArray()
        installed(context).filterNot { it.id == id }.forEach { p ->
            array.put(JSONObject().put("id", p.id).put("name", p.name).put("version", p.version)
                .put("description", p.description).put("author", p.author).put("type", p.type)
                .put("installedAt", p.installedAt))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun downloadPackage(address: String): String {
        require(address.startsWith("https://", true)) { "Use a secure HTTPS plugin URL." }
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        connection.instanceFollowRedirects = true
        return try {
            connection.connect()
            require(connection.responseCode in 200..299) { "Plugin download failed (HTTP ${connection.responseCode})." }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            require(text.length <= 256_000) { "Plugin package is too large." }
            text
        } finally { connection.disconnect() }
    }
}
