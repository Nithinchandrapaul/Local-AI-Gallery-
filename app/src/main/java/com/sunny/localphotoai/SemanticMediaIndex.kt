package com.sunny.localphotoai

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class SemanticHit(val item: MediaItem, val score: Double)

class SemanticMediaIndex(context: Context) {
    private val db = Helper(context.applicationContext)
    private val lock = Any()

    suspend fun put(item: MediaItem, embedding: FloatArray) = withContext(Dispatchers.IO) {
        putBatch(listOf(item to embedding))
    }

    suspend fun putBatch(batch: List<Pair<MediaItem, FloatArray>>) = withContext(Dispatchers.IO) {
        if (batch.isEmpty()) return@withContext
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
        withContext(Dispatchers.IO) {
            val map = allItems.associateBy { it.id }
            val hits = mutableListOf<SemanticHit>()
            synchronized(lock) {
                db.readableDatabase.rawQuery("SELECT id, embedding FROM vectors", null).use { c ->
                    val idCol = c.getColumnIndexOrThrow("id")
                    val embCol = c.getColumnIndexOrThrow("embedding")
                    while (c.moveToNext()) {
                        val item = map[c.getLong(idCol)] ?: continue
                        val fb = ByteBuffer.wrap(c.getBlob(embCol)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        val vector = FloatArray(fb.remaining())
                        fb.get(vector)
                        hits += SemanticHit(item, cosine(query, vector))
                    }
                }
            }
            hits.sortedByDescending { it.score }.take(limit)
        }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.readableDatabase.rawQuery("SELECT COUNT(*) FROM vectors", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
        }
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

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "local_photo_ai.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE vectors(id INTEGER PRIMARY KEY, uri TEXT NOT NULL, name TEXT, path TEXT, size INTEGER NOT NULL DEFAULT 0, date_added INTEGER NOT NULL DEFAULT 0, embedding BLOB NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_vectors_date ON vectors(date_added)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE vectors ADD COLUMN size INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vectors ADD COLUMN date_added INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_vectors_date ON vectors(date_added)")
            }
            if (oldVersion < 3) {
                db.execSQL("DROP TABLE IF EXISTS vectors")
                onCreate(db)
            }
        }
    }
}