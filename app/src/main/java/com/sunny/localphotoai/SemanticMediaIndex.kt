package com.sunny.localphotoai

import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

data class SemanticHit(val item: MediaItem, val score: Double)

class SemanticMediaIndex(context: Context) {
    private val db = Helper(context.applicationContext)
    private val lock = Any()

    suspend fun put(item: MediaItem, embedding: FloatArray) = withContext(Dispatchers.IO) {
        val bytes = ByteArray(embedding.size * 4)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(embedding)
        synchronized(lock) {
            db.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO vectors(id, uri, name, path, embedding) VALUES(?,?,?,?,?)",
                arrayOf(item.id, item.uri.toString(), item.name, item.path, bytes)
            )
        }
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
                        val id = c.getLong(idCol)
                        val item = map[id] ?: continue
                        val bytes = c.getBlob(embCol)
                        val fb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
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
                it.moveToFirst(); it.getInt(0)
            }
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        return if (aa == 0.0 || bb == 0.0) 0.0 else dot / (sqrt(aa) * sqrt(bb))
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "local_photo_ai.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE vectors(id INTEGER PRIMARY KEY, uri TEXT NOT NULL, name TEXT, path TEXT, embedding BLOB NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
}
