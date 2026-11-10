package com.sds.music

import android.content.Context
import android.content.ComponentName
import android.content.Intent
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.Executor
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Paper = Color(0xFF0B0B0C)
private val Ink = Color(0xFFF7F4EF)
private val Tile = Color(0xFF191817)
private val Accent = Color(0xFFD4C1A3)
private val Muted = Color(0xFF9C9489)

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
    val localFallbackPlayer = remember { ExoPlayer.Builder(context).build() }
    val controllerFuture = remember {
        MediaController.Builder(context, SessionToken(context, ComponentName(context, com.sds.music.player.MusicService::class.java))).buildAsync()
    }
    var servicePlayer by remember { mutableStateOf<MediaController?>(null) }
    val player: Player = servicePlayer ?: localFallbackPlayer

    LaunchedEffect(controllerFuture) {
        runCatching { context.startService(Intent(context, com.sds.music.player.MusicService::class.java)) }
        controllerFuture.addListener({
            servicePlayer = runCatching { controllerFuture.get() }.getOrNull()
        }, Executor { command -> command.run() })
    }
    DisposableEffect(controllerFuture) {
        onDispose {
            localFallbackPlayer.release()
            MediaController.releaseFuture(controllerFuture)
        }
    }
    var tracks by remember { mutableStateOf(readTracks(context)) }
    var importedPlaylists by remember { mutableStateOf(readImportedPlaylists(context)) }
    var selectedImportedPlaylist by remember { mutableStateOf<ImportedPlaylist?>(null) }
    var editingTrack by remember { mutableStateOf<Track?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editArtist by remember { mutableStateOf("") }
    var editAlbum by remember { mutableStateOf("") }
    var editYear by remember { mutableStateOf("") }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("Home") }
    var plugins by remember { mutableStateOf(PluginManager.installed(context)) }
    var pluginUrl by remember { mutableStateOf("") }
    var pluginMessage by remember { mutableStateOf<String?>(null) }
    val pluginScope = rememberCoroutineScope()
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
        val pluginPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Could not read plugin package.")
                val installed = PluginManager.install(context, raw)
                plugins = PluginManager.installed(context)
                pluginMessage = "Installed " + installed.name
            }.onFailure { pluginMessage = it.message ?: "Install failed." }
        }
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
        onDispose { player.removeListener(listener) }
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
                NavigationBar(containerColor = Color(0xFF121212), contentColor = Ink) {
                    listOf("Home", "Search", "Plugins", "Library", "Player").forEach { label ->
                        val icon = when (label) {
                            "Home" -> Icons.Rounded.Home
                            "Search" -> Icons.Rounded.Search
                            "Plugins" -> Icons.Rounded.Extension
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Box(Modifier.size(38.dp).background(Accent, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.GraphicEq, null, tint = Paper, modifier = Modifier.size(25.dp))
                        }
                        Column {
                            Text("SILVER DEV STUDIOS", fontSize = 9.sp, letterSpacing = 2.2.sp, fontWeight = FontWeight.Bold, color = Muted)
                            Text(when (tab) { "Home" -> "Discover"; "Search" -> "Search"; "Plugins" -> "Plugins"; "Library" -> "Your library"; else -> "Now playing" }, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Icon(Icons.Rounded.AddCircle, "Import audio", modifier = Modifier.size(30.dp)) }
                }
                when (tab) {
                    "Home" -> {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 22.dp)) {
                            Text("YOUR SOUND. YOUR SPACE.", color = Muted, fontSize = 10.sp, letterSpacing = 2.2.sp, fontWeight = FontWeight.Bold)
                            Text("Your music,\nyour way.", color = Ink, fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                            Text(tracks.size.toString() + " tracks in your local library", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                AssistChip(onClick = { tab = "Search" }, label = { Text("Search library") }, leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(16.dp)) })
                                AssistChip(onClick = { tab = "YouTube Music" }, label = { Text("YouTube Music") }, leadingIcon = { Icon(Icons.Rounded.PlayCircle, null, modifier = Modifier.size(16.dp)) })
                            }
                        }
                        Text("Recently added", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                        if (tracks.isEmpty()) EmptyLibrary { picker.launch(arrayOf("audio/*")) }
                        else TrackList(tracks.takeLast(8).reversed(), currentUri, onPlay = { playTrack(it, tracks, player) })
                    }
                    "Search" -> {
                        Text("Search tracks saved on this device.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Track, artist or album") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            shape = RoundedCornerShape(18.dp), singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent)
                        )
                        val matches = tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
                        if (query.isNotBlank()) Text(matches.size.toString() + " matches", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                        TrackList(matches, currentUri, onPlay = { playTrack(it, tracks, player) })
                        OutlinedButton(onClick = { openYouTubeMusic(context, query) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.PlayCircle, null, modifier = Modifier.padding(end = 8.dp))
                            Text(if (query.isBlank()) "Open YouTube Music" else "Search YouTube Music")
                        }
                    }
                    "Plugins" -> {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Provider plugins", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Text("Install providers from a package URL or local file.", color = Muted, fontSize = 13.sp)
                            OutlinedTextField(value = pluginUrl, onValueChange = { pluginUrl = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("HTTPS plugin URL") })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    pluginScope.launch {
                                        pluginMessage = "Installing…"
                                        runCatching {
                                            val raw = withContext(Dispatchers.IO) { PluginManager.downloadPackage(pluginUrl.trim()) }
                                            val item = PluginManager.install(context, raw)
                                            plugins = PluginManager.installed(context)
                                            pluginMessage = "Installed " + item.name
                                            pluginUrl = ""
                                        }.onFailure { pluginMessage = it.message ?: "Install failed." }
                                    }
                                }, enabled = pluginUrl.startsWith("https://")) { Text("Install URL") }
                                OutlinedButton(onClick = { pluginPicker.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) { Text("Upload file") }
                            }
                            pluginMessage?.let { Text(it, color = Accent, fontSize = 12.sp) }
                            Text("INSTALLED PROVIDERS", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                            if (plugins.isEmpty()) Text("No provider packages installed.", color = Muted, fontSize = 13.sp)
                            plugins.forEach { item ->
                                Row(Modifier.fillMaxWidth().background(Tile, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, color = Ink, fontWeight = FontWeight.SemiBold)
                                        Text(item.type.uppercase() + " · v" + item.version, color = Muted, fontSize = 11.sp)
                                        Text(item.description, color = Muted, fontSize = 11.sp)
                                    }
                                    IconButton(onClick = { PluginManager.remove(context, item.id); plugins = PluginManager.installed(context) }) {
                                        Icon(Icons.Rounded.DeleteOutline, "Remove", tint = Muted)
                                    }
                                }
                            }
                            Text("Only install packages from sources you trust. Spotube .smplug files are not directly compatible.", color = Muted, fontSize = 11.sp)
                        }
                    }
                    "Library" -> {
                        if (selectedImportedPlaylist != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { selectedImportedPlaylist = null }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                                Column {
                                    Text(selectedImportedPlaylist!!.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text(selectedImportedPlaylist!!.songs.size.toString() + " songs · " + selectedImportedPlaylist!!.source, color = Ink.copy(alpha = .6f), fontSize = 12.sp)
                                }
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(selectedImportedPlaylist!!.songs) { song ->
                                    Row(Modifier.fillMaxWidth().background(Tile, RoundedCornerShape(14.dp)).clickable {
                                        openYouTubeMusic(context, listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" "))
                                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "), color = Ink.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1)
                                        }
                                        Icon(Icons.Rounded.PlayCircle, "Find on YouTube Music", tint = Accent)
                                    }
                                }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Your music", color = Ink.copy(alpha = .7f))
                                Row {
                                    TextButton(onClick = { playlistPicker.launch(arrayOf("text/*", "application/json", "text/csv")) }) { Text("Import playlist", color = Accent) }
                                    TextButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("＋ Audio", color = Ink) }
                                }
                            }
                            importMessage?.let { Text(it, color = Accent, fontSize = 12.sp) }
                            importedPlaylists.forEach { playlist ->
                                Row(Modifier.fillMaxWidth().background(Tile, RoundedCornerShape(12.dp)).clickable { selectedImportedPlaylist = playlist }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.QueueMusic, null, tint = Accent)
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(playlist.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(playlist.songs.size.toString() + " songs · " + playlist.source, color = Ink.copy(alpha = .6f), fontSize = 11.sp)
                                    }
                                    Icon(Icons.Rounded.ChevronRight, null, tint = Ink.copy(alpha = .6f))
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                            Text(tracks.size.toString() + " files on this device", color = Ink.copy(alpha = .6f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                            if (tracks.isEmpty()) EmptyLibrary { picker.launch(arrayOf("audio/*")) }
                            else TrackList(tracks, currentUri, onPlay = { playTrack(it, tracks, player) }, onEdit = { track ->
                                editingTrack = track
                                editTitle = track.title
                                editArtist = track.artist
                                editAlbum = track.album
                                editYear = track.year
                            }, onRemove = { track ->
                                tracks = tracks.filterNot { it.uri == track.uri }
                                saveTracks(context, tracks)
                                if (currentUri == track.uri) { player.stop(); currentUri = null; currentTitle = "Nothing playing" }
                            })
                        }
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
        if (editingTrack != null) AlertDialog(
            onDismissRequest = { editingTrack = null },
            title = { Text("Edit track details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(editTitle, { editTitle = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(editArtist, { editArtist = it }, label = { Text("Artist") }, singleLine = true)
                    OutlinedTextField(editAlbum, { editAlbum = it }, label = { Text("Album") }, singleLine = true)
                    OutlinedTextField(editYear, { editYear = it }, label = { Text("Year") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val old = editingTrack!!
                    tracks = tracks.map { if (it.uri == old.uri) old.copy(title = editTitle.ifBlank { old.title }, artist = editArtist, album = editAlbum, year = editYear) else it }
                    saveTracks(context, tracks)
                    if (currentUri == old.uri) currentTitle = editTitle.ifBlank { old.title }
                    editingTrack = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingTrack = null }) { Text("Cancel") } }
        )
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

private fun playTrack(track: Track, tracks: List<Track>, player: Player) {
    val index = tracks.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
    player.setMediaItems(tracks.map { MediaItem.fromUri(it.uri) }, index, 0L)
    player.prepare()
    player.play()
}

@Composable
private fun TrackList(tracks: List<Track>, currentUri: String?, onPlay: (Track) -> Unit, onEdit: ((Track) -> Unit)? = null, onRemove: ((Track) -> Unit)? = null) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(tracks, key = { it.uri }) { track ->
            Row(Modifier.fillMaxWidth().background(Tile.copy(alpha = .7f), RoundedCornerShape(16.dp)).clickable { onPlay(track) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(Color(0xFF77736B), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null, tint = Accent) }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(if (track.uri == currentUri) "NOW PLAYING" else listOf(track.artist.ifBlank { "LOCAL AUDIO" }, track.album).filter { it.isNotBlank() }.joinToString(" · "), color = Color.White.copy(alpha = .7f), fontSize = 10.sp, letterSpacing = 1.sp, maxLines = 1)
                }
                if (onEdit != null) IconButton(onClick = { onEdit(track) }) { Icon(Icons.Rounded.Edit, "Edit details", tint = Accent) }
                if (onRemove != null) IconButton(onClick = { onRemove(track) }) { Icon(Icons.Rounded.Close, "Remove", tint = Color.White) }
                else if (onEdit == null) Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White)
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
 
private fun openYouTubeMusic(context: Context, search: String = "") {
    val encoded = Uri.encode(search.trim())
    val url = if (encoded.isBlank()) "https://music.youtube.com/" else "https://music.youtube.com/search?q=$encoded"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.youtube.music")))
    }
}

@Composable
private fun YouTubeMusicScreen(context: Context, search: String) {
    val packageName = "com.google.android.apps.youtube.music"
    val installed = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("YouTube Music", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(
            if (installed) "YouTube Music is installed. Open it to browse your library, playlists and downloads."
            else "Install YouTube Music to use your account, playlists and official offline downloads.",
            color = Muted, fontSize = 13.sp, lineHeight = 19.sp
        )
        Row(Modifier.fillMaxWidth().background(Tile, RoundedCornerShape(20.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(Color(0xFF292B2D), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayCircle, null, tint = Accent, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("YouTube Music", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(if (installed) "Installed on this device" else "Not installed", color = if (installed) Accent else Muted, fontSize = 11.sp)
            }
            TextButton(onClick = {
                if (installed) openYouTubeMusic(context)
                else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            }) { Text(if (installed) "Open" else "Install", color = Accent, fontWeight = FontWeight.Bold) }
        }
        OutlinedButton(onClick = { openYouTubeMusic(context, search) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Search, null, modifier = Modifier.padding(end = 8.dp))
            Text(if (search.isBlank()) "Browse YouTube Music" else "Search current query")
        }
        Text("YouTube Music manages its own downloads and playback. SDS-Music keeps your imported audio in its separate local library.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
