package com.sds.music

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class ImportedSong(
    val title: String,
    val artist: String = "",
    val album: String = ""
)

data class ImportedPlaylist(
    val name: String,
    val source: String,
    val songs: List<ImportedSong>
)

object LibraryImportParser {
    fun parse(content: String, filename: String): ImportedPlaylist {
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val songs = when {
            ext == "csv" || content.trimStart().startsWith("\uFEFF") ||
                content.lineSequence().firstOrNull().orEmpty().contains(',') -> parseCsv(content)
            else -> parseJson(content)
        }.distinctBy { listOf(it.title.lowercase(), it.artist.lowercase(), it.album.lowercase()) }
        require(songs.isNotEmpty()) {
            "No songs were found. Choose a Spotify CSV export or a YouTube Music Google Takeout JSON/CSV file."
        }
        val source = when {
            filename.contains("spotify", true) -> "Spotify"
            filename.contains("youtube", true) || filename.contains("music", true) -> "YouTube Music"
            else -> "Imported"
        }
        val name = filename.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').ifBlank { "Imported playlist" }
        return ImportedPlaylist(name, source, songs)
    }

    private fun parseCsv(raw: String): List<ImportedSong> {
        val rows = csvRows(raw)
        if (rows.size < 2) return emptyList()
        val headers = rows.first().map { it.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "") }
        fun index(vararg names: String) = headers.indexOfFirst { it in names }
        val titleIndex = index("trackname", "title", "song", "songtitle", "videotitle", "name")
        val artistIndex = index("artistname", "artistnames", "artist", "artists", "creator")
        val albumIndex = index("albumname", "albumtitle", "album", "release")
        if (titleIndex < 0) return emptyList()
        return rows.drop(1).mapNotNull { row ->
            val title = row.getOrNull(titleIndex)?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            ImportedSong(title, row.getOrNull(artistIndex)?.trim().orEmpty(), row.getOrNull(albumIndex)?.trim().orEmpty())
        }
    }

    private fun csvRows(raw: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        val text = raw.removePrefix("\uFEFF")
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { row.add(cell.toString()); cell.setLength(0) }
                (c == '\n' || c == '\r') && !quoted -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row.add(cell.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }

    private fun parseJson(raw: String): List<ImportedSong> {
        val root = runCatching { org.json.JSONTokener(raw).nextValue() }.getOrNull() ?: return emptyList()
        val found = mutableListOf<ImportedSong>()
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
                is JSONObject -> {
                    val title = value.optString("title").takeIf { it.isNotBlank() }
                        ?: value.optString("trackName").takeIf { it.isNotBlank() }
                        ?: value.optString("videoTitle").takeIf { it.isNotBlank() }
                    val artist = value.optString("artist").ifBlank {
                        value.optString("artistName").ifBlank {
                            value.optJSONArray("artists")?.let { arr ->
                                (0 until arr.length()).mapNotNull { idx ->
                                    val item = arr.opt(idx)
                                    if (item is JSONObject) item.optString("name").takeIf(String::isNotBlank)
                                    else item?.toString()?.takeIf(String::isNotBlank)
                                }.joinToString(", ")
                            }.orEmpty()
                        }
                    }.ifBlank {
                        value.optJSONArray("subtitles")?.let { arr ->
                            (0 until arr.length()).mapNotNull { idx -> arr.optJSONObject(idx)?.optString("name")?.takeIf(String::isNotBlank) }.joinToString(", ")
                        }.orEmpty()
                    }
                    val album = value.optString("album").ifBlank { value.optString("albumName") }
                    if (!title.isNullOrBlank() && (artist.isNotBlank() || value.has("trackName") || value.has("videoTitle"))) {
                        found.add(ImportedSong(title, artist, album))
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) visit(value.opt(keys.next()))
                }
            }
        }
        visit(root)
        return found
    }
}
