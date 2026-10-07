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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
    var showWelcome by remember { mutableStateOf(true) }
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
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Sunny Local AI Gallery") },
                    actions = {
                        TextButton(enabled = !busy && permission, onClick = {
                            scope.launch {
                                busy = true
                                status = "Loading on-device AI..."
                                val ok = embedder.initialize()
                                if (!ok) status = "On-device AI could not be loaded: ${embedder.lastError ?: "model unavailable"}"
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
                                    status = "AI model unavailable: ${embedder.lastError ?: ""}"
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
        if (showWelcome) {
            WelcomeScreen(
                onContinue = { showWelcome = false },
                onFeedback = {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                        putExtra(Intent.EXTRA_SUBJECT, "Sunny Local AI Gallery Feedback")
                        putExtra(Intent.EXTRA_TEXT, "Hi Sunny Local AI Gallery team,\n\nMy feedback:\n")
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
                painter = painterResource(R.drawable.sunny_ai_gallery_icon),
                contentDescription = "Sunny Local AI Gallery",
                modifier = Modifier.size(180.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(28.dp))
            Text("Hi Folks!", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Welcome to Sunny Local AI Gallery", style = MaterialTheme.typography.headlineSmall)
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
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    val displayItems = remember(report, selectedCategory) {
        if (report == null) emptyList()
        else when (selectedCategory) {
            "WA Forwarded" -> report.whatsAppReport.likelyForwarded
            "WA Sent" -> report.whatsAppReport.sentMedia
            "WA Large" -> report.whatsAppReport.largeMedia
            "Duplicates" -> report.recommendedDeleteIds.mapNotNull { id -> items.firstOrNull { it.id == id } }
            "Low Quality" -> report.qualityReport.lowQualityPhotos
            "Burst Shots" -> report.qualityReport.burstGroups.flatten().distinctBy { it.id }
            "Blurry" -> report.blurry
            "Screenshots" -> report.screenshots
            else -> {
                (report.recommendedDeleteIds.mapNotNull { id -> items.firstOrNull { it.id == id } } +
                 report.whatsAppReport.likelyForwarded +
                 report.whatsAppReport.sentMedia +
                 report.qualityReport.lowQualityPhotos +
                 report.screenshots).distinctBy { x -> x.id }
            }
        }
    }

    LaunchedEffect(displayItems) {
        selectedIds = displayItems.map { it.id }.toSet()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(enabled = !busy && items.isNotEmpty(), onClick = onAnalyze) {
                Text(if (report == null) "Analyze Gallery" else "Re-Analyze")
            }
            if (report != null && displayItems.isNotEmpty()) {
                val selectedItems = displayItems.filter { it.id in selectedIds }
                Button(
                    enabled = selectedItems.isNotEmpty() && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = { onDelete(selectedItems) }
                ) {
                    Text("Delete Selected (${selectedItems.size})")
                }
            }
        }

        Text(status, style = MaterialTheme.typography.bodySmall)

        if (report != null) {
            val wa = report.whatsAppReport
            val q = report.qualityReport

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "WhatsApp Intelligence",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Total: ${wa.totalCount} media • ${"%.1f".format(wa.totalBytes / 1024.0 / 1024.0)} MB (Received: ${wa.receivedCount}, Sent: ${wa.sentCount})",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategory == "WA Forwarded",
                            onClick = { selectedCategory = "WA Forwarded" },
                            label = { Text("Forwarded (${wa.likelyForwarded.size})") }
                        )
                        FilterChip(
                            selected = selectedCategory == "WA Sent",
                            onClick = { selectedCategory = "WA Sent" },
                            label = { Text("Sent (${wa.sentCount})") }
                        )
                        FilterChip(
                            selected = selectedCategory == "WA Large",
                            onClick = { selectedCategory = "WA Large" },
                            label = { Text("Large >2MB (${wa.largeMedia.size})") }
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Visual Quality Intelligence",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Blurry: ${q.blurryCount} • Poor Exposure: ${q.badExposureCount} • Compression: ${q.heavilyCompressedCount} • Bursts: ${q.burstGroupsCount}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategory == "Low Quality",
                            onClick = { selectedCategory = "Low Quality" },
                            label = { Text("Low Quality (${q.lowQualityPhotos.size})") }
                        )
                        FilterChip(
                            selected = selectedCategory == "Burst Shots",
                            onClick = { selectedCategory = "Burst Shots" },
                            label = { Text("Burst Sets (${q.burstGroupsCount})") }
                        )
                        FilterChip(
                            selected = selectedCategory == "Duplicates",
                            onClick = { selectedCategory = "Duplicates" },
                            label = { Text("Duplicates (${report.exactDuplicates.size})") }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == "All",
                    onClick = { selectedCategory = "All" },
                    label = { Text("All Candidates") }
                )
                FilterChip(
                    selected = selectedCategory == "Screenshots",
                    onClick = { selectedCategory = "Screenshots" },
                    label = { Text("Screenshots (${report.screenshots.size})") }
                )
            }

            val reasonHint = when (selectedCategory) {
                "WA Forwarded" -> "Likely forwarded (heuristic: compressed resolution, non-camera naming, low filesize). Review before deletion."
                "WA Sent" -> "Sent copies stored in WhatsApp Sent folder. Redundant if you already have the original."
                "WA Large" -> "WhatsApp photos and media exceeding 2 MB."
                "Low Quality" -> "Photos with quality score < 45 (severe blur, poor exposure, or heavy compression artifacts)."
                "Burst Shots" -> "Sequences of photos taken within seconds of each other. The highest quality photo is preserved."
                "Duplicates" -> "Exact duplicate photos verified via SHA-256. Best quality photo is preserved."
                "Blurry" -> "Photos with low sharpness (Laplacian variance < 80)."
                "Screenshots" -> "Screen captures detected in screenshots directory."
                else -> "All identified cleanup candidates. Review carefully before deleting."
            }
            Text(
                reasonHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (displayItems.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${selectedIds.intersect(displayItems.map { it.id }.toSet()).size} of ${displayItems.size} selected",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row {
                        TextButton(onClick = { selectedIds = displayItems.map { it.id }.toSet() }) {
                            Text("Select All")
                        }
                        TextButton(onClick = { selectedIds = emptySet() }) {
                            Text("Deselect All")
                        }
                    }
                }
            }

            SelectablePhotoGrid(
                items = displayItems,
                selectedIds = selectedIds,
                onToggleSelect = { id ->
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                }
            )
        }
    }
}

@Composable
private fun SelectablePhotoGrid(
    items: List<MediaItem>,
    selectedIds: Set<Long>,
    onToggleSelect: (Long) -> Unit
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
                    .clickable { onToggleSelect(item.id) }
            ) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect(item.id) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
                )

                val label = when {
                    item.isWhatsAppSent -> "Sent"
                    item.isLikelyForwarded -> "${item.whatsAppForwardConfidence}% Fwd"
                    item.qualityScore < 50 -> "Score ${item.qualityScore}"
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
            AsyncImage(
                model = item.uri,
                contentDescription = item.name,
                modifier = Modifier.aspectRatio(1f).fillMaxWidth(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
