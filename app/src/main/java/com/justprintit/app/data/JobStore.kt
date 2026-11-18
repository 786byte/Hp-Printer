package com.justprintit.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.justprintit.core.stats.PrintRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class JobStatus(val label: String) {
    RUNNING("Printing"),
    WAITING_FLIP("Waiting for you to flip the paper"),
    DONE("Printed"),
    FAILED("Failed"),
    CANCELED("Cancelled"),
    INTERRUPTED("Interrupted"),
}

data class JobRecord(
    val id: Long,
    val created: Long,
    val finished: Long?,
    val kind: String,
    val name: String,
    val status: JobStatus,
    val colorPages: Int,
    val monoPages: Int,
    val totalSheets: Int,
    val error: String?,
    /** Private copy of the document, kept so a failed job can be retried from history. */
    val files: List<String>,
    val mime: String,
    val options: String,
) {
    val pages get() = colorPages + monoPages
    val canRetry get() = status in setOf(JobStatus.FAILED, JobStatus.CANCELED, JobStatus.INTERRUPTED) &&
        files.isNotEmpty() && files.all { File(it).exists() }
}

/** Job history + page counters, in a small SQLite database. */
class JobStore(context: Context) : SQLiteOpenHelper(context, "jobs.db", null, 1) {
    private val _changes = MutableStateFlow(0)
    /** Bumps on every write so screens can refresh. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE jobs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created INTEGER NOT NULL,
                finished INTEGER,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                status TEXT NOT NULL,
                color_pages INTEGER NOT NULL DEFAULT 0,
                mono_pages INTEGER NOT NULL DEFAULT 0,
                total_sheets INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                files TEXT NOT NULL DEFAULT '',
                mime TEXT NOT NULL DEFAULT '',
                options TEXT NOT NULL DEFAULT '{}'
            )""",
        )
        db.execSQL("CREATE INDEX jobs_created ON jobs(created)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** A process killed mid-job leaves a RUNNING row behind; mark it so it can be retried by hand. */
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            db.execSQL("UPDATE jobs SET status='INTERRUPTED', error='The app was closed while printing' WHERE status IN ('RUNNING','WAITING_FLIP') AND created < ?",
                arrayOf<Any>(startedAt))
        }
    }

    private val startedAt = System.currentTimeMillis()

    fun insert(kind: String, name: String, totalSheets: Int, files: List<String>, mime: String, options: String): Long {
        val v = ContentValues().apply {
            put("created", System.currentTimeMillis()); put("kind", kind); put("name", name)
            put("status", JobStatus.RUNNING.name); put("total_sheets", totalSheets)
            put("files", files.joinToString("\n")); put("mime", mime); put("options", options)
        }
        return writableDatabase.insert("jobs", null, v).also { _changes.value++ }
    }

    fun addPages(id: Long, color: Int, mono: Int) {
        if (color == 0 && mono == 0) return
        writableDatabase.execSQL("UPDATE jobs SET color_pages = color_pages + ?, mono_pages = mono_pages + ? WHERE id = ?",
            arrayOf<Any>(color, mono, id))
        _changes.value++
    }

    fun setStatus(id: Long, status: JobStatus, error: String? = null) {
        val v = ContentValues().apply {
            put("status", status.name); put("error", error)
            if (status != JobStatus.RUNNING && status != JobStatus.WAITING_FLIP) put("finished", System.currentTimeMillis())
        }
        writableDatabase.update("jobs", v, "id = ?", arrayOf(id.toString()))
        _changes.value++
    }

    fun get(id: Long): JobRecord? = query("id = ?", arrayOf(id.toString()), 1).firstOrNull()

    fun recent(limit: Int = 100): List<JobRecord> = query(null, null, limit)

    fun printRecords(): List<PrintRecord> {
        val out = ArrayList<PrintRecord>()
        readableDatabase.rawQuery(
            "SELECT COALESCE(finished, created), color_pages, mono_pages FROM jobs WHERE color_pages + mono_pages > 0", null,
        ).use { c -> while (c.moveToNext()) out += PrintRecord(c.getLong(0), c.getInt(1), c.getInt(2)) }
        return out
    }

    fun clearHistory() {
        writableDatabase.delete("jobs", null, null)
        _changes.value++
    }

    /** Successful jobs don't need their document copy; failed ones keep it for a week for retrying. */
    fun cleanupOldFiles(filesDir: File) {
        val dir = File(filesDir, "jobs")
        val keep = HashSet<String>()
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        recent(500).filter { it.canRetry && it.created > weekAgo || it.status == JobStatus.RUNNING || it.status == JobStatus.WAITING_FLIP }
            .forEach { keep += it.files }
        dir.listFiles()?.forEach { f ->
            if (f.absolutePath !in keep && f.lastModified() < System.currentTimeMillis() - 3600_000) f.delete()
        }
    }

    private fun query(where: String?, args: Array<String>?, limit: Int): List<JobRecord> {
        val out = ArrayList<JobRecord>()
        readableDatabase.query("jobs", null, where, args, null, null, "created DESC", limit.toString()).use { c ->
            while (c.moveToNext()) {
                fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n))
                fun i(n: String) = c.getInt(c.getColumnIndexOrThrow(n))
                val finishedIdx = c.getColumnIndexOrThrow("finished")
                out += JobRecord(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    created = c.getLong(c.getColumnIndexOrThrow("created")),
                    finished = if (c.isNull(finishedIdx)) null else c.getLong(finishedIdx),
                    kind = s("kind"), name = s("name"),
                    status = runCatching { JobStatus.valueOf(s("status")) }.getOrDefault(JobStatus.FAILED),
                    colorPages = i("color_pages"), monoPages = i("mono_pages"), totalSheets = i("total_sheets"),
                    error = s("error"), files = s("files").split('\n').filter { it.isNotBlank() },
                    mime = s("mime"), options = s("options"),
                )
            }
        }
        return out
    }
}
