package com.sunny.localphotoai

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LocalPhotoAIApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LocalPhotoAIApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it
    }

    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var status by remember { mutableStateOf("Ready") }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<CleanupReport?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    val embedder = remember { EmbeddingEngine(context.applicationContext) }
    val index = remember { SemanticMediaIndex(context.applicationContext) }
    val analyzer = remember { PhotoAnalyzer(context.applicationContext) }

    DisposableEffect(Unit) { onDispose { embedder.close() } }

    LaunchedEffect(permission) {
        if (permission) {
            items = MediaStoreRepository(context).scanImages()
            status = "${items.size} photos found"
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Local Photo AI") },
                    actions = {
                        TextButton(enabled = !busy && permission, onClick = {
                            scope.launch {
                                busy = true
                                status = "Downloading/loading AI model..."
                                val ok = embedder.initialize()
                                if (!ok) status = "AI model could not be loaded. Check internet and retry."
                                else {
                                    val currentIds = items.map { it.id }.toSet()
                                    index.removeMissing(currentIds)
                                    var updated = 0
                                    var unchanged = 0
                                    items.forEachIndexed { i, item ->
                                        if (index.isCurrent(item)) {
                                            unchanged++
                                        } else {
                                            val e = embedder.embedImage(item.uri)
                                            if (e != null) {
                                                index.put(item, e)
                                                updated++
                                            }
                                        }
                                        if (i % 10 == 0 || i == items.lastIndex) {
                                            status = "Indexed ${i + 1}/${items.size} • $updated updated • $unchanged unchanged"
                                        }
                                    }
                                    status = "AI index ready: ${index.count()} photos • $updated updated"
                                }
                                busy = false
                            }
                        }) { Text("Index AI") }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, label = { Text("Search") }, icon = {})
                    NavigationBarItem(tab == 1, { tab = 1 }, label = { Text("Cleanup") }, icon = {})
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                if (!permission) {
                    PermissionCard { permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES) }
                } else if (tab == 0) {
                    SearchScreen(
                        query, { query = it }, results, status, busy,
                        onSearch = {
                            scope.launch {
                                busy = true
                                status = "Searching..."
                                if (!embedder.initialize()) {
                                    status = "AI model unavailable"
                                } else {
                                    results = index.search(embedder.embedText(query) ?: FloatArray(0), items).map { it.item }
                                    status = "${results.size} semantic matches"
                                }
                                busy = false
                            }
                        },
                        onRescan = {
                            items = MediaStoreRepository(context).scanImages()
                            status = "${items.size} photos found"
                        }
                    )
                } else {
                    CleanupScreen(
                        items = items,
                        report = report,
                        status = status,
                        busy = busy,
                        onAnalyze = {
                            scope.launch {
                                busy = true
                                status = "Analyzing duplicates and cleanup categories..."
                                report = analyzer.analyze(items)
                                status = "Analysis complete"
                                busy = false
                            }
                        },
                        onDelete = { selected ->
                            if (selected.isNotEmpty()) {
                                val uris = selected.map { it.uri }
                                val intentSender = MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
                                (context as Activity).startIntentSenderForResult(intentSender, 901, null, 0, 0, 0)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Photo access required", style = MaterialTheme.typography.titleLarge)
            Text("Local Photo AI needs access to your photos so it can search and clean them on this device.")
            Button(onClick = onGrant) { Text("Allow photos") }
        }
    }
}

@Composable
private fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: List<MediaItem>,
    status: String,
    busy: Boolean,
    onSearch: () -> Unit,
    onRescan: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search photos") },
            placeholder = { Text("e.g. receipts, my car, screenshots of payments") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = query.isNotBlank() && !busy, onClick = onSearch) { Text("Search") }
            OutlinedButton(enabled = !busy, onClick = onRescan) { Text("Rescan") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        PhotoGrid(results)
    }
}

@Composable
private fun CleanupScreen(
    items: List<MediaItem>,
    report: CleanupReport?,
    status: String,
    busy: Boolean,
    onAnalyze: () -> Unit,
    onDelete: (List<MediaItem>) -> Unit
) {
    val candidates = report?.let {
        (it.exactDuplicates.flatten().drop(1) +
         it.screenshots +
         it.largeFiles +
         it.likelyForwarded).distinctBy { x -> x.id }
    } ?: emptyList()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && items.isNotEmpty(), onClick = onAnalyze) { Text("Analyze") }
            OutlinedButton(enabled = candidates.isNotEmpty() && !busy, onClick = { onDelete(candidates) }) {
                Text("Review / Delete ${candidates.size}")
            }
        }
        Text(status)
        report?.let {
            Text("Exact duplicate groups: ${it.exactDuplicates.size}")
            Text("Visually similar groups: ${it.visualGroups.size}")
            Text("Screenshots: ${it.screenshots.size}")
            Text("WhatsApp: ${it.whatsapp.size}")
            Text("Likely forwarded: ${it.likelyForwarded.size}")
            Text("Large files >10 MB: ${it.largeFiles.size}")
            Text("Duplicate space recoverable: ${"%.1f".format(it.totalRecoverableBytes / 1024.0 / 1024.0)} MB")
        }
    }
}

@Composable
private fun PhotoGrid(items: List<MediaItem>) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No results")
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(items, key = { it.id }) { item ->
            AsyncImage(
                model = item.uri,
                contentDescription = item.name,
                modifier = Modifier.aspectRatio(1f).fillMaxWidth(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
