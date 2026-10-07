package com.sunny.localphotoai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long = System.currentTimeMillis() + (0..1000).random(),
    val isUser: Boolean,
    val text: String,
    val action: String? = null,
    val items: List<MediaItem> = emptyList()
)

@Composable
fun AssistantScreen(
    items: List<MediaItem>,
    report: CleanupReport?,
    analyzer: PhotoAnalyzer,
    embedder: EmbeddingEngine,
    index: SemanticMediaIndex,
    clusterEngine: AlbumClusterEngine? = null,
    onDelete: (List<MediaItem>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val initialMessage = remember {
        ChatMessage(
            isUser = false,
            text = "Hi! I am your 100% on-device AI Gallery Assistant.\n\n" +
                   "I can understand your photos locally, cluster them into smart albums, calculate recoverable storage, find duplicates, and inspect media without uploading anything to the cloud.",
            action = "Welcome"
        )
    }

    val messages = remember { mutableStateListOf(initialMessage) }

    val suggestions = listOf(
        "Show smart albums",
        "How much storage can I recover?",
        "Find exact duplicates",
        "Clean up clutter",
        "Show all videos",
        "Find large videos >50MB",
        "Inspect WhatsApp media",
        "Find blurry photos",
        "Show screenshots",
        "Find large files >10MB",
        "What can you do?"
    )

    fun sendCommand(cmd: String) {
        val prompt = cmd.trim()
        if (prompt.isBlank() || busy) return

        messages.add(ChatMessage(isUser = true, text = prompt))
        input = ""
        busy = true

        scope.launch {
            val outcome = LocalGalleryAssistant.execute(
                command = prompt,
                items = items,
                existingReport = report,
                analyzer = analyzer,
                embedder = embedder,
                index = index,
                clusterEngine = clusterEngine
            )
            messages.add(
                ChatMessage(
                    isUser = false,
                    text = outcome.result.message,
                    action = outcome.result.action,
                    items = outcome.items
                )
            )
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "Local AI Gallery Assistant",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Suggestion chips
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(suggestions) { s ->
                SuggestionChip(
                    onClick = { sendCommand(s) },
                    label = { Text(s, style = MaterialTheme.typography.labelMedium) },
                    enabled = !busy
                )
            }
        }

        Divider()

        // Conversation History
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(msg, onDelete)
            }
            if (busy) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Analyzing locally...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Divider()

        // Input Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask your local gallery...") },
                singleLine = true,
                enabled = !busy
            )
            Button(
                onClick = { sendCommand(input) },
                enabled = !busy && input.isNotBlank()
            ) {
                Text("Ask")
            }
        }
    }
}

@Composable
private fun ChatBubble(
    msg: ChatMessage,
    onDelete: (List<MediaItem>) -> Unit
) {
    val isUser = msg.isUser
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            contentColor = contentColor,
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                if (msg.action != null && !isUser) {
                    Text(
                        text = "• ${msg.action}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (msg.items.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${msg.items.size} Candidate Photos:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))

                    // Small photo gallery preview
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(75.dp),
                        modifier = Modifier.heightIn(max = 200.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(msg.items, key = { it.id }) { item ->
                            val isChecked = item.id in selectedIds
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (isChecked) selectedIds - item.id else selectedIds + item.id
                                    }
                            ) {
                                AsyncImage(
                                    model = item.uri,
                                    contentDescription = item.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                                    },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(1.dp)
                                )
                                if (item.isVideo) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                        shape = MaterialTheme.shapes.extraSmall,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp)
                                    ) {
                                        Text(
                                            text = "▶ ${item.durationFormatted.ifEmpty { "Video" }}",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            selectedIds = if (selectedIds.size == msg.items.size) emptySet() else msg.items.map { it.id }.toSet()
                        }) {
                            Text(if (selectedIds.size == msg.items.size) "Deselect All" else "Select All")
                        }

                        if (selectedIds.isNotEmpty()) {
                            Button(
                                onClick = {
                                    val targets = msg.items.filter { it.id in selectedIds }
                                    onDelete(targets)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Delete (${selectedIds.size})")
                            }
                        }
                    }
                }
            }
        }
    }
}
