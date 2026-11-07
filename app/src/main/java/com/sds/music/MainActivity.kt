package com.sds.music

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import com.sds.music.online.AudiusApi
import com.sds.music.online.AudiusTrack
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch

private val Paper = Color(0xFF121212)
private val Ink = Color(0xFFF5F5F5)
private val Tile = Color(0xFF242424)
private val Accent = Color(0xFFB9B6AE)

data class Track(val uri: String, val title: String, val artist: String = "", val album: String = "", val year: String = "", val musicBrainzId: String = "")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SdsMusicApp(this) }
    }
}

private fun readTracks(context: Context): List<Track> {
    val raw = context.getSharedPreferences("sds_music", Context.MODE_PRIVATE).getString("tracks", "[]") ?: "[]"
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Track(item.getString("uri"), item.getString("title"), item.optString("artist"), item.optString("album"), item.optString("year"), item.optString("musicBrainzId"))
        }
    }.getOrDefault(emptyList())
}

private fun saveTracks(context: Context, tracks: List<Track>) {
    val array = JSONArray()
    tracks.forEach { array.put(JSONObject().put("uri", it.uri).put("title", it.title).put("artist", it.artist).put("album", it.album).put("year", it.year).put("musicBrainzId", it.musicBrainzId)) }
    context.getSharedPreferences("sds_music", Context.MODE_PRIVATE).edit().putString("tracks", array.toString()).apply()
}

private fun displayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.substringBeforeLast('.') ?: (uri.lastPathSegment ?: "Untitled audio")
}

private fun readLocalMetadata(context: Context, uri: Uri): Track {
    val fallback = displayName(context, uri)
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        Track(uri = uri.toString(),
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: fallback,
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR).orEmpty())
    } catch (_: Exception) { Track(uri.toString(), fallback) }
    finally { runCatching { retriever.release() } }
}

private fun readImportedPlaylists(context: Context): List<ImportedPlaylist> = runCatching {
    val raw = context.getSharedPreferences("sds_music", Context.MODE_PRIVATE).getString("imported_playlists", "[]") ?: "[]"
    val array = JSONArray(raw)
    (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)
        val songs = item.optJSONArray("songs") ?: JSONArray()
        ImportedPlaylist(item.optString("name"), item.optString("source"), (0 until songs.length()).mapNotNull { j ->
            songs.optJSONObject(j)?.let { ImportedSong(it.optString("title"), it.optString("artist"), it.optString("album")) }
        })
    }
}.getOrDefault(emptyList())

private fun saveImportedPlaylists(context: Context, playlists: List<ImportedPlaylist>) {
    val array = JSONArray()
    playlists.forEach { playlist ->
        val songs = JSONArray()
        playlist.songs.forEach { songs.put(JSONObject().put("title", it.title).put("artist", it.artist).put("album", it.album)) }
        array.put(JSONObject().put("name", playlist.name).put("source", playlist.source).put("songs", songs))
    }
    context.getSharedPreferences("sds_music", Context.MODE_PRIVATE).edit().putString("imported_playlists", array.toString()).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SdsMusicApp(context: Context) {
    val player = remember { ExoPlayer.Builder(context).build() }
    var tracks by remember { mutableStateOf(readTracks(context)) }
    var importedPlaylists by remember { mutableStateOf(readImportedPlaylists(context)) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("Home") }
    var currentUri by remember { mutableStateOf<String?>(null) }
    var currentTitle by remember { mutableStateOf("Nothing playing") }
    var playing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var loopA by remember { mutableLongStateOf(0L) }
    var loopB by remember { mutableLongStateOf(0L) }
    var loopEnabled by remember { mutableStateOf(false) }
    var showLoop by remember { mutableStateOf(false) }
    var audiusResults by remember { mutableStateOf<List<AudiusTrack>>(emptyList()) }
    var trending by remember { mutableStateOf<List<AudiusTrack>>(emptyList()) }
    var onlineLoading by remember { mutableStateOf(false) }
    var onlineError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        onlineLoading = true
        try { trending = AudiusApi.trending() }
        catch (error: Exception) { onlineError = error.message ?: "Couldn't load music." }
        finally { onlineLoading = false }
    }

    fun searchAudius() {
        scope.launch {
            onlineLoading = true
            onlineError = null
            try { audiusResults = AudiusApi.search(query) }
            catch (error: Exception) { onlineError = error.message ?: "Search failed." }
            finally { onlineLoading = false }
        }
    }

    fun playAudius(track: AudiusTrack) {
        player.setMediaItem(MediaItem.fromUri(AudiusApi.streamUrl(track.id)))
        player.prepare()
        player.play()
        currentUri = AudiusApi.streamUrl(track.id)
        currentTitle = track.title
        tab = "Player"
    }

    val playlistPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else "import.json"
                } ?: "import.json"
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Couldn't read that file.")
                val imported = LibraryImportParser.parse(content, filename)
                importedPlaylists = importedPlaylists + imported
                saveImportedPlaylists(context, importedPlaylists)
                importMessage = "Imported " + imported.songs.size + " songs from " + imported.source + "."
            }.onFailure { importMessage = it.message ?: "Couldn't import this file." }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val additions = uris.mapNotNull { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                readLocalMetadata(context, uri)
            }.getOrNull()
        }
        if (additions.isNotEmpty()) {
            tracks = (tracks + additions).distinctBy { it.uri }
            saveTracks(context, tracks)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentUri = mediaItem?.localConfiguration?.uri?.toString()
                currentTitle = tracks.firstOrNull { it.uri == currentUri }?.title ?: currentTitle
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            if (loopEnabled && loopB > loopA && player.currentPosition >= loopB) player.seekTo(loopA)
            delay(250)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Paper, background = Paper, surface = Tile, onSurface = Ink)) {
        Scaffold(
            containerColor = Paper,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF1D1D1D), contentColor = Ink) {
                    listOf("Home", "Search", "Library", "Player").forEach { label ->
                        val icon = when (label) {
                            "Home" -> Icons.Rounded.Home
                            "Search" -> Icons.Rounded.Search
                            "Library" -> Icons.Rounded.LibraryMusic
                            else -> Icons.Rounded.GraphicEq
                        }
                        NavigationBarItem(selected = tab == label, onClick = { tab = label }, icon = { Icon(icon, null) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Accent))
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("SDS-MUSIC", fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                        Text(when (tab) { "Home" -> "Discover"; "Search" -> "Search"; "Library" -> "Your library"; else -> "Now playing" }, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Icon(Icons.Rounded.AddCircle, "Import audio", modifier = Modifier.size(30.dp)) }
                }
                when (tab) {
                    "Home" -> {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Made for listening", color = Ink.copy(alpha = .65f), fontSize = 14.sp)
                            TextButton(onClick = { tab = "Library" }) { Text("Library", color = Accent) }
                        }
                        if (trending.isNotEmpty()) {
                            Text("Trending", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                items(trending, key = { it.id }) { track -> AudiusTrackRow(track, onClick = { playAudius(track) }) }
                            }
                        } else if (onlineLoading) {
                            Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
                        } else {
                            Text(onlineError ?: "No tracks available right now.", color = Ink.copy(alpha = .65f))
                        }
                    }
                    "Search" -> {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Songs, artists") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            shape = RoundedCornerShape(28.dp), singleLine = true
                        )
                        Button(onClick = { searchAudius() }, enabled = query.isNotBlank() && !onlineLoading, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            if (onlineLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Search")
                        }
                        onlineError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                            items(audiusResults, key = { it.id }) { track -> AudiusTrackRow(track, onClick = { playAudius(track) }) }
                        }
                    }
                    "Library" -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Your music", color = Ink.copy(alpha = .7f))
                            Row {
                                TextButton(onClick = { playlistPicker.launch(arrayOf("text/*", "application/json", "text/csv")) }) { Text("Import playlist", color = Accent) }
                                TextButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("＋ Audio", color = Ink) }
                            }
                        }
                        importMessage?.let { Text(it, color = Accent, fontSize = 12.sp) }
                        importedPlaylists.forEach { playlist ->
                            Row(Modifier.fillMaxWidth().background(Tile, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.QueueMusic, null, tint = Accent)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(playlist.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(playlist.songs.size.toString() + " songs · " + playlist.source, color = Ink.copy(alpha = .6f), fontSize = 11.sp)
                                }
                            }
                        }
                        Text(tracks.size.toString() + " files on this device", color = Ink.copy(alpha = .6f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                        if (tracks.isEmpty()) EmptyLibrary { picker.launch(arrayOf("audio/*")) }
                        else TrackList(tracks, currentUri, onPlay = { playTrack(it, tracks, player) }, onRemove = { track ->
                            tracks = tracks.filterNot { it.uri == track.uri }
                            saveTracks(context, tracks)
                            if (currentUri == track.uri) { player.stop(); currentUri = null; currentTitle = "Nothing playing" }
                        })
                    }
                    else -> {
                        Spacer(Modifier.height(34.dp))
                        Box(Modifier.fillMaxWidth().height(240.dp).background(Tile, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                            Text("♫", fontSize = 96.sp, color = Accent)
                        }
                        Text(currentTitle, Modifier.fillMaxWidth().padding(top = 24.dp), fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("SDS-MUSIC", color = Ink.copy(alpha = .55f), fontSize = 11.sp, letterSpacing = 2.sp)
                        Slider(value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f, onValueChange = { player.seekTo(it.toLong()) }, valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()), colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Ink))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(timeLabel(position), fontSize = 11.sp); Text(timeLabel(duration), fontSize = 11.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { player.seekToPreviousMediaItem() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", modifier = Modifier.size(34.dp)) }
                            FilledIconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.size(66.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Ink, contentColor = Paper)) {
                                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause", modifier = Modifier.size(36.dp))
                            }
                            IconButton(onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(34.dp)) }
                        }
                        OutlinedButton(onClick = { loopA = 0L; loopB = duration; showLoop = true }, modifier = Modifier.fillMaxWidth()) { Text("Loop Studio") }
                    }
                }
            }
        }
        if (showLoop) AlertDialog(
            onDismissRequest = { showLoop = false },
            title = { Text("Loop Studio") },
            text = {
                Column {
                    Text("Set a section of the current track to repeat.")
                    Text("A · ${timeLabel(loopA)}", Modifier.padding(top = 12.dp))
                    Slider(value = loopA.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()), onValueChange = { loopA = it.toLong().coerceAtMost((duration - 1000).coerceAtLeast(0)) }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat())
                    Text("B · ${timeLabel(loopB)}")
                    Slider(value = loopB.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()), onValueChange = { loopB = it.toLong().coerceAtLeast(loopA + 1000).coerceAtMost(duration) }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = loopEnabled, onCheckedChange = { loopEnabled = it && loopB > loopA })
                        Text("Repeat selected section")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { if (loopB > loopA) player.seekTo(loopA); showLoop = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { loopEnabled = false; showLoop = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AudiusTrackRow(track: AudiusTrack, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Tile, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (track.artwork.isNotBlank()) {
            AsyncImage(model = track.artwork, contentDescription = null, modifier = Modifier.size(54.dp).background(Color.DarkGray, RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(54.dp).background(Color(0xFF383838), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, tint = Accent)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(track.artist, color = Ink.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1)
        }
        Text("▶", color = Accent, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

private fun playTrack(track: Track, tracks: List<Track>, player: ExoPlayer) {
    val index = tracks.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
    player.setMediaItems(tracks.map { MediaItem.fromUri(it.uri) }, index, 0L)
    player.prepare()
    player.play()
}

@Composable
private fun TrackList(tracks: List<Track>, currentUri: String?, onPlay: (Track) -> Unit, onRemove: ((Track) -> Unit)? = null) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(tracks, key = { it.uri }) { track ->
            Row(Modifier.fillMaxWidth().background(Tile.copy(alpha = .7f), RoundedCornerShape(16.dp)).clickable { onPlay(track) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(Color(0xFF77736B), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null, tint = Accent) }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(if (track.uri == currentUri) "NOW PLAYING" else listOf(track.artist.ifBlank { "LOCAL AUDIO" }, track.album).filter { it.isNotBlank() }.joinToString(" · "), color = Color.White.copy(alpha = .7f), fontSize = 10.sp, letterSpacing = 1.sp, maxLines = 1)
                }
                if (onRemove != null) IconButton(onClick = { onRemove(track) }) { Icon(Icons.Rounded.Close, "Remove", tint = Color.White) }
                else Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White)
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.LibraryMusic, null, modifier = Modifier.size(52.dp), tint = Ink.copy(alpha = .5f))
        Text("Your library is waiting", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text("Import audio files stored on your device.", color = Ink.copy(alpha = .65f), modifier = Modifier.padding(top = 4.dp))
        Button(onClick = onImport, modifier = Modifier.padding(top = 16.dp)) { Text("Choose audio files") }
    }
}

private fun timeLabel(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
