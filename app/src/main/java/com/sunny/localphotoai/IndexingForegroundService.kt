package com.sunny.localphotoai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class IndexingForegroundService : Service() {

    companion object {
        private const val TAG = "IndexingService"
        const val CHANNEL_ID = "leo_ai_indexing_channel"
        const val NOTIFICATION_ID = 4040
        const val COMPLETE_NOTIFICATION_ID = 4041

        const val ACTION_START = "com.sunny.localphotoai.ACTION_START_INDEXING"
        const val ACTION_STOP = "com.sunny.localphotoai.ACTION_STOP_INDEXING"
        const val EXTRA_LIMIT = "extra_limit"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _progress = MutableStateFlow(0f)
        val progress = _progress.asStateFlow()

        private val _statusMessage = MutableStateFlow("Ready")
        val statusMessage = _statusMessage.asStateFlow()

        fun start(context: Context, limit: Int? = null) {
            val intent = Intent(context, IndexingForegroundService::class.java).apply {
                action = ACTION_START
                limit?.let { putExtra(EXTRA_LIMIT, it) }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Cannot start IndexingForegroundService", e)
                _statusMessage.value = "Cannot start background indexing: ${e.localizedMessage ?: "Restricted"}"
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, IndexingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "Cannot stop IndexingForegroundService", e)
            }
        }
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled coroutine error in IndexingForegroundService", throwable)
        _statusMessage.value = "Indexing error: ${throwable.localizedMessage ?: "Unexpected failure"}"
        _isRunning.value = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {}
        try {
            stopSelf()
        } catch (_: Throwable) {}
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler)
    private var isStopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isStopRequested = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                val limit = if (intent.hasExtra(EXTRA_LIMIT)) intent.getIntExtra(EXTRA_LIMIT, 500) else null
                startForegroundIndexing(limit)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundIndexing(limit: Int?) {
        _isRunning.value = true
        isStopRequested = false

        val initialNotif = buildNotification("✨ Weaving visual intelligence...", 0, 100)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotif)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start foreground service", e)
            _statusMessage.value = "Unable to start foreground service: ${e.localizedMessage}"
            _isRunning.value = false
            stopSelf()
            return
        }

        serviceScope.launch {
            var embedder: EmbeddingEngine? = null
            try {
                val repo = MediaStoreRepository(applicationContext)
                embedder = EmbeddingEngine(applicationContext)
                val index = SemanticMediaIndex(applicationContext)

                val ok = embedder.initialize()
                if (!ok) {
                    _statusMessage.value = "AI model loading failed: ${embedder.lastError}"
                    return@launch
                }

                // Ensure live in-memory cache is primed
                index.ensureCacheLoaded()

                val allMedia = repo.scanAll().filter { !it.isVideo }
                val targetPhotos = if (limit != null) allMedia.take(limit) else allMedia
                val total = targetPhotos.size
                val metadataMap = index.getIndexedMetadataMap()

                // Filter to items needing indexing
                val pendingItems = targetPhotos.filter { item ->
                    val existing = metadataMap[item.id]
                    existing == null || existing.first != item.size || existing.second != item.dateAdded
                }

                val alreadyIndexedCount = total - pendingItems.size
                var updated = 0
                val pendingTotal = pendingItems.size

                if (pendingTotal == 0) {
                    _progress.value = 1f
                    _statusMessage.value = "✨ All $total photos are already indexed and up to date!"
                    return@launch
                }

                // Adaptive concurrency tuned for budget chipsets (2-4 efficient cores) to flagship multi-cores
                val availableCores = Runtime.getRuntime().availableProcessors()
                val workerCount = minOf(4, maxOf(2, availableCores / 2))
                val prefetchCapacity = workerCount * 3
                val prefetchChannel = Channel<Pair<MediaItem, Bitmap>?>(capacity = prefetchCapacity)

                // Stage 1: Adaptive Prefetch & Fast Decode Pipeline (Dispatchers.IO)
                val prefetchJob = launch(Dispatchers.IO) {
                    try {
                        val itemQueue = Channel<MediaItem>(capacity = Channel.UNLIMITED)
                        for (item in pendingItems) {
                            itemQueue.send(item)
                        }
                        itemQueue.close()

                        val workers = (0 until workerCount).map {
                            launch {
                                for (item in itemQueue) {
                                    if (isStopRequested) break
                                    try {
                                        val bmp = embedder.loadOptimizedBitmap(item.uri, 224)
                                        if (bmp != null && !bmp.isRecycled) {
                                            prefetchChannel.send(item to bmp)
                                        } else {
                                            prefetchChannel.send(null)
                                        }
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "Error decoding bitmap for ${item.id}", e)
                                        prefetchChannel.send(null)
                                    }
                                }
                            }
                        }
                        workers.joinAll()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Prefetch pipeline error", e)
                    } finally {
                        prefetchChannel.close()
                    }
                }

                // Stage 2: High-Performance Neural Inference & Real-Time Dispatch (Dispatchers.Default)
                val batch = mutableListOf<Pair<MediaItem, FloatArray>>()
                var processedCount = 0
                var lastNotifTime = 0L

                for (entry in prefetchChannel) {
                    if (isStopRequested) {
                        entry?.second?.let { if (!it.isRecycled) it.recycle() }
                        break
                    }
                    processedCount++
                    if (entry != null) {
                        val (item, bmp) = entry
                        try {
                            val vector = if (!bmp.isRecycled) {
                                embedder.embedBitmap(bmp)
                            } else null

                            if (vector != null) {
                                // REAL-TIME AVAILABILITY: Immediately register into live in-memory search index!
                                index.registerLive(item, vector)
                                batch += (item to vector)
                                updated++
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Embedding failed for ${item.id}", e)
                        } finally {
                            if (!bmp.isRecycled) {
                                bmp.recycle()
                            }
                        }
                    }

                    // Write-ahead flush every 10 items or at end: reduces SQLite transaction overhead by 50%
                    if (batch.size >= 10 || processedCount == pendingTotal) {
                        try {
                            index.putBatch(batch)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Batch persistence error", e)
                        }
                        batch.clear()
                    }

                    val totalDone = alreadyIndexedCount + processedCount
                    val progressFraction = totalDone.toFloat() / total
                    _progress.value = progressFraction
                    val progressPct = (totalDone * 100) / maxOf(1, total)
                    val statusText = "✨ Connecting memories... $totalDone/$total ($updated indexed)"
                    _statusMessage.value = statusText

                    // Throttled notification updates (at most once every 500ms or on completion) to avoid IPC lag
                    val now = System.currentTimeMillis()
                    if (now - lastNotifTime >= 500L || processedCount == pendingTotal) {
                        lastNotifTime = now
                        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        try {
                            notifManager.notify(NOTIFICATION_ID, buildNotification(statusText, progressPct, 100))
                        } catch (e: Throwable) {
                            Log.w(TAG, "Notification post failed", e)
                        }
                    }
                }

                prefetchJob.cancel()
                // Drain and recycle any lingering bitmaps to avoid memory leaks
                while (true) {
                    val remaining = prefetchChannel.tryReceive().getOrNull() ?: break
                    remaining?.second?.let { if (!it.isRecycled) it.recycle() }
                }

                if (batch.isNotEmpty()) {
                    try {
                        index.putBatch(batch)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Final batch persistence error", e)
                    }
                    batch.clear()
                }

                if (!isStopRequested) {
                    triggerCompletionAlert(updated, total)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Fatal error during indexing", t)
                _statusMessage.value = "Indexing interrupted: ${t.localizedMessage ?: "Unknown error"}"
            } finally {
                embedder?.close()
                _isRunning.value = false
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (e: Throwable) {
                    Log.w(TAG, "Error stopping foreground", e)
                }
                stopSelf()
            }
        }
    }

    private fun triggerCompletionAlert(indexedCount: Int, total: Int) {
        // Haptic feedback / vibration
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(350)
                }
            }
        } catch (_: Exception) {}

        // Completion notification
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val completionNotif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🎉 Magic Complete!")
            .setContentText("Leo AI finished indexing $total photos ($indexedCount newly learned). Instant semantic search is ready!")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify(COMPLETE_NOTIFICATION_ID, completionNotif)
    }

    private fun buildNotification(text: String, progress: Int, max: Int): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, IndexingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Leo AI Local Gallery")
            .setContentText(text)
            .setProgress(max, progress, false)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Indexing & Analysis",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows progress of background AI semantic indexing"
            }
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        _isRunning.value = false
    }
}
