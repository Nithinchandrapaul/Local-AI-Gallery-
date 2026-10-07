package com.sunny.localphotoai

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var repo: MediaStoreRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = MediaStoreRepository(contentResolver)
        setContent { LocalPhotoAIApp() }
    }

    @Composable
    private fun LocalPhotoAIApp() {
        val scope = rememberCoroutineScope()
        var hasPermission by remember { mutableStateOf(hasMediaPermission()) }
        var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
        var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
        var cleanupResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
        var query by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var status by remember { mutableStateOf("Ready") }
        var modelReady by remember { mutableStateOf(ModelDownloader.isInstalled(this@MainActivity)) }
        var modelProgress by remember { mutableIntStateOf(0) }
        var taskProgress by remember { mutableIntStateOf(0) }
        var retriever by remember { mutableStateOf<SemanticMediaIndex?>(null) }
        var cleanup by remember { mutableStateOf<CleanupEngine?>(null) }
        var tab by remember { mutableIntStateOf(0) }
        var deleteCount by remember { mutableIntStateOf(0) }
        var smartGroups by remember { mutableStateOf<List<SmartCleanup.Group>>(emptyList()) }
        var reclaimableBytes by remember { mutableLongStateOf(0L) }
        var smartCleanup by remember { mutableStateOf<SmartCleanup?>(null) }
        var whatsappCandidates by remember { mutableStateOf<List<WhatsAppCleaner.Candidate>>(emptyList()) }
        var whatsappMode by remember { mutableStateOf("likely") }

        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            hasPermission = grants.values.any { it }
            if (hasPermission) scope.launch { refreshLibrary() }
        }
        val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK) {
                deleteCount = selected.size
                selected = emptySet()
                scope.launch { refreshLibrary(); results = emptyList(); cleanupResults = emptyList(); status = "Deleted $deleteCount photos" }
            }
        }

        suspend fun refreshLibrary() {
            items = withContext(Dispatchers.IO) { repo.loadImages() }
            status = "${items.size} photos found"
        }

        LaunchedEffect(hasPermission) {
            if (hasPermission && items.isEmpty()) refreshLibrary()
        }

        fun requestPermission() {
            val p = if (android.os.Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissionLauncher.launch(p)
        }

        fun downloadModel() {
            scope.launch {
                status = "Downloading EmbeddingGemma 2…"
                try {
                    withContext(Dispatchers.IO) { ModelDownloader.download(this@MainActivity) { modelProgress = it } }
                    modelReady = true
                    status = "Model ready"
                } catch (e: Exception) { status = "Model download failed: ${e.message}" }
            }
        }

        fun startEngine() {
            scope.launch {
                status = "Starting local AI engine…"
                try {
                    val idx = withContext(Dispatchers.IO) {
                        SemanticMediaIndex(this@MainActivity, ModelDownloader.modelFile(this@MainActivity)).also { it.initialize(true) }
                    }
                    retriever?.close()
                    retriever = idx
                    cleanup = CleanupEngine(contentResolver)
                    smartCleanup = SmartCleanup(contentResolver)
                    status = "AI ready — building local photo index…"
                    withContext(Dispatchers.IO) { idx.indexAll(items) { taskProgress = it } }
                    status = "AI index ready — ${items.size} photos"
                } catch (e: Exception) { status = "Engine failed: ${e.message}" }
            }
        }

        fun runSearch(text: String) {
            query = text
            if (text.isBlank() || retriever == null) {
                results = if (text.isBlank()) emptyList() else results
                return
            }
            scope.launch {
                status = "Searching locally…"
                try {
                    val found = withContext(Dispatchers.IO) { retriever!!.search(text, 300) }
                    val scoreById = found.associate { it.id() to it.score() }
                    results = items.filter { it.id in scoreById }.sortedByDescending { scoreById[it.id] ?: 0.0 }
                    status = "${results.size} semantic matches"
                } catch (e: Exception) { status = "Search failed: ${e.message}" }
            }
        }

        fun loadSmartCleanup() {
            val engine = smartCleanup ?: run { status = "Start AI first"; return }
            scope.launch {
                selected = emptySet(); taskProgress = 0
                status = "Finding similar-photo groups and choosing the best keeper…"
                smartGroups = engine.analyze(items) { taskProgress = it }
                reclaimableBytes = smartGroups.sumOf { it.reclaimableBytes }
                cleanupResults = smartGroups.flatMap { it.removable }
                status = "${smartGroups.size} smart groups • ${cleanupResults.size} removable photos • ${formatBytes(reclaimableBytes)} reclaimable"
            }
        }

        fun loadCleanup(type: String) {
            val engine = cleanup ?: run { status = "Start AI first"; return }
            scope.launch {
                selected = emptySet(); taskProgress = 0
                status = "Analyzing ${items.size} photos…"
                cleanupResults = when (type) {
                    "duplicates" -> {
                        val groups = engine.findExactDuplicates(items) { taskProgress = it }
                        groups.flatMap { group -> group.items.drop(1) }
                    }
                    "similar" -> {
                        val groups = engine.findSimilarCandidates(items) { taskProgress = it }
                        groups.flatMap { it.drop(1) }
                    }
                    "blurry" -> engine.findBlurry(items) { taskProgress = it }
                    "large" -> items.filter { it.sizeBytes >= 10L * 1024 * 1024 }
                    "screenshots" -> items.filter { it.displayName.contains("screenshot", ignoreCase = true) }
                    else -> emptyList()
                }
                status = "${cleanupResults.size} cleanup candidates"
            }
        }

        fun loadWhatsApp(mode: String = whatsappMode) {
            whatsappMode = mode
            scope.launch {
                selected = emptySet()
                status = "Checking WhatsApp downloaded images…"
                whatsappCandidates = withContext(Dispatchers.Default) {
                    items.mapNotNull { WhatsAppCleaner.classify(it) }
                        .filter { if (mode == "likely") it.category == WhatsAppCleaner.Category.LIKELY_FORWARD else true }
                        .sortedWith(compareByDescending<WhatsAppCleaner.Candidate> { it.score }.thenByDescending { it.item.dateTaken })
                }
                cleanupResults = whatsappCandidates.map { it.item }
                val likely = whatsappCandidates.count { it.category == WhatsAppCleaner.Category.LIKELY_FORWARD }
                val bytes = whatsappCandidates.sumOf { it.item.sizeBytes }
                status = "WhatsApp: ${whatsappCandidates.size} candidates • $likely likely forwards • ${formatBytes(bytes)}"
                tab = 1
            }
        }

        fun deleteSelected() {
            val chosen = items.filter { it.id in selected }
            if (chosen.isEmpty()) return
            val request = MediaStore.createDeleteRequest(contentResolver, chosen.map { it.uri })
            deleteLauncher.launch(request.intentSender)
        }

        val shown = when {
            tab == 0 && query.isBlank() -> items
            tab == 0 -> results
            else -> cleanupResults
        }

        MaterialTheme {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Local Photo AI") },
                        actions = { TextButton(onClick = { scope.launch { refreshLibrary() } }) { Text("Rescan") } }
                    )
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                    if (!hasPermission) {
                        Spacer(Modifier.height(30.dp))
                        Text("Local AI Photo Cleaner", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Search, find duplicates and clean your gallery without sending photos to the cloud.")
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = { requestPermission() }) { Text("Allow photo access") }
                    } else {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Search") })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Clean") })
                        }
                        Spacer(Modifier.height(10.dp))

                        if (tab == 0) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { runSearch(it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("Try: receipts, screenshots, my car…") }
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AssistChip(onClick = { if (!modelReady) downloadModel() }, label = { Text(if (modelReady) "Model ready" else "Download AI model") })
                                if (modelReady && retriever == null) Button(onClick = { startEngine() }) { Text("Start AI") }
                            }
                        } else {
                            Text("AI Cleanup", style = MaterialTheme.typography.titleLarge)
                            Text("Review recommendations before deletion. Nothing is deleted automatically.", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { loadSmartCleanup() }, modifier = Modifier.fillMaxWidth()) { Text("Smart cleanup — keep best photos") }
                            if (smartGroups.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text("${smartGroups.size} groups • ${cleanupResults.size} candidates • ${formatBytes(reclaimableBytes)} recoverable", style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { loadCleanup("duplicates") }) { Text("Exact") }
                                OutlinedButton(onClick = { loadCleanup("similar") }) { Text("Similar") }
                                OutlinedButton(onClick = { loadCleanup("blurry") }) { Text("Blurry") }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { loadCleanup("large") }) { Text(">10 MB") }
                                OutlinedButton(onClick = { loadCleanup("screenshots") }) { Text("Screenshots") }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = { loadWhatsApp("likely") }, modifier = Modifier.weight(1f)) { Text("WhatsApp forwards") }
                                OutlinedButton(onClick = { loadWhatsApp("all") }, modifier = Modifier.weight(1f)) { Text("All WhatsApp") }
                            }
                            if (whatsappCandidates.isNotEmpty()) {
                                Text("Forward detection is heuristic: WhatsApp does not expose a reliable forwarded flag to gallery apps.", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        if (taskProgress in 1..99) {
                            LinearProgressIndicator(progress = { taskProgress / 100f }, Modifier.fillMaxWidth())
                        }
                        if (!modelReady && modelProgress > 0) {
                            LinearProgressIndicator(progress = { modelProgress / 100f }, Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        if (whatsappCandidates.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Likely-forward score: higher means more likely to be received/forwarded-style media, not proof of forwarding.", style = MaterialTheme.typography.labelSmall)
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${shown.size} photos", style = MaterialTheme.typography.titleMedium)
                            if (shown.isNotEmpty()) {
                                TextButton(onClick = { selected = if (shown.all { it.id in selected }) emptySet() else selected + shown.map { it.id } }) {
                                    Text(if (shown.all { it.id in selected }) "Clear" else "Select all")
                                }
                            }
                        }

                        if (selected.isNotEmpty()) {
                            Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${selected.size} selected")
                                    Button(onClick = { deleteSelected() }) { Text("Delete") }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            items(shown, key = { it.id }) { item ->
                                val isSelected = item.id in selected
                                Box(
                                    Modifier.aspectRatio(1f).clickable {
                                        selected = if (isSelected) selected - item.id else selected + item.id
                                    }
                                ) {
                                    AsyncImage(model = item.uri, contentDescription = item.displayName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    if (isSelected) {
                                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)), contentAlignment = Alignment.TopEnd) {
                                            Text("✓", color = Color.White, modifier = Modifier.padding(8.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        DisposableEffect(Unit) { onDispose { retriever?.close() } }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L * 1024L) return "${bytes / 1024L} KB"
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun hasMediaPermission(): Boolean {
        val p = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
