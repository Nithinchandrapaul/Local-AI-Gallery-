package com.sunny.localphotoai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class SemanticHit(val item: MediaItem, val score: Double)

data class CachedCleanupData(
    val id: Long,
    val size: Long,
    val dateAdded: Long,
    val sha256: String?,
    val dHash: Long?,
    val qualityScore: Int,
    val isBlurry: Boolean,
    val isBadExposure: Boolean,
    val isHeavilyCompressed: Boolean,
    val isLowResolution: Boolean,
    val qualityReason: String?,
    val isPersonalCamera: Boolean
)

class SemanticMediaIndex(context: Context) {
    private val db = Helper(context.applicationContext)
    private val lock = Any()

    companion object {
        // High-speed in-memory vector cache: enables sub-millisecond similarity scans and instant real-time search
        private val liveEmbeddingCache = java.util.concurrent.ConcurrentHashMap<Long, FloatArray>()
        val liveIndexedCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    }

    suspend fun ensureCacheLoaded(): Int = withContext(Dispatchers.IO) {
        if (liveEmbeddingCache.isEmpty()) {
            val map = getAllEmbeddings()
            liveEmbeddingCache.putAll(map)
            liveIndexedCount.value = liveEmbeddingCache.size
        }
        liveEmbeddingCache.size
    }

    fun registerLive(item: MediaItem, embedding: FloatArray) {
        liveEmbeddingCache[item.id] = embedding
        liveIndexedCount.value = liveEmbeddingCache.size
    }

    suspend fun putSingleLive(item: MediaItem, embedding: FloatArray) = withContext(Dispatchers.IO) {
        registerLive(item, embedding)
        put(item, embedding)
    }

    suspend fun put(item: MediaItem, embedding: FloatArray) = withContext(Dispatchers.IO) {
        registerLive(item, embedding)
        putBatch(listOf(item to embedding))
    }

    suspend fun putBatch(batch: List<Pair<MediaItem, FloatArray>>) = withContext(Dispatchers.IO) {
        if (batch.isEmpty()) return@withContext
        for ((item, emb) in batch) {
            liveEmbeddingCache[item.id] = emb
        }
        liveIndexedCount.value = liveEmbeddingCache.size

        synchronized(lock) {
            val database = db.writableDatabase
            database.beginTransaction()
            try {
                val stmt = database.compileStatement(
                    "INSERT OR REPLACE INTO vectors(id, uri, name, path, size, date_added, embedding) VALUES(?,?,?,?,?,?,?)"
                )
                for ((item, embedding) in batch) {
                    val bytes = ByteArray(embedding.size * 4)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(embedding)
                    stmt.clearBindings()
                    stmt.bindLong(1, item.id)
                    stmt.bindString(2, item.uri.toString())
                    stmt.bindString(3, item.name)
                    stmt.bindString(4, item.path)
                    stmt.bindLong(5, item.size)
                    stmt.bindLong(6, item.dateAdded)
                    stmt.bindBlob(7, bytes)
                    stmt.executeInsert()
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    suspend fun getIndexedMetadataMap(): Map<Long, Pair<Long, Long>> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<Long, Pair<Long, Long>>()
        synchronized(lock) {
            db.readableDatabase.rawQuery("SELECT id, size, date_added FROM vectors", null).use { c ->
                val idCol = c.getColumnIndexOrThrow("id")
                val sizeCol = c.getColumnIndexOrThrow("size")
                val dateCol = c.getColumnIndexOrThrow("date_added")
                while (c.moveToNext()) {
                    map[c.getLong(idCol)] = Pair(c.getLong(sizeCol), c.getLong(dateCol))
                }
            }
        }
        map
    }

    suspend fun isCurrent(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.readableDatabase.rawQuery(
                "SELECT size, date_added FROM vectors WHERE id=?",
                arrayOf(item.id.toString())
            ).use { c ->
                c.moveToFirst() && c.getLong(0) == item.size && c.getLong(1) == item.dateAdded
            }
        }
    }

    suspend fun removeMissing(currentIds: Set<Long>) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val database = db.writableDatabase
            database.rawQuery("SELECT id FROM vectors", null).use { c ->
                val stale = mutableListOf<Long>()
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id !in currentIds) stale += id
                }
                stale.forEach { id -> database.delete("vectors", "id=?", arrayOf(id.toString())) }
            }
        }
    }

    suspend fun getAllEmbeddings(): Map<Long, FloatArray> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Long, FloatArray>()
        synchronized(lock) {
            db.readableDatabase.rawQuery("SELECT id, embedding FROM vectors", null).use { c ->
                val idCol = c.getColumnIndexOrThrow("id")
                val embCol = c.getColumnIndexOrThrow("embedding")
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val fb = ByteBuffer.wrap(c.getBlob(embCol)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val vector = FloatArray(fb.remaining())
                    fb.get(vector)
                    result[id] = vector
                }
            }
        }
        result
    }

    suspend fun search(query: FloatArray, allItems: List<MediaItem>, limit: Int = 100): List<SemanticHit> =
        withContext(Dispatchers.Default) {
            ensureCacheLoaded()
            val map = allItems.associateBy { it.id }
            val hits = mutableListOf<SemanticHit>()

            // Microsecond in-memory cosine similarity search over live vectors
            for ((id, vector) in liveEmbeddingCache) {
                val item = map[id] ?: continue
                val score = cosine(query, vector)
                if (score > 0.35) {
                    hits += SemanticHit(item, score)
                }
            }
            hits.sortedByDescending { it.score }.take(limit)
        }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        ensureCacheLoaded()
        liveEmbeddingCache.size
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        return if (aa == 0.0 || bb == 0.0) 0.0 else dot / (sqrt(aa) * sqrt(bb))
    }

    // ==========================================
    // PERSISTENT CLEANUP ANALYSIS CACHE
    // ==========================================

    suspend fun saveCleanupCache(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        synchronized(lock) {
            val database = db.writableDatabase
            database.beginTransaction()
            try {
                val stmt = database.compileStatement(
                    "INSERT OR REPLACE INTO cleanup_cache(id, size, date_added, sha256, d_hash, quality_score, is_blurry, is_bad_exposure, is_heavily_compressed, is_low_res, quality_reason, is_personal_camera) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
                )
                for (item in items) {
                    stmt.clearBindings()
                    stmt.bindLong(1, item.id)
                    stmt.bindLong(2, item.size)
                    stmt.bindLong(3, item.dateAdded)
                    if (item.sha256 != null) stmt.bindString(4, item.sha256) else stmt.bindNull(4)
                    if (item.dHash != null) stmt.bindLong(5, item.dHash) else stmt.bindNull(5)
                    stmt.bindLong(6, item.qualityScore.toLong())
                    stmt.bindLong(7, if (item.isBlurry) 1L else 0L)
                    stmt.bindLong(8, if (item.isBadExposure) 1L else 0L)
                    stmt.bindLong(9, if (item.isHeavilyCompressed) 1L else 0L)
                    stmt.bindLong(10, if (item.isLowResolution) 1L else 0L)
                    if (item.qualityReason != null) stmt.bindString(11, item.qualityReason) else stmt.bindNull(11)
                    stmt.bindLong(12, if (item.isPersonalCameraPhoto) 1L else 0L)
                    stmt.executeInsert()
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    suspend fun loadCleanupCache(): Map<Long, CachedCleanupData> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<Long, CachedCleanupData>()
        synchronized(lock) {
            db.readableDatabase.rawQuery("SELECT id, size, date_added, sha256, d_hash, quality_score, is_blurry, is_bad_exposure, is_heavily_compressed, is_low_res, quality_reason, is_personal_camera FROM cleanup_cache", null).use { c ->
                val idCol = c.getColumnIndexOrThrow("id")
                val sizeCol = c.getColumnIndexOrThrow("size")
                val dateCol = c.getColumnIndexOrThrow("date_added")
                val shaCol = c.getColumnIndexOrThrow("sha256")
                val dHashCol = c.getColumnIndexOrThrow("d_hash")
                val qsCol = c.getColumnIndexOrThrow("quality_score")
                val blurCol = c.getColumnIndexOrThrow("is_blurry")
                val expCol = c.getColumnIndexOrThrow("is_bad_exposure")
                val compCol = c.getColumnIndexOrThrow("is_heavily_compressed")
                val lowCol = c.getColumnIndexOrThrow("is_low_res")
                val qrCol = c.getColumnIndexOrThrow("quality_reason")
                val camCol = c.getColumnIndexOrThrow("is_personal_camera")

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    map[id] = CachedCleanupData(
                        id = id,
                        size = c.getLong(sizeCol),
                        dateAdded = c.getLong(dateCol),
                        sha256 = if (c.isNull(shaCol)) null else c.getString(shaCol),
                        dHash = if (c.isNull(dHashCol)) null else c.getLong(dHashCol),
                        qualityScore = c.getInt(qsCol),
                        isBlurry = c.getInt(blurCol) == 1,
                        isBadExposure = c.getInt(expCol) == 1,
                        isHeavilyCompressed = c.getInt(compCol) == 1,
                        isLowResolution = c.getInt(lowCol) == 1,
                        qualityReason = if (c.isNull(qrCol)) null else c.getString(qrCol),
                        isPersonalCamera = c.getInt(camCol) == 1
                    )
                }
            }
        }
        map
    }

    // ==========================================
    // BIOMETRIC PRIVATE VAULT
    // ==========================================

    suspend fun addToVault(id: Long) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val cv = ContentValues().apply {
                put("id", id)
                put("date_vaulted", System.currentTimeMillis() / 1000)
            }
            db.writableDatabase.insertWithOnConflict("vault_items", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    suspend fun removeFromVault(id: Long) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.writableDatabase.delete("vault_items", "id=?", arrayOf(id.toString()))
        }
    }

    suspend fun getVaultIds(): Set<Long> = withContext(Dispatchers.IO) {
        val set = mutableSetOf<Long>()
        synchronized(lock) {
            db.readableDatabase.rawQuery("SELECT id FROM vault_items", null).use { c ->
                while (c.moveToNext()) {
                    set += c.getLong(0)
                }
            }
        }
        set
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "local_photo_ai.db", null, 4) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE vectors(id INTEGER PRIMARY KEY, uri TEXT NOT NULL, name TEXT, path TEXT, size INTEGER NOT NULL DEFAULT 0, date_added INTEGER NOT NULL DEFAULT 0, embedding BLOB NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_vectors_date ON vectors(date_added)")
            createExtraTables(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Data preservation guarantee: NEVER drop tables or delete user indexes during upgrade
            runCatching {
                db.execSQL("CREATE TABLE IF NOT EXISTS vectors(id INTEGER PRIMARY KEY, uri TEXT NOT NULL, name TEXT, path TEXT, size INTEGER NOT NULL DEFAULT 0, date_added INTEGER NOT NULL DEFAULT 0, embedding BLOB NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_vectors_date ON vectors(date_added)")
            }
            if (oldVersion < 2) {
                runCatching { db.execSQL("ALTER TABLE vectors ADD COLUMN size INTEGER NOT NULL DEFAULT 0") }
                runCatching { db.execSQL("ALTER TABLE vectors ADD COLUMN date_added INTEGER NOT NULL DEFAULT 0") }
                runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_vectors_date ON vectors(date_added)") }
            }
            createExtraTables(db)
        }

        private fun createExtraTables(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS cleanup_cache(
                    id INTEGER PRIMARY KEY,
                    size INTEGER NOT NULL,
                    date_added INTEGER NOT NULL,
                    sha256 TEXT,
                    d_hash INTEGER,
                    quality_score INTEGER NOT NULL,
                    is_blurry INTEGER NOT NULL,
                    is_bad_exposure INTEGER NOT NULL,
                    is_heavily_compressed INTEGER NOT NULL,
                    is_low_res INTEGER NOT NULL,
                    quality_reason TEXT,
                    is_personal_camera INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE TABLE IF NOT EXISTS vault_items(id INTEGER PRIMARY KEY, date_vaulted INTEGER NOT NULL)")
        }
    }
}