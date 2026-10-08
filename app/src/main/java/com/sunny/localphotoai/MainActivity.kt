package com.sunny.localphotoai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LocalPhotoAIApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LocalPhotoAIApp() {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val scope = rememberCoroutineScope()
    var showWelcome by remember { mutableStateOf(true) }

    val permissionsToRequest = remember {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
            list += Manifest.permission.POST_NOTIFICATIONS
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        list.toTypedArray()
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

    val repo = remember { MediaStoreRepository(context.applicationContext) }
    val index = remember { SemanticMediaIndex(context.applicationContext) }
    val analyzer = remember { PhotoAnalyzer(context.applicationContext) }
    val clusterEngine = remember { AlbumClusterEngine(index) }
    val embedder = remember { EmbeddingEngine(context.applicationContext) }

    var allItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var trashedItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var vaultIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var isVaultUnlocked by remember { mutableStateOf(false) }

    // Filter items to exclude vaulted photos from normal browsing
    val items = remember(allItems, vaultIds) {
        allItems.filter { it.id !in vaultIds }
    }
    val vaultedItems = remember(allItems, vaultIds) {
        allItems.filter { it.id in vaultIds }
    }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var status by remember { mutableStateOf("Ready") }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<CleanupReport?>(null) }
    var tab by remember { mutableIntStateOf(0) }

    var clusters by remember { mutableStateOf<List<MediaCluster>>(emptyList()) }
    var selectedCluster by remember { mutableStateOf<MediaCluster?>(null) }

    // Foreground service observation
    val isServiceRunning by IndexingForegroundService.isRunning.collectAsState()
    val serviceProgress by IndexingForegroundService.progress.collectAsState()
    val serviceStatus by IndexingForegroundService.statusMessage.collectAsState()

    var showIndexOptionsDialog by remember { mutableStateOf(false) }
    var showVaultDialog by remember { mutableStateOf(false) }

    // Crash-proof deletion launcher
    val deleteResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            scope.launch {
                allItems = repo.scanAll()
                trashedItems = repo.scanTrashed()
                report = analyzer.analyze(allItems, forceRefresh = false)
                status = "Gallery updated successfully."
                Toast.makeText(context, "Action completed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleTrashRequest(selected: List<MediaItem>, trash: Boolean) {
        if (selected.isEmpty()) return
        val uris = selected.map { it.uri }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val intentSender = MediaStore.createTrashRequest(context.contentResolver, uris, trash).intentSender
                deleteResultLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            } else {
                val intentSender = MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
                deleteResultLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun handlePermanentDelete(selected: List<MediaItem>) {
        if (selected.isEmpty()) return
        val uris = selected.map { it.uri }
        try {
            val intentSender = MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
            deleteResultLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        } catch (e: Exception) {
            Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchBiometricPrompt(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(context, "Auth error: $errString", Toast.LENGTH_SHORT).show()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Private Vault")
            .setSubtitle("Confirm biometric or device lock")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(promptInfo)
    }

    LaunchedEffect(permission) {
        if (permission) {
            allItems = repo.scanAll()
            trashedItems = repo.scanTrashed()
            vaultIds = index.getVaultIds()

            val photosCount = allItems.count { !it.isVideo }
            val videosCount = allItems.count { it.isVideo }
            status = "$photosCount photos, $videosCount videos found"

            // Instant silent restore from SQLite persistent cache
            scope.launch {
                report = analyzer.analyze(allItems, forceRefresh = false)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { embedder.close() }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Image(
                                    painter = painterResource(R.drawable.leo_ai_gallery_icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp).clip(CircleShape)
                                )
                                Text("Leo Ai local gallery", fontWeight = FontWeight.SemiBold)
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                if (isVaultUnlocked) {
                                    showVaultDialog = true
                                } else {
                                    launchBiometricPrompt {
                                        isVaultUnlocked = true
                                        showVaultDialog = true
                                    }
                                }
                            }) {
                                Text(if (isVaultUnlocked) "🔓" else "🔒", fontSize = 18.sp)
                            }
                            TextButton(
                                enabled = !isServiceRunning && permission,
                                onClick = { showIndexOptionsDialog = true }
                            ) {
                                Text("⚡ Index AI")
                            }
                        }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(tab == 0, { tab = 0 }, label = { Text("Search") }, icon = { Text("🔍") })
                        NavigationBarItem(tab == 1, { tab = 1 }, label = { Text("Albums") }, icon = { Text("🖼️") })
                        NavigationBarItem(tab == 2, { tab = 2 }, label = { Text("Cleanup") }, icon = { Text("🧹") })
                        NavigationBarItem(tab == 3, { tab = 3 }, label = { Text("Assistant") }, icon = { Text("✨") })
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 6.dp)) {

                    // Background service live progress banner
                    if (isServiceRunning) {
                        RealTimeProgressCard(
                            title = "✨ Weaving visual intelligence...",
                            statusText = serviceStatus,
                            progress = serviceProgress,
                            onStop = { IndexingForegroundService.stop(context) }
                        )
                        Spacer(Modifier.height(6.dp))
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
                                    status = "✨ Searching with visual AI..."
                                    val qLower = query.lowercase().trim()

                                    val semanticHits = if (embedder.initialize()) {
                                        val qVec = embedder.embedText(query)
                                        if (qVec != null) index.search(qVec, items, limit = 100) else emptyList()
                                    } else emptyList()

                                    val semanticItems = semanticHits.filter { it.score > 0.40 }.map { it.item }
                                    val semanticIds = semanticItems.map { it.id }.toSet()

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
                                scope.launch {
                                    allItems = repo.scanAll()
                                    trashedItems = repo.scanTrashed()
                                    status = "${allItems.size} media items refreshed"
                                }
                            },
                            onMoveToVault = { item ->
                                scope.launch {
                                    index.addToVault(item.id)
                                    vaultIds = index.getVaultIds()
                                    Toast.makeText(context, "Moved to Private Vault", Toast.LENGTH_SHORT).show()
                                }
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
                                    status = "✨ Clustering albums using temporal and visual AI..."
                                    clusters = clusterEngine.clusterMedia(items)
                                    status = "Generated ${clusters.size} smart albums"
                                    busy = false
                                }
                            }
                        )
                    } else if (tab == 2) {
                        CleanupScreen(
                            items = items,
                            trashedItems = trashedItems,
                            report = report,
                            status = status,
                            busy = busy,
                            onAnalyze = {
                                scope.launch {
                                    busy = true
                                    status = "✨ Analyzing gallery clutter and quality..."
                                    report = analyzer.analyze(allItems, forceRefresh = true)
                                    status = "Analysis complete: ${report?.recommendedDeleteIds?.size ?: 0} duplicates flagged"
                                    busy = false
                                }
                            },
                            onTrash = { selected -> handleTrashRequest(selected, true) },
                            onRestore = { selected -> handleTrashRequest(selected, false) },
                            onPermanentDelete = { selected -> handlePermanentDelete(selected) },
                            onMoveToVault = { item ->
                                scope.launch {
                                    index.addToVault(item.id)
                                    vaultIds = index.getVaultIds()
                                    Toast.makeText(context, "Moved to Private Vault", Toast.LENGTH_SHORT).show()
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
                            onDelete = { selected -> handleTrashRequest(selected, true) }
                        )
                    }
                }
            }

            if (showIndexOptionsDialog) {
                val totalPhotos = items.count { !it.isVideo }
                var cachedCount by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) { cachedCount = index.count() }

                AlertDialog(
                    onDismissRequest = { showIndexOptionsDialog = false },
                    title = { Text("⚡ AI Semantic Search Indexing") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Semantic search uses an on-device AI model with GPU acceleration. Background service will keep running even if you close the app.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "✨ Currently indexed: $cachedCount of $totalPhotos photos",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                            Button(
                                onClick = {
                                    showIndexOptionsDialog = false
                                    IndexingForegroundService.start(context, 500)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("⚡ Quick: Recent 500 Photos (~30-60s)")
                            }
                            OutlinedButton(
                                onClick = {
                                    showIndexOptionsDialog = false
                                    IndexingForegroundService.start(context, 2000)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("🚀 Extended: Recent 2,000 Photos (~2 min)")
                            }
                            OutlinedButton(
                                onClick = {
                                    showIndexOptionsDialog = false
                                    IndexingForegroundService.start(context, null)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("📚 Full Library: All $totalPhotos Photos")
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showIndexOptionsDialog = false }) { Text("Cancel") }
                    }
                )
            }

            if (showVaultDialog) {
                PrivateVaultDialog(
                    vaultedItems = vaultedItems,
                    onDismiss = { showVaultDialog = false },
                    onRemoveFromVault = { item ->
                        scope.launch {
                            index.removeFromVault(item.id)
                            vaultIds = index.getVaultIds()
                            Toast.makeText(context, "Restored to Gallery", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onLock = {
                        isVaultUnlocked = false
                        showVaultDialog = false
                        Toast.makeText(context, "Vault Locked", Toast.LENGTH_SHORT).show()
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
private fun WelcomeScreen(onContinue: () -> Unit, onFeedback: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.leo_ai_gallery_icon),
                contentDescription = "Leo Ai local gallery",
                modifier = Modifier.size(170.dp).clip(CircleShape),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(24.dp))
            Text("✨ Hi Folks!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Welcome to Leo Ai local gallery", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            Text(
                "Your memories stay 100% on your device. Search, cluster, and clean your gallery with private local AI.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Enter Gallery")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onFeedback, modifier = Modifier.fillMaxWidth()) {
                Text("Send Feedback")
            }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Photo & Media Access Required", style = MaterialTheme.typography.titleLarge)
            Text("Leo Ai local gallery needs storage permissions to organize, search, and clean your photos locally.")
            Button(onClick = onGrant) { Text("Allow Access") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(
    items: List<MediaItem>,
    query: String,
    onQuery: (String) -> Unit,
    results: List<MediaItem>,
    status: String,
    busy: Boolean,
    onSearch: () -> Unit,
    onRescan: () -> Unit,
    onMoveToVault: (MediaItem) -> Unit
) {
    val gridState = rememberLazyGridState()
    var mediaFilter by remember { mutableStateOf("All") }
    var timeFilter by remember { mutableStateOf("All") }
    var highQualityOnly by remember { mutableStateOf(false) }

    // Collapsible header state: collapses smoothly when user scrolls into the photos
    val isHeaderVisible by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset < 30
        }
    }

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

    Column(Modifier.fillMaxSize()) {
        // Search Input (Always easily accessible)
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                label = { Text("Search photos & media") },
                placeholder = { Text("e.g. sunsets, receipts, car, documents") },
                singleLine = true
            )
            Button(
                enabled = query.isNotBlank() && !busy,
                onClick = onSearch,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Search")
            }
        }

        // Collapsible keywords and filter chips container
        AnimatedVisibility(
            visible = isHeaderVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                val suggestions = listOf("receipts", "family photos", "car", "sunset", "documents", "WhatsApp images", "screenshots")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(suggestions) { suggestion ->
                        AssistChip(
                            onClick = { onQuery(suggestion) },
                            label = { Text(suggestion) },
                            enabled = !busy
                        )
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = mediaFilter == "All",
                            onClick = { mediaFilter = "All" },
                            label = { Text("All (${items.size})") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = mediaFilter == "Photos",
                            onClick = { mediaFilter = "Photos" },
                            label = { Text("Photos ($photosCount)") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = mediaFilter == "Videos",
                            onClick = { mediaFilter = "Videos" },
                            label = { Text("Videos ($videosCount)") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = timeFilter == "30 Days",
                            onClick = { timeFilter = if (timeFilter == "30 Days") "All" else "30 Days" },
                            label = { Text("Past 30d") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = highQualityOnly,
                            onClick = { highQualityOnly = !highQualityOnly },
                            label = { Text("★ High Quality") }
                        )
                    }
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // Full Screen Timeline Photo Grid
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TimelinePhotoGrid(
                items = displayedItems,
                gridState = gridState,
                onMoveToVault = onMoveToVault
            )
        }
    }
}

@Composable
private fun TimelinePhotoGrid(
    items: List<MediaItem>,
    gridState: LazyGridState = rememberLazyGridState(),
    onMoveToVault: (MediaItem) -> Unit
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No photos found", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = if (isLandscape) 6 else 3

    // Group items chronologically by day or month
    val grouped = remember(items) {
        val dateFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val dayFormat = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
        val nowCal = Calendar.getInstance()
        val itemCal = Calendar.getInstance()

        items.groupBy { item ->
            itemCal.timeInMillis = item.dateAdded * 1000L
            if (itemCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)) {
                if (itemCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) {
                    "Today"
                } else if (itemCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR) - 1) {
                    "Yesterday"
                } else {
                    dayFormat.format(itemCal.time)
                }
            } else {
                dateFormat.format(itemCal.time)
            }
        }
    }

    var inspectingItem by remember { mutableStateOf<MediaItem?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        grouped.forEach { (headerText, groupPhotos) ->
            item(span = { GridItemSpan(columns) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp, start = 2.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(headerText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${groupPhotos.size} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(groupPhotos, key = { it.id }) { photo ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .fillMaxWidth()
                        .clickable { inspectingItem = photo }
                ) {
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = photo.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    if (photo.isVideo) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        ) {
                            Text(
                                text = "▶ ${photo.durationFormatted.ifEmpty { "Video" }}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (inspectingItem != null) {
        SinglePhotoDetailDialog(
            item = inspectingItem!!,
            onDismiss = { inspectingItem = null },
            onMoveToVault = {
                onMoveToVault(inspectingItem!!)
                inspectingItem = null
            }
        )
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
            Text(selectedCluster.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "${selectedCluster.category} • ${selectedCluster.dateRangeFormatted}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TimelinePhotoGrid(selectedCluster.items, onMoveToVault = {})
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
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(enabled = !busy && items.isNotEmpty(), onClick = onCluster) {
                    Text(if (clusters.isEmpty()) "Build Smart Albums" else "Re-Cluster")
                }
            }
            if (clusters.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No albums generated yet. Tap 'Build Smart Albums' to cluster your photos using on-device temporal proximity and visual AI.",
                        textAlign = TextAlign.Center,
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
                                        maxLines = 1,
                                        fontWeight = FontWeight.SemiBold
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
    trashedItems: List<MediaItem>,
    report: CleanupReport?,
    status: String,
    busy: Boolean,
    onAnalyze: () -> Unit,
    onTrash: (List<MediaItem>) -> Unit,
    onRestore: (List<MediaItem>) -> Unit,
    onPermanentDelete: (List<MediaItem>) -> Unit,
    onMoveToVault: (MediaItem) -> Unit
) {
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var inspectingItemIndex by remember { mutableStateOf<Int?>(null) }
    var compareDuplicateGroup by remember { mutableStateOf<List<MediaItem>?>(null) }
    var trashRetentionDays by remember { mutableIntStateOf(30) }

    val displayItems = remember(report, trashedItems, selectedCategory) {
        if (selectedCategory == "Recycle Bin") {
            trashedItems
        } else if (report == null) {
            emptyList()
        } else when (selectedCategory) {
            "Duplicates" -> report.recommendedDeleteIds.mapNotNull { id -> items.firstOrNull { it.id == id } }
            "Blurry" -> report.blurry
            "Low Quality" -> report.qualityReport.lowQualityPhotos
            "Burst Shots" -> report.qualityReport.burstGroups.flatten().distinctBy { it.id }
            "Screenshots" -> report.screenshots
            "WA Sent" -> report.whatsAppReport.sentMedia
            "WA Forwarded" -> report.whatsAppReport.likelyForwarded
            "Large Videos" -> report.largeVideos
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

    LaunchedEffect(displayItems, selectedCategory) {
        selectedIds = if (selectedCategory == "Recycle Bin") emptySet() else displayItems.map { it.id }.toSet()
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

            val selectedItems = displayItems.filter { it.id in selectedIds }
            if (selectedCategory == "Recycle Bin") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        enabled = selectedItems.isNotEmpty(),
                        onClick = { onRestore(selectedItems) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("♻️ Restore (${selectedItems.size})", style = MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        enabled = selectedItems.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = { onPermanentDelete(selectedItems) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Empty Permanently", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else if (report != null) {
                Button(
                    enabled = selectedItems.isNotEmpty() && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    onClick = { onTrash(selectedItems) }
                ) {
                    Text("Move to Bin (${selectedItems.size})", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // CATEGORY REEL (Includes Recycle Bin!)
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedCategory == "Recycle Bin",
                    onClick = { selectedCategory = "Recycle Bin" },
                    label = { Text("🗑️ Bin (${trashedItems.size})") }
                )
            }
            if (report != null) {
                val wa = report.whatsAppReport
                val q = report.qualityReport
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
                        selected = selectedCategory == "Large Videos",
                        onClick = { selectedCategory = "Large Videos" },
                        label = { Text("Large Videos (${report.largeVideos.size})") }
                    )
                }
            }
        }

        // Duplicates Side-by-Side Quick Action
        if (selectedCategory == "Duplicates" && report != null && report.exactDuplicates.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Exact Duplicate Groups (${report.exactDuplicates.size})", style = MaterialTheme.typography.labelMedium)
                TextButton(
                    onClick = { compareDuplicateGroup = report.exactDuplicates.firstOrNull() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("🔍 Compare Side-by-Side", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Selection Controls Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val currentSelectedCount = selectedIds.intersect(displayItems.map { it.id }.toSet()).size
            Text(
                "$currentSelectedCount / ${displayItems.size} selected",
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

        // Grid Review Area
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
    }

    // Photo Inspection Dialog
    if (inspectingItemIndex != null && inspectingItemIndex!! in displayItems.indices) {
        val currentInspectItem = displayItems[inspectingItemIndex!!]
        PhotoDetailDialog(
            item = currentInspectItem,
            isSelected = currentInspectItem.id in selectedIds,
            currentIndex = inspectingItemIndex!! + 1,
            totalCount = displayItems.size,
            onToggleSelect = {
                val id = currentInspectItem.id
                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
            },
            onPrevious = { if (inspectingItemIndex!! > 0) inspectingItemIndex = inspectingItemIndex!! - 1 },
            onNext = { if (inspectingItemIndex!! < displayItems.lastIndex) inspectingItemIndex = inspectingItemIndex!! + 1 },
            onDismiss = { inspectingItemIndex = null }
        )
    }

    // Side-by-Side Duplicate Comparison Dialog
    if (compareDuplicateGroup != null && compareDuplicateGroup!!.size >= 2) {
        SideBySideCompareDialog(
            original = compareDuplicateGroup!![0],
            duplicate = compareDuplicateGroup!![1],
            onKeepLeft = {
                onTrash(listOf(compareDuplicateGroup!![1]))
                compareDuplicateGroup = null
            },
            onKeepRight = {
                onTrash(listOf(compareDuplicateGroup!![0]))
                compareDuplicateGroup = null
            },
            onKeepBoth = { compareDuplicateGroup = null },
            onDismiss = { compareDuplicateGroup = null }
        )
    }
}

@Composable
private fun SideBySideCompareDialog(
    original: MediaItem,
    duplicate: MediaItem,
    onKeepLeft: () -> Unit,
    onKeepRight: () -> Unit,
    onKeepBoth: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔍 Duplicate Comparison", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Left candidate
                    Card(Modifier.weight(1f).fillMaxHeight(), elevation = CardDefaults.cardElevation(2.dp)) {
                        Column(Modifier.padding(6.dp)) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                AsyncImage(
                                    model = original.uri,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                                if (original.qualityScore >= duplicate.qualityScore) {
                                    Surface(
                                        color = Color(0xFF10B981),
                                        shape = MaterialTheme.shapes.extraSmall,
                                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                                    ) {
                                        Text("Best Quality", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding( horizontal = 4.dp, vertical = 2.dp))
                                    }
                                }
                            }
                            Text("Score: ${original.qualityScore}/100", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("${original.width} x ${original.height} • ${"%.1f".format(original.sizeMb)} MB", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = onKeepLeft, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Text("Keep This", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // Right candidate
                    Card(Modifier.weight(1f).fillMaxHeight(), elevation = CardDefaults.cardElevation(2.dp)) {
                        Column(Modifier.padding(6.dp)) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                AsyncImage(
                                    model = duplicate.uri,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                                if (duplicate.qualityScore > original.qualityScore) {
                                    Surface(
                                        color = Color(0xFF10B981),
                                        shape = MaterialTheme.shapes.extraSmall,
                                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                                    ) {
                                        Text("Best Quality", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding( horizontal = 4.dp, vertical = 2.dp))
                                    }
                                }
                            }
                            Text("Score: ${duplicate.qualityScore}/100", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("${duplicate.width} x ${duplicate.height} • ${"%.1f".format(duplicate.sizeMb)} MB", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = onKeepRight, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Text("Keep This", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = onKeepBoth) {
                        Text("Keep Both Files")
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivateVaultDialog(
    vaultedItems: List<MediaItem>,
    onDismiss: () -> Unit,
    onRemoveFromVault: (MediaItem) -> Unit,
    onLock: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("🔒 Biometric Private Vault", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${vaultedItems.size} hidden photos secured", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onLock, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("Lock Vault")
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (vaultedItems.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Private vault is empty.\nMove sensitive photos to vault from any photo inspection screen.", textAlign = TextAlign.Center)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(vaultedItems, key = { it.id }) { item ->
                            Box(modifier = Modifier.aspectRatio(1f).fillMaxWidth()) {
                                AsyncImage(
                                    model = item.uri,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                OutlinedButton(
                                    onClick = { onRemoveFromVault(item) },
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Unhide", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun SinglePhotoDetailDialog(
    item: MediaItem,
    onDismiss: () -> Unit,
    onMoveToVault: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text("${"%.1f".format(item.sizeMb)} MB • ${item.width} x ${item.height}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onMoveToVault, modifier = Modifier.weight(1f)) {
                        Text("🔒 Move to Vault")
                    }
                }
            }
        }
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

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = if (isLandscape) 6 else 3

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                    item.isTrashed -> "⏳ ${item.trashedDaysRemaining}d left"
                    item.isPersonalCameraPhoto -> "📷 Camera"
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

                val reasons = mutableListOf<String>()
                if (item.isTrashed) reasons += "In Recycle Bin (${item.trashedDaysRemaining} days remaining)"
                if (item.isPersonalCameraPhoto) reasons += "Genuine personal camera photo"
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

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onToggleSelect,
                        colors = if (isSelected) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                 else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isSelected) "✓ Marked for Cleanup (Tap to Deselect)" else "Select for Cleanup")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(onClick = onPrevious, enabled = currentIndex > 1) { Text("← Previous") }
                        OutlinedButton(onClick = onNext, enabled = currentIndex < totalCount) { Text("Next →") }
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
        }
    }
}
