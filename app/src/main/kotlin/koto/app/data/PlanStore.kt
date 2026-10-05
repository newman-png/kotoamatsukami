package koto.app.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import koto.core.ai.PlannerSettings
import koto.core.ai.PlannerStatus
import koto.core.plan.MasterPlan
import koto.core.plan.Profile
import koto.core.plan.TaskBook
import koto.core.plan.WeekSummary
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * The planner's files: profile, master plan, the day books, week summaries, the laptop's address
 * and the planner's status. JSON in app-private storage, each file replaced atomically. Read on
 * the main thread, written mostly from the planner thread.
 */
class PlanStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "planner").apply { mkdirs() }

    fun profile(): Profile? = read(PROFILE, Profile.serializer())
    fun saveProfile(p: Profile) = write(PROFILE, Profile.serializer(), p)

    fun plan(): MasterPlan? = synchronized(LOCK) {
        val f = File(dir, PLAN)
        if (!f.exists()) return null
        if (f.lastModified() != cachedPlanStamp || cachedPlan == null) {
            cachedPlan = read(PLAN, MasterPlan.serializer())
            cachedPlanStamp = f.lastModified()
        }
        cachedPlan
    }

    fun savePlan(p: MasterPlan) = synchronized(LOCK) {
        write(PLAN, MasterPlan.serializer(), p)
        cachedPlan = p
        cachedPlanStamp = File(dir, PLAN).lastModified()
    }

    fun book(date: LocalDate): TaskBook? = read(bookName(date), TaskBook.serializer())

    /** Saves a day's book and drops books older than a few days. */
    fun saveBook(b: TaskBook) {
        write(bookName(LocalDate.parse(b.date)), TaskBook.serializer(), b)
        val oldest = LocalDate.parse(b.date).minusDays(KEEP_BOOK_DAYS)
        dir.listFiles()?.forEach { f ->
            val date = f.name.removePrefix("book-").removeSuffix(".json")
            if (f.name.startsWith("book-") && runCatching { LocalDate.parse(date) }.getOrNull()?.isBefore(oldest) == true) f.delete()
        }
    }

    fun summaries(): List<WeekSummary> = read(SUMMARIES, ListSerializer(WeekSummary.serializer())).orEmpty()

    /** Keeps one summary per week, newest last. */
    fun saveSummary(s: WeekSummary) {
        val list = (summaries().filter { it.week != s.week } + s).sortedBy { it.week }
        write(SUMMARIES, ListSerializer(WeekSummary.serializer()), list)
    }

    fun settings(): PlannerSettings? = read(SETTINGS, PlannerSettings.serializer())
    fun saveSettings(s: PlannerSettings) = write(SETTINGS, PlannerSettings.serializer(), s)

    fun status(): PlannerStatus = read(STATUS, PlannerStatus.serializer()) ?: PlannerStatus()

    fun updateStatus(change: (PlannerStatus) -> PlannerStatus) = synchronized(LOCK) {
        write(STATUS, PlannerStatus.serializer(), change(status()))
    }

    /** A new setup: the old plan, books, summaries and status go. Profile and settings stay. */
    fun clearPlan() = synchronized(LOCK) {
        dir.listFiles()?.filter { it.name != PROFILE && it.name != SETTINGS }?.forEach { it.delete() }
        cachedPlan = null
        cachedPlanStamp = -1
    }

    private fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return try {
            json.decodeFromString(serializer, AtomicFile(f).readFully().toString(Charsets.UTF_8))
        } catch (e: IOException) {
            Log.e(TAG, "cannot read $name", e)
            null
        } catch (e: SerializationException) {
            Log.e(TAG, "cannot parse $name", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "cannot parse $name", e)
            null
        }
    }

    private fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        val file = AtomicFile(File(dir, name))
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            Log.e(TAG, "cannot write $name", e)
            return
        }
        try {
            out.write(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            Log.e(TAG, "cannot write $name", e)
        }
    }

    private fun bookName(date: LocalDate) = "book-$date.json"

    private companion object {
        const val TAG = "koto.planstore"
        const val PROFILE = "profile.json"
        const val PLAN = "plan.json"
        const val SUMMARIES = "summaries.json"
        const val SETTINGS = "planner.json"
        const val STATUS = "status.json"
        const val KEEP_BOOK_DAYS = 3L

        val LOCK = Any()
        var cachedPlan: MasterPlan? = null
        var cachedPlanStamp = -1L
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
