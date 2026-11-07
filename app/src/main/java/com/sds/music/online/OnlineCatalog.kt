package com.sds.music.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class RecordingMatch(
    val id: String,
    val title: String,
    val artist: String,
    val release: String,
    val year: String
)

data class RadioStation(
    val id: String,
    val name: String,
    val country: String,
    val codec: String,
    val bitrate: Int,
    val streamUrl: String,
    val homepage: String
)

private suspend fun getJson(url: String): String = withContext(Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = 15_000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "SDS-Music/1.0 (https://github.com/dontcare-dev/SDS-music)")
    }
    try {
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Online service returned HTTP ${connection.responseCode}")
        }
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

object MusicBrainzApi {
    suspend fun searchRecordings(query: String): List<RecordingMatch> {
        val clean = query.trim()
        require(clean.isNotEmpty()) { "Enter a track title or artist first." }
        val encoded = URLEncoder.encode("recording:\"$clean\"", "UTF-8")
        val json = getJson("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=15")
        val recordings = org.json.JSONObject(json).optJSONArray("recordings") ?: JSONArray()
        return (0 until recordings.length()).mapNotNull { index ->
            val item = recordings.optJSONObject(index) ?: return@mapNotNull null
            val credits = item.optJSONArray("artist-credit") ?: JSONArray()
            val artist = (0 until credits.length()).mapNotNull { i ->
                val credit = credits.optJSONObject(i)
                credit?.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: credit?.optString("name")?.takeIf { it.isNotBlank() }
            }.joinToString("")
            val releases = item.optJSONArray("releases") ?: JSONArray()
            val release = releases.optJSONObject(0)
            RecordingMatch(
                id = item.optString("id"),
                title = item.optString("title", "Unknown recording"),
                artist = artist.ifBlank { "Unknown artist" },
                release = release?.optString("title").orEmpty(),
                year = release?.optString("date").orEmpty().take(4)
            )
        }
    }
}

object RadioBrowserApi {
    suspend fun searchStations(query: String): List<RadioStation> {
        val clean = query.trim()
        require(clean.isNotEmpty()) { "Enter a station name, genre, or country." }
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val json = getJson("https://de1.api.radio-browser.info/json/stations/search?name=$encoded&limit=30&hidebroken=true&order=clickcount&reverse=true")
        val stations = JSONArray(json)
        return (0 until stations.length()).mapNotNull { index ->
            val item = stations.optJSONObject(index) ?: return@mapNotNull null
            val stream = item.optString("url_resolved").ifBlank { item.optString("url") }
            if (stream.isBlank() || !stream.startsWith("http")) return@mapNotNull null
            RadioStation(
                id = item.optString("stationuuid"),
                name = item.optString("name", "Unknown station"),
                country = item.optString("country"),
                codec = item.optString("codec"),
                bitrate = item.optInt("bitrate"),
                streamUrl = stream,
                homepage = item.optString("homepage")
            )
        }
    }
}
