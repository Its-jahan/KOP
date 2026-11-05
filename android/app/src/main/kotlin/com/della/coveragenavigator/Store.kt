// Local persistence: SQLite (transactions, WAL) for sessions, the append-only event log
// and checkpoints; graph snapshots as gzip files written atomically (tmp + fsync + rename).
// Nothing here leaves the device.
package com.della.coveragenavigator

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import della.core.Checkpoint
import della.core.ContractJson
import della.core.GraphSnapshot
import della.core.SessionEvent
import della.core.sha256Hex
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class StoredSession(
    val sessionId: String,
    val contentHash: String,
    val baseJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ended: Boolean,
)

class Store(context: Context) : SQLiteOpenHelper(context.applicationContext, "coverage.db", null, 2) {
    private val context: Context = context.applicationContext
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE graphs (content_hash TEXT PRIMARY KEY, graph_id TEXT NOT NULL, provider_id TEXT NOT NULL, fetched_at TEXT NOT NULL, endpoint TEXT, file TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE sessions (session_id TEXT PRIMARY KEY, content_hash TEXT NOT NULL, base_json TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, ended INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE events (session_id TEXT NOT NULL, seq INTEGER NOT NULL, event_id TEXT NOT NULL UNIQUE, type TEXT NOT NULL, body BLOB NOT NULL, PRIMARY KEY (session_id, seq))")
        db.execSQL("CREATE TABLE checkpoints (session_id TEXT PRIMARY KEY, seq INTEGER NOT NULL, checksum TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        createSettings(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: key/value settings (the active monthly-plan campaign).
        if (oldVersion < 2) createSettings(db)
    }

    private fun createSettings(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")
    }

    private val graphDir: File get() = File(context.filesDir, "graphs").apply { mkdirs() }

    // ---------------- graphs ----------------

    fun saveGraph(g: GraphSnapshot, endpoint: String?) {
        val file = File(graphDir, "${g.contentHash}.json.gz")
        if (!file.exists()) atomicWrite(file, gzip(ContractJson.stringify(ContractJson.encodeGraph(g)).toByteArray(Charsets.UTF_8)))
        val cv = ContentValues().apply {
            put("content_hash", g.contentHash)
            put("graph_id", g.graphId)
            put("provider_id", g.providerId)
            put("fetched_at", g.fetchedAt)
            put("endpoint", endpoint)
            put("file", file.name)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("graphs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun graphEndpoint(contentHash: String): String? =
        readableDatabase.rawQuery("SELECT endpoint FROM graphs WHERE content_hash=?", arrayOf(contentHash)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    /** Loads a stored graph; returns null when missing or unreadable. Caller verifies the hash. */
    fun loadGraph(contentHash: String): GraphSnapshot? {
        val file = File(graphDir, "$contentHash.json.gz")
        if (!file.exists()) return null
        return try {
            ContractJson.decodeGraph(ContractJson.parse(String(gunzip(file.readBytes()), Charsets.UTF_8)))
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- sessions & events ----------------

    fun createSession(sessionId: String, contentHash: String, baseJson: String, events: List<SessionEvent>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            db.insertOrThrow("sessions", null, ContentValues().apply {
                put("session_id", sessionId)
                put("content_hash", contentHash)
                put("base_json", baseJson)
                put("created_at", now)
                put("updated_at", now)
                put("ended", 0)
            })
            insertEvents(db, events)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Atomically appends events (all or none). Duplicate eventIds/seq violate constraints and abort. */
    fun appendEvents(sessionId: String, events: List<SessionEvent>, ended: Boolean = false) {
        if (events.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertEvents(db, events)
            val cv = ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
                if (ended) put("ended", 1)
            }
            db.update("sessions", cv, "session_id=?", arrayOf(sessionId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertEvents(db: SQLiteDatabase, events: List<SessionEvent>) {
        for (e in events) {
            db.insertOrThrow("events", null, ContentValues().apply {
                put("session_id", e.sessionId)
                put("seq", e.seq)
                put("event_id", e.eventId)
                put("type", e.type)
                put("body", gzip(ContractJson.stringify(ContractJson.encodeEvent(e)).toByteArray(Charsets.UTF_8)))
            })
        }
    }

    /** Raw event records in seq order (decoded text; a torn/corrupt blob yields an unparsable record). */
    fun eventRecords(sessionId: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT body FROM events WHERE session_id=? ORDER BY seq", arrayOf(sessionId)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    try {
                        String(gunzip(c.getBlob(0)), Charsets.UTF_8)
                    } catch (e: Exception) {
                        "\u0000corrupt"
                    },
                )
            }
        }
        return out
    }

    fun lastEventRecord(sessionId: String): String? =
        readableDatabase.rawQuery("SELECT body FROM events WHERE session_id=? ORDER BY seq DESC LIMIT 1", arrayOf(sessionId)).use { c ->
            if (c.moveToFirst()) String(gunzip(c.getBlob(0)), Charsets.UTF_8) else null
        }

    /** Removes records after the last valid sequence (torn/corrupt tail found during recovery). */
    fun truncateAfter(sessionId: String, lastValidSeq: Long) {
        writableDatabase.delete("events", "session_id=? AND seq>?", arrayOf(sessionId, lastValidSeq.toString()))
    }

    fun writeCheckpoint(cp: Checkpoint) {
        writableDatabase.insertWithOnConflict("checkpoints", null, ContentValues().apply {
            put("session_id", cp.sessionId)
            put("seq", cp.seq)
            put("checksum", cp.checksum)
            put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun checkpoint(sessionId: String): Checkpoint? =
        readableDatabase.rawQuery("SELECT seq, checksum FROM checkpoints WHERE session_id=?", arrayOf(sessionId)).use { c ->
            if (c.moveToFirst()) Checkpoint(sessionId, c.getLong(0), c.getString(1)) else null
        }

    fun session(sessionId: String): StoredSession? =
        readableDatabase.rawQuery("SELECT session_id, content_hash, base_json, created_at, updated_at, ended FROM sessions WHERE session_id=?", arrayOf(sessionId)).use { c ->
            if (c.moveToFirst()) StoredSession(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getInt(5) != 0) else null
        }

    /** Most recently updated session that has not ended. */
    fun lastOpenSession(): StoredSession? =
        readableDatabase.rawQuery("SELECT session_id, content_hash, base_json, created_at, updated_at, ended FROM sessions WHERE ended=0 ORDER BY updated_at DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) StoredSession(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), false) else null
        }

    /** All sessions (newest first) with their base JSON, for campaign-day lookup. */
    fun allSessions(): List<StoredSession> {
        val out = ArrayList<StoredSession>()
        readableDatabase.rawQuery("SELECT session_id, content_hash, base_json, created_at, updated_at, ended FROM sessions ORDER BY updated_at DESC", null).use { c ->
            while (c.moveToNext()) out.add(StoredSession(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getInt(5) != 0))
        }
        return out
    }

    // ---------------- settings (key/value) ----------------

    fun getSetting(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Stores (or with null removes) a setting in one durable write. */
    fun setSetting(key: String, value: String?) {
        if (value == null) {
            writableDatabase.delete("settings", "key=?", arrayOf(key))
            return
        }
        writableDatabase.insertWithOnConflict("settings", null, ContentValues().apply {
            put("key", key)
            put("value", value)
            put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun sessionCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM sessions", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun markEnded(sessionId: String) {
        writableDatabase.update("sessions", ContentValues().apply { put("ended", 1) }, "session_id=?", arrayOf(sessionId))
    }

    /** Deletes every local session, event, checkpoint, cached graph and the monthly plan. */
    fun deleteAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("events", null, null)
            db.delete("checkpoints", null, null)
            db.delete("sessions", null, null)
            db.delete("graphs", null, null)
            db.delete("settings", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        graphDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        fun checksum(records: List<String>): String = sha256Hex(records.joinToString("\n"))

        fun gzip(bytes: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(bytes) }
            return bos.toByteArray()
        }

        fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

        fun atomicWrite(target: File, bytes: ByteArray) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("rename failed for ${target.name}")
            }
        }

        fun emptyPayload() = JsonObject(emptyMap())
    }
}
