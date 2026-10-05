package koto.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.util.Log
import koto.core.plan.SpellRecord
import koto.core.spell.Domain
import koto.core.spell.Feedback
import koto.core.spell.Outcome
import koto.core.spell.Source
import koto.core.spell.SpellTask
import koto.core.spell.TaskKind
import java.util.concurrent.Executors

/** Local database. Nothing in it ever leaves the phone. */
class KotoDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "koto.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE spells (
                id TEXT PRIMARY KEY,
                task TEXT NOT NULL,
                kind TEXT NOT NULL,
                domain TEXT NOT NULL,
                floor INTEGER NOT NULL,
                level INTEGER NOT NULL,
                source TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                latency_ms INTEGER,
                outcome TEXT,
                active_ms INTEGER,
                mark INTEGER NOT NULL DEFAULT 0,
                feedback TEXT,
                ended_at INTEGER,
                topic TEXT NOT NULL DEFAULT '',
                planned_s INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX spells_started ON spells(started_at)")
        db.execSQL("CREATE TABLE opens (at INTEGER NOT NULL, pkg TEXT NOT NULL)")
        db.execSQL("CREATE INDEX opens_at ON opens(at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Layer 3: the goal topic and the planned length, for the planner's statistics.
            db.execSQL("ALTER TABLE spells ADD COLUMN topic TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE spells ADD COLUMN planned_s INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/** One logged takeover, as read back for the log screen (and later the weekly report). */
data class SpellRow(
    val id: String,
    val task: String,
    val kind: String,
    val floor: Boolean,
    val level: Int,
    val source: String,
    val startedAt: Long,
    val latencyMs: Long?,
    val outcome: String?,
    val activeMs: Long?,
    val mark: Boolean,
    val feedback: Feedback?,
    val domain: String = "",
    val topic: String = "",
)

/**
 * Records every takeover: start, outcome, latency, how long the task ran, skip marks and the
 * one-tap feedback, plus every open of a distraction app. Writes happen on one background thread
 * in order; a failed write is logged and never disturbs a takeover.
 */
object SpellLog {
    private const val TAG = "koto.log"
    private const val KEEP_OPENS_MS = 60L * 24 * 60 * 60 * 1000

    private val io = Executors.newSingleThreadExecutor { Thread(it, "koto-log") }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var helper: KotoDb? = null

    private fun db(context: Context): SQLiteDatabase =
        (helper ?: synchronized(this) { helper ?: KotoDb(context).also { helper = it } }).writableDatabase

    private fun write(context: Context, what: String, block: (SQLiteDatabase) -> Unit) {
        val app = context.applicationContext
        io.execute {
            try {
                block(db(app))
            } catch (e: RuntimeException) {
                Log.e(TAG, "log write failed: $what", e)
            }
        }
    }

    fun started(context: Context, id: String, task: SpellTask, floor: Boolean, level: Int, source: Source, plannedSeconds: Int, atMs: Long) =
        write(context, "start") {
            it.insert("spells", null, ContentValues().apply {
                put("id", id)
                put("task", task.id)
                put("kind", task.kind.name.lowercase())
                put("domain", task.domain.name.lowercase())
                put("floor", if (floor) 1 else 0)
                put("level", level)
                put("source", source.name.lowercase())
                put("started_at", atMs)
                put("topic", task.topic)
                put("planned_s", plannedSeconds)
            })
        }

    fun ended(context: Context, id: String, outcome: Outcome, latencyMs: Long?, activeMs: Long, mark: Boolean, atMs: Long) =
        write(context, "end") {
            it.update("spells", ContentValues().apply {
                put("outcome", outcome.name.lowercase())
                if (latencyMs != null) put("latency_ms", latencyMs)
                put("active_ms", activeMs)
                put("mark", if (mark) 1 else 0)
                put("ended_at", atMs)
            }, "id = ?", arrayOf(id))
        }

    fun feedback(context: Context, id: String, value: Feedback) = write(context, "feedback") {
        it.update("spells", ContentValues().apply { put("feedback", value.code) }, "id = ?", arrayOf(id))
    }

    fun open(context: Context, pkg: String, atMs: Long) = write(context, "open") {
        it.insert("opens", null, ContentValues().apply {
            put("at", atMs)
            put("pkg", pkg)
        })
        it.delete("opens", "at < ?", arrayOf((atMs - KEEP_OPENS_MS).toString()))
    }

    /**
     * Every takeover started in [fromMs, toMs), as the planner reads them. Blocking: planner
     * thread, or small ranges only.
     */
    fun records(context: Context, fromMs: Long, toMs: Long): List<SpellRecord> {
        val out = ArrayList<SpellRecord>()
        try {
            db(context).rawQuery(
                "SELECT started_at, kind, domain, topic, source, floor, planned_s, outcome, active_ms, latency_ms, feedback " +
                    "FROM spells WHERE started_at >= ? AND started_at < ? ORDER BY started_at",
                arrayOf(fromMs.toString(), toMs.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    out += SpellRecord(
                        startedAtMs = c.getLong(0),
                        kind = enumOr(c.getString(1), TaskKind.PULSE),
                        domain = enumOr(c.getString(2), Domain.FOCUS),
                        topic = c.getString(3).orEmpty(),
                        source = enumOr(c.getString(4), Source.SCHEDULE),
                        floor = c.getInt(5) == 1,
                        plannedSeconds = c.getInt(6),
                        outcome = c.getString(7)?.let { o -> Outcome.entries.firstOrNull { it.name.equals(o, ignoreCase = true) } },
                        activeMs = if (c.isNull(8)) 0 else c.getLong(8),
                        latencyMs = if (c.isNull(9)) null else c.getLong(9),
                        feedback = Feedback.fromCode(c.getString(10)),
                    )
                }
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "record read failed", e)
        }
        return out
    }

    /** Distraction-app opens in [fromMs, toMs). Blocking, like [records]. */
    fun opens(context: Context, fromMs: Long, toMs: Long): List<Long> {
        val out = ArrayList<Long>()
        try {
            db(context).rawQuery("SELECT at FROM opens WHERE at >= ? AND at < ? ORDER BY at", arrayOf(fromMs.toString(), toMs.toString())).use { c ->
                while (c.moveToNext()) out += c.getLong(0)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "open read failed", e)
        }
        return out
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback

    /** Reads on the log thread (after any pending writes) and answers on the main thread. */
    fun recent(context: Context, limit: Int, sinceMs: Long, answer: (List<SpellRow>, Int) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val rows = ArrayList<SpellRow>()
            var opens = 0
            try {
                val db = db(app)
                db.rawQuery(
                    "SELECT id, task, kind, floor, level, source, started_at, latency_ms, outcome, active_ms, mark, feedback, domain, topic " +
                        "FROM spells ORDER BY started_at DESC LIMIT ?",
                    arrayOf(limit.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        rows += SpellRow(
                            id = c.getString(0),
                            task = c.getString(1),
                            kind = c.getString(2),
                            floor = c.getInt(3) == 1,
                            level = c.getInt(4),
                            source = c.getString(5),
                            startedAt = c.getLong(6),
                            latencyMs = if (c.isNull(7)) null else c.getLong(7),
                            outcome = c.getString(8),
                            activeMs = if (c.isNull(9)) null else c.getLong(9),
                            mark = c.getInt(10) == 1,
                            feedback = Feedback.fromCode(c.getString(11)),
                            domain = c.getString(12).orEmpty(),
                            topic = c.getString(13).orEmpty(),
                        )
                    }
                }
                db.rawQuery("SELECT COUNT(*) FROM opens WHERE at >= ?", arrayOf(sinceMs.toString())).use { c ->
                    if (c.moveToFirst()) opens = c.getInt(0)
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "log read failed", e)
            }
            main.post { answer(rows, opens) }
        }
    }
}
