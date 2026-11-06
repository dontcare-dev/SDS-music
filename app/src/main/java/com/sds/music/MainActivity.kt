package com.sds.music

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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

private val Paper = Color(0xFFB9B6AE)
private val Ink = Color(0xFF302E2A)
private val Tile = Color(0xFF89857D)
private val Accent = Color(0xFFE8D8E2)

data class Track(val uri: String, val title: String)

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
            Track(item.getString("uri"), item.getString("title"))
        }
    }.getOrDefault(emptyList())
}

private fun saveTracks(context: Context, tracks: List<Track>) {
    val array = JSONArray()
    tracks.forEach { array.put(JSONObject().put("uri", it.uri).put("title", it.title)) }
    context.getSharedPreferences("sds_music", Context.MODE_PRIVATE).edit().putString("tracks", array.toString()).apply()
}

private fun displayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.substringBeforeLast('.') ?: (uri.lastPathSegment ?: "Untitled audio")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SdsMusicApp(context: Context) {
    val player = remember { ExoPlayer.Builder(context).build() }
    var tracks by remember { mutableStateOf(readTracks(context)) }
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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val additions = uris.mapNotNull { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Track(uri.toString(), displayName(context, uri))
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
                currentTitle = tracks.firstOrNull { it.uri == currentUri }?.title ?: "Unknown track"
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

    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, onPrimary = Paper, background = Paper, surface = Paper, onSurface = Ink)) {
        Scaffold(
            containerColor = Paper,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFFA9A69E), contentColor = Ink) {
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
                        Text(when (tab) { "Home" -> "Your little music corner."; "Search" -> "Find your sound."; "Library" -> "Your collection."; else -> "Now playing." }, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Icon(Icons.Rounded.AddCircle, "Import audio", modifier = Modifier.size(30.dp)) }
                }
                when (tab) {
                    "Home" -> {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(158.dp).background(Tile, RoundedCornerShape(24.dp)).padding(18.dp)) {
                            Column(Modifier.align(Alignment.BottomStart)) {
                                Text("YOUR OFFLINE LIBRARY", color = Color.White, fontSize = 11.sp, letterSpacing = 2.sp)
                                Text("A soundtrack of you.", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                                Text("${tracks.size} tracks imported", color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
                            }
                            Text("✧", Modifier.align(Alignment.TopEnd), color = Accent, fontSize = 34.sp)
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("RECENTLY ADDED", fontSize = 11.sp, letterSpacing = 2.sp)
                            TextButton(onClick = { tab = "Library" }) { Text("View all", color = Ink) }
                        }
                        if (tracks.isEmpty()) EmptyLibrary { picker.launch(arrayOf("audio/*")) }
                        else TrackList(tracks.take(5), currentUri) { playTrack(it, tracks, player) }
                    }
                    "Search" -> {
                        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search your music") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(18.dp), singleLine = true)
                        val filtered = tracks.filter { it.title.contains(query, ignoreCase = true) }
                        TrackList(filtered, currentUri) { playTrack(it, tracks, player) }
                    }
                    "Library" -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${tracks.size} tracks", color = Ink.copy(alpha = .7f))
                            TextButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("＋ Import", color = Ink) }
                        }
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
                        Text("LOCAL AUDIO", color = Ink.copy(alpha = .6f), fontSize = 11.sp, letterSpacing = 2.sp)
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
                    Text(if (track.uri == currentUri) "NOW PLAYING" else "LOCAL AUDIO", color = Color.White.copy(alpha = .7f), fontSize = 10.sp, letterSpacing = 1.sp)
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
