package com.mtenglish.app.translate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Persistent translation cache: SQLite table (source PK, english, backend, updated). All database
 * work runs on a dedicated single background thread, so callers never block the main thread and
 * writes are serialized.
 */
class TranslationCache(context: Context) {
    private val helper = Helper(context.applicationContext)
    private val dbDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "mt-translation-db").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dbDispatcher)

    /** Looks up many sources at once. Missing sources are absent from the result. */
    suspend fun getAll(sources: Collection<String>): Map<String, String> {
        if (sources.isEmpty()) return emptyMap()
        return withContext(dbDispatcher) {
            val out = HashMap<String, String>()
            try {
                val db = helper.readableDatabase
                sources.distinct().chunked(MAX_SQL_ARGS).forEach { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    db.rawQuery(
                        "SELECT source, english FROM $TABLE WHERE source IN ($placeholders) AND backend != '$PROVISIONAL_BACKEND'",
                        chunk.toTypedArray(),
                    ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cache read failed", e)
            }
            out
        }
    }

    /** Most recently updated entries, used to warm the in-memory cache at startup. */
    suspend fun loadRecent(limit: Int): Map<String, String> = withContext(dbDispatcher) {
        val out = LinkedHashMap<String, String>()
        try {
            helper.readableDatabase.rawQuery(
                "SELECT source, english FROM $TABLE WHERE backend != '$PROVISIONAL_BACKEND' ORDER BY updated DESC LIMIT ?",
                arrayOf(limit.toString()),
            ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        } catch (e: Exception) {
            Log.w(TAG, "Cache warm-up failed", e)
        }
        out
    }

    /** Stores translations asynchronously (fire-and-forget, off the main thread). */
    fun putAsync(entries: Map<String, String>, backend: String) {
        if (entries.isEmpty()) return
        val copy = HashMap(entries)
        scope.launch {
            try {
                val db = helper.writableDatabase
                val now = System.currentTimeMillis()
                db.beginTransaction()
                try {
                    val cv = ContentValues(4)
                    for ((src, en) in copy) {
                        cv.clear()
                        cv.put("source", src)
                        cv.put("english", en)
                        cv.put("backend", backend)
                        cv.put("updated", now)
                        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cache write failed", e)
            }
        }
    }

    suspend fun clear() = withContext(dbDispatcher) {
        try {
            helper.writableDatabase.delete(TABLE, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Cache clear failed", e)
        }
        Unit
    }

    suspend fun count(): Long = withContext(dbDispatcher) {
        try {
            android.database.DatabaseUtils.queryNumEntries(helper.readableDatabase, TABLE)
        } catch (e: Exception) {
            0L
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (source TEXT PRIMARY KEY, english TEXT NOT NULL, backend TEXT NOT NULL, updated INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX idx_${TABLE}_updated ON $TABLE(updated)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val TAG = "MtCache"
        private const val DB_NAME = "translations.db"
        private const val DB_VERSION = 1
        private const val TABLE = "translations"
        private const val MAX_SQL_ARGS = 500

        /**
         * Rows written by older versions for the ML Kit backend: literal machine translations that must
         * never shadow Claude, so they are ignored on read (the engine no longer persists them).
         */
        private const val PROVISIONAL_BACKEND = MlKitTranslator.ID
    }
}
