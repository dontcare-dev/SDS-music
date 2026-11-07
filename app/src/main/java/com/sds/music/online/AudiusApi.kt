package com.sds.music.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class AudiusTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artwork: String,
    val durationSeconds: Int,
    val genre: String,
    val permalink: String
)

object AudiusApi {
    private const val BASE = "https://api.audius.co/v1"

    private suspend fun request(path: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 18000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SDS-Music/1.0")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Audius returned HTTP ${connection.responseCode}")
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTracks(json: JSONObject): List<AudiusTrack> {
        val data = json.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val art = item.optJSONObject("artwork")
            val image = art?.optString("480x480")?.takeIf { it.startsWith("http") }
                ?: art?.optString("150x150")?.takeIf { it.startsWith("http") }
                ?: item.optString("cover_art").takeIf { it.startsWith("http") }
                ?: ""
            val user = item.optJSONObject("user")
            val artistArray = item.optJSONArray("artists")
            val creditedArtist = artistArray?.optJSONObject(0)?.let { credited ->
                firstMeaningful(credited.optString("name"), credited.optString("artist_name"))
            }
            val artist = firstMeaningful(
                item.optString("artist_name"),
                creditedArtist,
                user?.optString("name"),
                user?.optString("handle"),
                item.optString("artist")
            ) ?: "Artist profile unavailable"
            AudiusTrack(
                id = id,
                title = firstMeaningful(item.optString("title")) ?: "Untitled",
                artist = artist,
                artwork = image,
                durationSeconds = item.optInt("duration"),
                genre = item.optString("genre"),
                permalink = item.optString("permalink")
            )
        }
    }

    private fun firstMeaningful(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() && it != "null" && it != "Unknown artist" }?.trim()

    suspend fun trending(): List<AudiusTrack> =
        parseTracks(request("/tracks/trending?time=week&limit=30")).distinctBy { it.id }

    suspend fun search(query: String): List<AudiusTrack> {
        require(query.isNotBlank()) { "Type a song or artist to search." }
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return parseTracks(request("/tracks/search?query=$encoded&limit=20&sort_method=popular")).distinctBy { it.id }
    }

    fun streamUrl(trackId: String): String = "$BASE/tracks/$trackId/stream"
}
