package com.jdenglish.app.translate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistent translation cache (engine, src) -> dst. Plain SQLiteOpenHelper, all I/O on Dispatchers.IO.
 * Never throws: database errors are logged and treated as misses.
 */
class TranslationCache(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    @Volatile private var putsSinceTrim = 0

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try { db.enableWriteAheadLogging() } catch (_: Throwable) {}
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "engine TEXT NOT NULL, src TEXT NOT NULL, dst TEXT NOT NULL, ts INTEGER NOT NULL, " +
                "PRIMARY KEY(engine, src))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_ts ON $TABLE(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = onUpgrade(db, oldVersion, newVersion)

    /** Returns src -> dst for the hits among [srcs]. */
    suspend fun getAll(engine: String, srcs: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (srcs.isEmpty()) return@withContext emptyMap()
        val out = HashMap<String, String>()
        try {
            val db = readableDatabase
            val list = srcs.distinct()
            for (chunk in list.chunked(MAX_SQL_ARGS - 1)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val args = arrayOf(engine) + chunk.toTypedArray()
                db.rawQuery("SELECT src, dst FROM $TABLE WHERE engine = ? AND src IN ($placeholders)", args).use { c ->
                    while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cache read failed", t)
        }
        out
    }

    suspend fun putAll(engine: String, entries: Map<String, String>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                val cv = ContentValues(4)
                for ((src, dst) in entries) {
                    cv.clear()
                    cv.put("engine", engine)
                    cv.put("src", src)
                    cv.put("dst", dst)
                    cv.put("ts", now)
                    db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            putsSinceTrim += entries.size
            if (putsSinceTrim >= TRIM_EVERY) {
                putsSinceTrim = 0
                trim(db)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cache write failed", t)
        }
    }

    private fun trim(db: SQLiteDatabase) {
        try {
            val count = db.compileStatement("SELECT COUNT(*) FROM $TABLE").use { it.simpleQueryForLong() }
            if (count > MAX_ROWS) {
                val excess = count - MAX_ROWS + MAX_ROWS / 10 // free 10% headroom
                db.execSQL(
                    "DELETE FROM $TABLE WHERE rowid IN (SELECT rowid FROM $TABLE ORDER BY ts ASC LIMIT $excess)"
                )
                Log.i(TAG, "trimmed $excess cache rows")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cache trim failed", t)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            writableDatabase.delete(TABLE, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "cache clear failed", t)
        }
        Unit
    }

    suspend fun count(): Long = withContext(Dispatchers.IO) {
        try {
            readableDatabase.compileStatement("SELECT COUNT(*) FROM $TABLE").use { it.simpleQueryForLong() }
        } catch (t: Throwable) { 0L }
    }

    companion object {
        private const val TAG = "JDT.Cache"
        private const val DB_NAME = "translations.db"
        private const val DB_VERSION = 1
        private const val TABLE = "tr"
        const val MAX_ROWS = 50_000L
        private const val TRIM_EVERY = 500
        private const val MAX_SQL_ARGS = 900 // SQLite default limit is 999
    }
}
