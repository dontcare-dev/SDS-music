package com.sds.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

private val Paper = Color(0xFFB9B6AE)
private val Ink = Color(0xFF302E2A)
private val Tile = Color(0xFF969187)
private val Accent = Color(0xFFE8D8E2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SdsMusicApp() }
    }
}

private data class CollectionCard(val title: String, val subtitle: String, val kind: String, val symbol: String)
private val samples = listOf(
    CollectionCard("Recently played", "Your listening history", "Collection", "◈"),
    CollectionCard("Favorites", "Songs you love", "Playlist", "♡"),
    CollectionCard("Night drive", "Playlist · 12 tracks", "Playlist", "☾"),
    CollectionCard("Imported music", "Your local library", "Library", "♫"),
    CollectionCard("Loop moments", "Saved song sections", "Collection", "↻"),
    CollectionCard("Discover", "Find something new", "Explore", "⌕")
)

@Composable
private fun SdsMusicApp() {
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<CollectionCard?>(null) }
    var playing by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, background = Paper, surface = Paper, onSurface = Ink)) {
        Scaffold(containerColor = Paper, bottomBar = {
            NavigationBar(containerColor = Color(0xFFA9A69E), contentColor = Ink) {
                listOf("Home", "Search", "Library", "Player").forEach { label ->
                    val icon = when(label) { "Home" -> Icons.Rounded.Home; "Search" -> Icons.Rounded.Search; "Library" -> Icons.Rounded.LibraryMusic; else -> Icons.Rounded.GraphicEq }
                    NavigationBarItem(selected = tab == label, onClick = { tab = label }, icon = { Icon(icon, null) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Accent))
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("SDS-MUSIC", fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                        Text(if (selected == null) "Your little music corner." else selected!!.title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { showImport = true }) { Icon(Icons.Rounded.AddCircle, "Import music", modifier = Modifier.size(30.dp)) }
                }
                when(tab) {
                    "Home" -> {
                        Text("MADE FOR YOUR MOOD", fontSize = 10.sp, letterSpacing = 2.sp, color = Ink.copy(alpha=.65f))
                        Box(Modifier.fillMaxWidth().padding(vertical=12.dp).height(150.dp).background(Color(0xFF77736B), RoundedCornerShape(24.dp)).padding(18.dp)) {
                            Column(Modifier.align(Alignment.BottomStart)) {
                                Text("PIXEL COLLECTION", color=Color.White, fontSize=11.sp, letterSpacing=2.sp)
                                Text("A soundtrack of you.", color=Color.White, fontSize=23.sp, fontWeight=FontWeight.Bold)
                            }
                            Text("✧ ｡ﾟ", Modifier.align(Alignment.TopEnd), color=Accent, fontSize=28.sp)
                        }
                        Text("YOUR COLLECTION", fontSize=11.sp, letterSpacing=2.sp, modifier=Modifier.padding(vertical=8.dp))
                        LazyVerticalGrid(columns=GridCells.Fixed(2), verticalArrangement=Arrangement.spacedBy(12.dp), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            items(samples) { card ->
                                Column(Modifier.fillMaxWidth().background(Tile, RoundedCornerShape(20.dp)).clickable { selected=card; tab="Library" }.padding(12.dp)) {
                                    Box(Modifier.fillMaxWidth().height(112.dp).background(Color(0xFF77736B), RoundedCornerShape(14.dp)), contentAlignment=Alignment.Center) {
                                        Text(card.symbol, color=Accent, fontSize=52.sp)
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Text(card.title, color=Color.White, fontWeight=FontWeight.SemiBold)
                                    Text(card.subtitle, color=Color.White.copy(alpha=.75f), fontSize=11.sp)
                                }
                            }
                        }
                    }
                    "Search" -> Column(Modifier.padding(top=12.dp)) {
                        OutlinedTextField(value="", onValueChange={}, modifier=Modifier.fillMaxWidth(), placeholder={Text("Search your music")}, leadingIcon={Icon(Icons.Rounded.Search,null)}, shape=RoundedCornerShape(18.dp), singleLine=true)
                        Text("Search your imported library or add music from your device.", Modifier.padding(top=18.dp), color=Ink.copy(alpha=.7f))
                        Button(onClick={showImport=true}, modifier=Modifier.padding(top=12.dp)) { Text("Import audio") }
                    }
                    "Library" -> Column(Modifier.padding(top=16.dp)) { Text(selected?.title ?: "Your library", fontSize=24.sp, fontWeight=FontWeight.Bold); Text("Your imported tracks and playlists will appear here.", Modifier.padding(top=8.dp)) }
                    else -> Column(Modifier.fillMaxSize(), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
                        Text("♫", fontSize=72.sp); Text("Nothing playing yet", fontSize=22.sp, fontWeight=FontWeight.SemiBold)
                        Button(onClick={tab="Library"}) { Text("Open library") }
                        Row(verticalAlignment=Alignment.CenterVertically) { IconButton(onClick={playing=!playing}) { Icon(if(playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play") }; Text(if(playing) "Playing" else "Paused") }
                    }
                }
            }
        }
        if(showImport) AlertDialog(onDismissRequest={showImport=false}, title={Text("Add music")}, text={Text("Choose audio files from your device using Android's file picker. URL imports will be added for supported, authorized sources.")}, confirmButton={TextButton(onClick={showImport=false}) { Text("Got it") }})
    }
}
