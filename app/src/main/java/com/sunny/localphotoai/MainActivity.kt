package com.sunny.localphotoai

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
    var showWelcome by remember { mutableStateOf(true) }
    val permissionsToRequest = remember {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    var permission by remember {
        mutableStateOf(
            permissionsToRequest.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permission = results.values.any { it }
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
    val clusterEngine = remember { AlbumClusterEngine(index) }

    var clusters by remember { mutableStateOf<List<MediaCluster>>(emptyList()) }
    var selectedCluster by remember { mutableStateOf<MediaCluster?>(null) }

    // Live Real-Time Analysis & Indexing State
    var liveCurrentItem by remember { mutableStateOf<MediaItem?>(null) }
    var liveProgress by remember { mutableFloatStateOf(0f) }
    var liveRecentItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var liveTitle by remember { mutableStateOf("") }
    var stopRequested by remember { mutableStateOf(false) }
    var showIndexOptionsDialog by remember { mutableStateOf(false) }

    fun startIndexing(limit: Int?) {
        scope.launch {
            busy = true
            stopRequested = false
            liveTitle = if (limit != null) "Indexing AI (Recent $limit Photos)" else "Indexing AI Semantic Search"
            liveProgress = 0f
            liveRecentItems = emptyList()
            status = "Loading on-device AI..."
            val ok = embedder.initialize()
            if (!ok) {
                status = "On-device AI could not be loaded: ${embedder.lastError ?: "model unavailable"}"
                busy = false
            } else {
                val allPhotos = items.filter { !it.isVideo }
                val photos = if (limit != null) allPhotos.take(limit) else allPhotos
                val currentIds = allPhotos.map { it.id }.toSet()
                index.removeMissing(currentIds)

                status = "Checking indexed cache..."
                val metadataMap = index.getIndexedMetadataMap()
                var updated = 0
                var unchanged = 0
                val batch = mutableListOf<Pair<MediaItem, FloatArray>>()
                val total = photos.size

                for (i in photos.indices) {
                    if (stopRequested) {
                        status = "Indexing paused: $i/$total scanned ($updated indexed). Ready to search!"
                        break
                    }
                    val item = photos[i]
                    val existing = metadataMap[item.id]
                    var wasUpdated = false
                    if (existing != null && existing.first == item.size && existing.second == item.dateAdded) {
                        unchanged++
                    } else {
                        liveCurrentItem = item
                        val e = embedder.embedImage(item.uri)
                        if (e != null) {
                            batch += (item to e)
                            updated++
                            wasUpdated = true
                            liveRecentItems = (listOf(item) + liveRecentItems).take(12)
                        }
                    }

                    // Throttle Compose state updates to prevent recomposition slowdown
                    if (wasUpdated || i % 8 == 0 || i == photos.lastIndex) {
                        liveProgress = (i + 1).toFloat() / total
                        status = "Indexed ${i + 1}/$total • $updated new embeddings"
                    }

                    if (batch.size >= 10 || i == photos.lastIndex) {
                        index.putBatch(batch)
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) {
                    index.putBatch(batch)
                    batch.clear()
                }
                if (!stopRequested) {
                    status = "AI index ready: ${index.count()} photos indexed • $updated new"
                }
                busy = false
                liveCurrentItem = null
            }
        }
    }

    DisposableEffect(Unit) { onDispose { embedder.close() } }

    LaunchedEffect(permission) {
        if (permission) {
            items = MediaStoreRepository(context).scanAll()
            val photosCount = items.count { !it.isVideo }
            val videosCount = items.count { it.isVideo }
            status = "$photosCount photos, $videosCount videos found"
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Leo Ai local gallery") },
                    actions = {
                        TextButton(enabled = !busy && permission, onClick = {
                            showIndexOptionsDialog = true
                        }) { Text("Index AI") }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, label = { Text("Search") }, icon = {})
                    NavigationBarItem(tab == 1, { tab = 1 }, label = { Text("Albums") }, icon = {})
                    NavigationBarItem(tab == 2, { tab = 2 }, label = { Text("Cleanup") }, icon = {})
                    NavigationBarItem(tab == 3, { tab = 3 }, label = { Text("Assistant") }, icon = {})
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                if (busy && liveTitle.isNotBlank()) {
                    RealTimeProgressCard(
                        title = liveTitle,
                        statusText = status,
                        progress = liveProgress,
                        currentItem = liveCurrentItem,
                        recentItems = liveRecentItems,
                        onStop = { stopRequested = true }
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (!permission) {
                    PermissionCard { permissionLauncher.launch(permissionsToRequest) }
                } else if (tab == 0) {
                    SearchScreen(
                        items = items,
                        query = query,
                        onQuery = { query = it },
                        results = results,
                        status = status,
                        busy = busy,
                        onSearch = {
                            scope.launch {
                                busy = true
                                status = "Searching..."
                                val qLower = query.lowercase().trim()

                                // 1. Semantic embeddings search (if model initialized)
                                val semanticHits = if (embedder.initialize()) {
                                    val qVec = embedder.embedText(query)
                                    if (qVec != null) index.search(qVec, items, limit = 100) else emptyList()
                                } else emptyList()

                                val semanticItems = semanticHits.filter { it.score > 0.40 }.map { it.item }
                                val semanticIds = semanticItems.map { it.id }.toSet()

                                // 2. High-speed heuristic & metadata search across ALL items
                                val heuristicMatches = items.filter { item ->
                                    item.id !in semanticIds && (
                                        item.name.lowercase().contains(qLower) ||
                                        item.path.lowercase().contains(qLower) ||
                                        (qLower in listOf("screenshot", "screenshots") && item.isScreenshot) ||
                                        (qLower in listOf("whatsapp", "wa") && item.isWhatsApp) ||
                                        (qLower in listOf("video", "videos") && item.isVideo) ||
                                        (qLower in listOf("sent") && item.isWhatsAppSent) ||
                                        (qLower in listOf("blur", "blurry") && item.isBlurry) ||
                                        (qLower in listOf("burst", "bursts") && item.isBurstCandidate) ||
                                        (qLower in listOf("large", "heavy") && item.sizeMb > 10.0)
                                    )
                                }

                                results = semanticItems + heuristicMatches
                                status = if (semanticItems.isNotEmpty()) {
                                    "${semanticItems.size} semantic AI matches + ${heuristicMatches.size} smart matches"
                                } else {
                                    "${results.size} matches found"
                                }
                                busy = false
                            }
                        },
                        onRescan = {
                            items = MediaStoreRepository(context).scanAll()
                            val photosCount = items.count { !it.isVideo }
                            val videosCount = items.count { it.isVideo }
                            status = "$photosCount photos, $videosCount videos found"
                        }
                    )
                } else if (tab == 1) {
                    AlbumsScreen(
                        items = items,
                        clusters = clusters,
                        selectedCluster = selectedCluster,
                        onSelectCluster = { selectedCluster = it },
                        busy = busy,
                        onCluster = {
                            scope.launch {
                                busy = true
                                status = "Clustering albums using temporal and visual AI..."
                                clusters = clusterEngine.clusterMedia(items)
                                status = "Generated ${clusters.size} smart albums"
                                busy = false
                            }
                        }
                    )
                } else if (tab == 2) {
                    CleanupScreen(
                        items = items,
                        report = report,
                        status = status,
                        busy = busy,
                        onAnalyze = {
                            scope.launch {
                                busy = true
                                stopRequested = false
                                liveTitle = "Analyzing Gallery Clutter & Quality"
                                liveProgress = 0f
                                liveRecentItems = emptyList()
                                status = "Scanning photos and identifying duplicates..."
                                report = analyzer.analyze(items) { current, total, item ->
                                    if (!stopRequested) {
                                        liveCurrentItem = item
                                        liveProgress = current.toFloat() / total
                                        status = "Scanning $current / $total: ${item.name}"
                                        if (item.isBlurry || item.isBadExposure || item.sha256 != null || item.isWhatsApp) {
                                            liveRecentItems = (listOf(item) + liveRecentItems).take(16)
                                        }
                                    }
                                }
                                status = "Analysis complete: ${report?.recommendedDeleteIds?.size ?: 0} duplicates and cleanup items flagged"
                                busy = false
                                liveCurrentItem = null
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
                } else {
                    AssistantScreen(
                        items = items,
                        report = report,
                        analyzer = analyzer,
                        embedder = embedder,
                        index = index,
                        clusterEngine = clusterEngine,
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

        if (showIndexOptionsDialog) {
            val totalPhotos = items.count { !it.isVideo }
            var cachedCount by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                cachedCount = index.count()
            }
            AlertDialog(
                onDismissRequest = { showIndexOptionsDialog = false },
                title = { Text("AI Semantic Search Indexing") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Semantic search uses an on-device AI model to understand photos locally without cloud uploads.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Currently indexed: $cachedCount of $totalPhotos photos",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Text(
                            "Choose indexing scope (you can search immediately or pause anytime):",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Button(
                            onClick = {
                                showIndexOptionsDialog = false
                                startIndexing(500)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("⚡ Quick: Recent 500 Photos (~1-2 min)")
                        }
                        OutlinedButton(
                            onClick = {
                                showIndexOptionsDialog = false
                                startIndexing(2000)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🚀 Extended: Recent 2,000 Photos (~5 min)")
                        }
                        OutlinedButton(
                            onClick = {
                                showIndexOptionsDialog = false
                                startIndexing(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📚 Full Library: All $totalPhotos Photos")
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showIndexOptionsDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showWelcome) {
            WelcomeScreen(
                onContinue = { showWelcome = false },
                onFeedback = {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                        putExtra(Intent.EXTRA_SUBJECT, "Leo Ai local gallery Feedback")
                        putExtra(Intent.EXTRA_TEXT, "Hi Leo Ai local gallery team,\n\nMy feedback:\n")
                    }
                    runCatching { context.startActivity(intent) }
                }
            )
        }
        }
    }
}

@Composable
private fun WelcomeScreen(
    onContinue: () -> Unit,
    onFeedback: () -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.leo_ai_gallery_icon),
                contentDescription = "Leo Ai local gallery",
                modifier = Modifier.size(180.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(28.dp))
            Text("Hi Folks!", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Welcome to Leo Ai local gallery", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text(
                "Your photos stay on your device. Search, understand and clean your gallery with on-device AI.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Enter Gallery")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onFeedback, modifier = Modifier.fillMaxWidth()) {
                Text("Send Feedback")
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "We'd love to hear what you think. Your feedback helps us make the gallery better.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Photo access required", style = MaterialTheme.typography.titleLarge)
            Text("Leo Ai local gallery needs access to your photos so it can search and clean them on this device.")
            Button(onClick = onGrant) { Text("Allow photos") }
        }
    }
}

@Composable
private fun SearchScreen(
    items: List<MediaItem>,
    query: String,
    onQuery: (String) -> Unit,
    results: List<MediaItem>,
    status: String,
    busy: Boolean,
    onSearch: () -> Unit,
    onRescan: () -> Unit
) {
    var mediaFilter by remember { mutableStateOf("All") }
    var timeFilter by remember { mutableStateOf("All") }
    var highQualityOnly by remember { mutableStateOf(false) }

    val photosCount = remember(items) { items.count { !it.isVideo } }
    val videosCount = remember(items) { items.count { it.isVideo } }

    val displayedItems = remember(items, results, query, mediaFilter, timeFilter, highQualityOnly) {
        val base = if (results.isNotEmpty() || query.isNotBlank()) results else items
        val nowSec = System.currentTimeMillis() / 1000L
        base.filter { item ->
            val matchMedia = when (mediaFilter) {
                "Photos" -> !item.isVideo
                "Videos" -> item.isVideo
                else -> true
            }
            val matchTime = when (timeFilter) {
                "30 Days" -> (nowSec - item.dateAdded) <= 30L * 86400L
                "1 Year" -> (nowSec - item.dateAdded) <= 365L * 86400L
                else -> true
            }
            val matchQuality = if (highQualityOnly) item.qualityScore >= 60 else true
            matchMedia && matchTime && matchQuality
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search photos & media") },
            placeholder = { Text("e.g. receipts, my car, videos, WhatsApp") },
            singleLine = true
        )
        val suggestions = listOf(
            "receipts",
            "payment screenshots",
            "my car",
            "family photos",
            "documents",
            "travel photos",
            "memes",
            "WhatsApp images"
        )
        Text("Try a smart search", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.take(3).forEach { suggestion ->
                AssistChip(
                    onClick = { onQuery(suggestion) },
                    label = { Text(suggestion) },
                    enabled = !busy
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.drop(3).take(3).forEach { suggestion ->
                AssistChip(
                    onClick = { onQuery(suggestion) },
                    label = { Text(suggestion) },
                    enabled = !busy
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = mediaFilter == "All",
                onClick = { mediaFilter = "All" },
                label = { Text("All (${items.size})") }
            )
            FilterChip(
                selected = mediaFilter == "Photos",
                onClick = { mediaFilter = "Photos" },
                label = { Text("Photos ($photosCount)") }
            )
            FilterChip(
                selected = mediaFilter == "Videos",
                onClick = { mediaFilter = "Videos" },
                label = { Text("Videos ($videosCount)") }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = timeFilter == "All",
                onClick = { timeFilter = "All" },
                label = { Text("All Time") }
            )
            FilterChip(
                selected = timeFilter == "30 Days",
                onClick = { timeFilter = "30 Days" },
                label = { Text("Past 30d") }
            )
            FilterChip(
                selected = timeFilter == "1 Year",
                onClick = { timeFilter = "1 Year" },
                label = { Text("Past 1y") }
            )
            FilterChip(
                selected = highQualityOnly,
                onClick = { highQualityOnly = !highQualityOnly },
                label = { Text("★ High Quality") }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = query.isNotBlank() && !busy, onClick = onSearch) { Text("Search") }
            OutlinedButton(enabled = !busy, onClick = onRescan) { Text("Rescan") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        PhotoGrid(displayedItems)
    }
}

@Composable
private fun AlbumsScreen(
    items: List<MediaItem>,
    clusters: List<MediaCluster>,
    selectedCluster: MediaCluster?,
    onSelectCluster: (MediaCluster?) -> Unit,
    busy: Boolean,
    onCluster: () -> Unit
) {
    if (selectedCluster != null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { onSelectCluster(null) }) {
                    Text("← All Albums")
                }
                Text(
                    "${selectedCluster.items.size} items • ${"%.1f".format(selectedCluster.totalSizeMb)} MB",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(selectedCluster.title, style = MaterialTheme.typography.titleLarge)
            Text(
                "${selectedCluster.category} • ${selectedCluster.dateRangeFormatted}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PhotoGrid(selectedCluster.items)
        }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI Smart Albums",
                    style = MaterialTheme.typography.titleMedium
                )
                Button(enabled = !busy && items.isNotEmpty(), onClick = onCluster) {
                    Text(if (clusters.isEmpty()) "Build Smart Albums" else "Re-Cluster")
                }
            }
            if (clusters.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No albums generated yet. Tap 'Build Smart Albums' to cluster your photos using on-device temporal proximity and visual AI.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(clusters, key = { it.id }) { cluster ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectCluster(cluster) },
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column {
                                Box(modifier = Modifier.aspectRatio(1.2f).fillMaxWidth()) {
                                    AsyncImage(
                                        model = cluster.coverItem.uri,
                                        contentDescription = cluster.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                        shape = MaterialTheme.shapes.extraSmall,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                    ) {
                                        Text(
                                            text = "${cluster.items.size} items",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Column(Modifier.padding(8.dp)) {
                                    Text(
                                        text = cluster.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = cluster.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = cluster.dateRangeFormatted,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
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
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var inspectingItemIndex by remember { mutableStateOf<Int?>(null) }
    var showDetailsDialog by remember { mutableStateOf(false) }

    val displayItems = remember(report, selectedCategory) {
        if (report == null) emptyList()
        else when (selectedCategory) {
            "WA Forwarded" -> report.whatsAppReport.likelyForwarded
            "WA Sent" -> report.whatsAppReport.sentMedia
            "WA Large" -> report.whatsAppReport.largeMedia
            "Duplicates" -> report.recommendedDeleteIds.mapNotNull { id -> items.firstOrNull { it.id == id } }
            "Low Quality" -> report.qualityReport.lowQualityPhotos
            "Burst Shots" -> report.qualityReport.burstGroups.flatten().distinctBy { it.id }
            "Large Videos" -> report.largeVideos
            "Blurry" -> report.blurry
            "Screenshots" -> report.screenshots
            else -> {
                (report.recommendedDeleteIds.mapNotNull { id -> items.firstOrNull { it.id == id } } +
                 report.whatsAppReport.likelyForwarded +
                 report.whatsAppReport.sentMedia +
                 report.qualityReport.lowQualityPhotos +
                 report.largeVideos +
                 report.screenshots).distinctBy { x -> x.id }
            }
        }
    }

    LaunchedEffect(displayItems) {
        selectedIds = displayItems.map { it.id }.toSet()
    }

    Column(Modifier.fillMaxSize()) {
        // TOP CONTROLS ROW
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = !busy && items.isNotEmpty(),
                onClick = onAnalyze,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(if (report == null) "Analyze Gallery" else "Re-Analyze", style = MaterialTheme.typography.labelMedium)
            }

            if (report != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { showDetailsDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("ℹ Stats", style = MaterialTheme.typography.labelMedium)
                    }
                    val selectedItems = displayItems.filter { it.id in selectedIds }
                    Button(
                        enabled = selectedItems.isNotEmpty() && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        onClick = { onDelete(selectedItems) }
                    ) {
                        Text("Delete (${selectedItems.size})", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        if (report != null) {
            val wa = report.whatsAppReport
            val q = report.qualityReport

            // SLEEK HORIZONTAL CATEGORY REEL (Takes only ~38dp!)
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    val allCount = (report.recommendedDeleteIds + wa.likelyForwarded.map { it.id } + wa.sentMedia.map { it.id } + q.lowQualityPhotos.map { it.id } + report.largeVideos.map { it.id } + report.screenshots.map { it.id }).distinct().size
                    FilterChip(
                        selected = selectedCategory == "All",
                        onClick = { selectedCategory = "All" },
                        label = { Text("All ($allCount)") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedCategory == "Duplicates",
                        onClick = { selectedCategory = "Duplicates" },
                        label = { Text("Duplicates (${report.exactDuplicates.size})") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedCategory == "Blurry",
                        onClick = { selectedCategory = "Blurry" },
                        label = { Text("Blurry (${report.blurry.size})") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedCategory == "Low Quality",
                        onClick = { selectedCategory = "Low Quality" },
                        label = { Text("Low Quality (${q.lowQualityPhotos.size})") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedCategory == "Burst Shots",
                        onClick = { selectedCategory = "Burst Shots" },
                        label = { Text("Burst Sets (${q.burstGroupsCount})") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedCategory == "Screenshots",
                        onClick = { selectedCategory = "Screenshots" },
                        label = { Text("Screenshots (${report.screenshots.size})") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedCategory == "WA Sent",
                        onClick = { selectedCategory = "WA Sent" },
                        label = { Text("WA Sent (${wa.sentCount})") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedCategory == "WA Forwarded",
                        onClick = { selectedCategory = "WA Forwarded" },
                        label = { Text("WA Forwarded (${wa.likelyForwarded.size})") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedCategory == "Large Videos",
                        onClick = { selectedCategory = "Large Videos" },
                        label = { Text("Large Videos (${report.largeVideos.size})") }
                    )
                }
            }

            // COMPACT SELECTION CONTROLS BAR (Takes only ~32dp!)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val currentSelectedCount = selectedIds.intersect(displayItems.map { it.id }.toSet()).size
                Text(
                    "$currentSelectedCount / ${displayItems.size} selected for cleanup",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { selectedIds = displayItems.map { it.id }.toSet() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("Select All", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = { selectedIds = emptySet() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("Deselect All", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // FULL-SCREEN PHOTO REVIEW GRID (Takes 100% of remaining screen!)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SelectablePhotoGrid(
                    items = displayItems,
                    selectedIds = selectedIds,
                    onToggleSelect = { id ->
                        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                    },
                    onInspectItem = { item ->
                        val idx = displayItems.indexOfFirst { it.id == item.id }
                        if (idx >= 0) inspectingItemIndex = idx
                    }
                )
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Tap 'Analyze Gallery' to scan photos for duplicates, blurry pictures, WhatsApp clutter, and large videos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // STATS & INTELLIGENCE BREAKDOWN DIALOG
    if (showDetailsDialog && report != null) {
        val wa = report.whatsAppReport
        val q = report.qualityReport
        val v = report.videoReport
        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            title = { Text("Gallery Intelligence Summary") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("WhatsApp Clutter", style = MaterialTheme.typography.titleSmall)
                    Text("Total: ${wa.totalCount} media • ${"%.1f".format(wa.totalBytes / 1024.0 / 1024.0)} MB\nReceived: ${wa.receivedCount} | Sent copies: ${wa.sentCount} | Large: ${wa.largeMedia.size}", style = MaterialTheme.typography.bodySmall)
                    Divider()
                    Text("Visual Quality", style = MaterialTheme.typography.titleSmall)
                    Text("Blurry: ${q.blurryCount} | Low Quality: ${q.lowQualityPhotos.size}\nBurst Sets: ${q.burstGroupsCount} | Duplicates: ${report.exactDuplicates.size}", style = MaterialTheme.typography.bodySmall)
                    Divider()
                    Text("Video Clutter", style = MaterialTheme.typography.titleSmall)
                    Text("Total: ${v.totalVideos} videos • ${"%.1f".format(v.totalVideoBytes / 1024.0 / 1024.0)} MB\nLarge >50MB: ${report.largeVideos.size}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetailsDialog = false }) { Text("Close") }
            }
        )
    }

    // FULL-SCREEN PHOTO INSPECTOR DIALOG
    if (inspectingItemIndex != null && inspectingItemIndex!! in displayItems.indices) {
        val currentInspectItem = displayItems[inspectingItemIndex!!]
        val isSelected = currentInspectItem.id in selectedIds
        PhotoDetailDialog(
            item = currentInspectItem,
            isSelected = isSelected,
            currentIndex = inspectingItemIndex!! + 1,
            totalCount = displayItems.size,
            onToggleSelect = {
                val id = currentInspectItem.id
                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
            },
            onPrevious = {
                if (inspectingItemIndex!! > 0) inspectingItemIndex = inspectingItemIndex!! - 1
            },
            onNext = {
                if (inspectingItemIndex!! < displayItems.lastIndex) inspectingItemIndex = inspectingItemIndex!! + 1
            },
            onDismiss = { inspectingItemIndex = null }
        )
    }
}

@Composable
private fun SelectablePhotoGrid(
    items: List<MediaItem>,
    selectedIds: Set<Long>,
    onToggleSelect: (Long) -> Unit,
    onInspectItem: (MediaItem) -> Unit
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("No photos in this category")
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
            val isSelected = item.id in selectedIds
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .fillMaxWidth()
                    .clickable { onInspectItem(item) }
            ) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // Checkbox touch target on top-right
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .clickable { onToggleSelect(item.id) }
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect(item.id) }
                    )
                }

                val label = when {
                    item.isWhatsAppSent -> "Sent"
                    item.isLikelyForwarded -> "${item.whatsAppForwardConfidence}% Fwd"
                    item.qualityScore < 50 -> "Score ${item.qualityScore}"
                    item.isBlurry -> "Blurry"
                    else -> null
                }
                if (label != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                if (item.isVideo) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    ) {
                        Text(
                            text = "▶ ${item.durationFormatted.ifEmpty { "Video" }}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoDetailDialog(
    item: MediaItem,
    isSelected: Boolean,
    currentIndex: Int,
    totalCount: Int,
    onToggleSelect: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1
                        )
                        Text(
                            text = "Photo $currentIndex of $totalCount • ${"%.1f".format(item.sizeMb)} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                // Photo Image
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                // Details & Reason
                val reasons = mutableListOf<String>()
                if (item.isBlurry) reasons += "Blurry photo"
                if (item.qualityScore < 50) reasons += "Quality score: ${item.qualityScore}/100"
                if (item.isWhatsAppSent) reasons += "WhatsApp Sent copy"
                if (item.isLikelyForwarded) reasons += "WhatsApp Forwarded (${item.whatsAppForwardConfidence}%)"
                if (item.isScreenshot) reasons += "Screenshot"
                if (item.isVideo && item.sizeMb > 50) reasons += "Large video (>50MB)"
                if (reasons.isEmpty()) reasons += "Cleanup candidate"

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Reason: ${reasons.joinToString(" • ")}", style = MaterialTheme.typography.labelSmall)
                        if (item.width > 0 && item.height > 0) {
                            Text("Resolution: ${item.width} x ${item.height} • Path: ${item.path}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Bottom Controls: Select/Keep Button + Nav arrows
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onToggleSelect,
                        colors = if (isSelected) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                 else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isSelected) "✓ Marked for Deletion (Tap to Keep)" else "Keep Photo (Tap to Mark for Delete)")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(
                            onClick = onPrevious,
                            enabled = currentIndex > 1
                        ) {
                            Text("← Previous")
                        }
                        OutlinedButton(
                            onClick = onNext,
                            enabled = currentIndex < totalCount
                        ) {
                            Text("Next →")
                        }
                    }
                }
            }
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
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .fillMaxWidth()
            ) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (item.isVideo) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    ) {
                        Text(
                            text = "▶ ${item.durationFormatted.ifEmpty { "Video" }}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RealTimeProgressCard(
    title: String,
    statusText: String,
    progress: Float,
    currentItem: MediaItem?,
    recentItems: List<MediaItem>,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onStop,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop", style = MaterialTheme.typography.labelSmall)
                }
            }

            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(statusText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }

            if (currentItem != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AsyncImage(
                        model = currentItem.uri,
                        contentDescription = "Current photo",
                        modifier = Modifier.size(36.dp).clip(MaterialTheme.shapes.extraSmall),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        text = "${currentItem.name} (${"%.1f".format(currentItem.sizeMb)} MB)",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
