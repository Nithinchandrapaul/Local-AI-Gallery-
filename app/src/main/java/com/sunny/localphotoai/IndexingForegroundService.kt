package com.sunny.localphotoai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class IndexingForegroundService : Service() {

    companion object {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, IndexingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
        startForeground(NOTIFICATION_ID, initialNotif)

        serviceScope.launch {
            val repo = MediaStoreRepository(applicationContext)
            val embedder = EmbeddingEngine(applicationContext)
            val index = SemanticMediaIndex(applicationContext)

            val ok = embedder.initialize()
            if (!ok) {
                _statusMessage.value = "AI model loading failed: ${embedder.lastError}"
                _isRunning.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            val allMedia = repo.scanAll().filter { !it.isVideo }
            val targetPhotos = if (limit != null) allMedia.take(limit) else allMedia
            val total = targetPhotos.size
            val metadataMap = index.getIndexedMetadataMap()

            var updated = 0
            val batch = mutableListOf<Pair<MediaItem, FloatArray>>()

            for (i in targetPhotos.indices) {
                if (isStopRequested) {
                    _statusMessage.value = "Indexing paused: $i/$total photos processed."
                    break
                }

                val item = targetPhotos[i]
                val existing = metadataMap[item.id]

                if (existing == null || existing.first != item.size || existing.second != item.dateAdded) {
                    val vector = embedder.embedImage(item.uri)
                    if (vector != null) {
                        batch += (item to vector)
                        updated++
                    }
                }

                if (batch.size >= 10 || i == targetPhotos.lastIndex) {
                    index.putBatch(batch)
                    batch.clear()
                }

                if (i % 6 == 0 || i == targetPhotos.lastIndex) {
                    val progressFraction = (i + 1).toFloat() / total
                    _progress.value = progressFraction
                    val progressPct = ((i + 1) * 100) / maxOf(1, total)
                    val statusText = "✨ Connecting memories... ${i + 1}/$total ($updated indexed)"
                    _statusMessage.value = statusText

                    val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notifManager.notify(NOTIFICATION_ID, buildNotification(statusText, progressPct, 100))
                }
            }

            if (batch.isNotEmpty()) {
                index.putBatch(batch)
                batch.clear()
            }

            embedder.close()

            if (!isStopRequested) {
                triggerCompletionAlert(updated, total)
            }

            _isRunning.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
