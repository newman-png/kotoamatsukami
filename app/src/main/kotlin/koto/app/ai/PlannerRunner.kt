package koto.app.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import koto.app.data.PlanStore
import koto.app.data.PlannerLog
import koto.app.data.SpellLog
import koto.app.data.Store
import koto.core.ai.LlmUnavailable
import koto.core.ai.MasterPlanner
import koto.core.ai.PlannerReport
import koto.core.ai.TaskWriter
import koto.core.plan.CompactState
import koto.core.plan.History
import koto.core.plan.MasterPlan
import koto.core.plan.NightClock
import koto.core.plan.PlanContexts
import koto.core.plan.PlanRules
import koto.core.plan.Profile
import koto.core.plan.SetupChecks
import koto.core.plan.merge
import koto.core.time.SpellConfig
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Runs the planning that needs the laptop, on its own thread, while the app's foreground service
 * keeps the process alive. Before there is a plan: the master plan, retried every half hour
 * until the laptop answers. After: each night, the weekly re-plan when a week turns, then the
 * next two days of tasks. If the laptop stays off, the phone carries on with code (see
 * [PlanKeeper]).
 */
object PlannerRunner {
    private const val TAG = "koto.planner"
    private const val WAKE_LOCK_MS = 6 * 3_600_000L
    private const val REPLAN_DRAFTS = 2

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "koto-planner").apply { priority = Thread.MIN_PRIORITY } }
    private val busy = AtomicBoolean(false)
    @Volatile private var lastScheduleLine = ""

    /** True while the planner thread is working. */
    fun isWorking(): Boolean = busy.get()

    /** Starts a run now unless one is going. */
    fun kick(context: Context) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            val lock = app.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "koto:planner")
                ?.apply { setReferenceCounted(false); acquire(WAKE_LOCK_MS) }
            try {
                work(app)
            } catch (e: Exception) {
                failed(app, e)
            } catch (e: LinkageError) {
                // A class that failed to load or initialise must not take the takeovers down with it.
                failed(app, e)
            } finally {
                lock?.let { if (it.isHeld) it.release() }
                busy.set(false)
                schedule(app)
            }
        }
    }

    /** A bug, not a missing laptop: the cause goes to the planner log and, short, to the main screen. */
    private fun failed(context: Context, e: Throwable) {
        val reason = PlannerReport.describe(e)
        PlannerLog.add(context, "error: $reason", e)
        PlanStore(context).updateStatus { it.copy(note = "Planner error: ${PlannerReport.short(reason)}") }
    }

    /** Arms the planner's alarm for its next run, or cancels it when there is nothing to plan. */
    fun schedule(context: Context) {
        val store = PlanStore(context)
        val config = Store(context).config()
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        if (store.profile() == null || store.settings() == null || config == null) {
            alarms.cancel(pi)
            return
        }
        val zone = ZoneId.systemDefault()
        val status = store.status()
        val now = LocalDateTime.now(zone)
        val lastAttempt = status.lastAttemptMs.takeIf { it > 0 }?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
        val next = if (store.plan() == null) {
            lastAttempt?.plusMinutes(NightClock.RETRY_MINUTES)?.takeIf { it.isAfter(now) } ?: now.plusMinutes(1)
        } else {
            NightClock.nextRun(config.windows, now, status.nightDoneFor?.let(LocalDate::parse), lastAttempt)
        }
        val atMs = maxOf(next.atZone(zone).toInstant().toEpochMilli(), System.currentTimeMillis() + 60_000L)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
        Log.i(TAG, "next planner run at ${LocalDateTime.ofInstant(Instant.ofEpochMilli(atMs), zone)}")
        val nextLine = "next try ${LocalDateTime.ofInstant(Instant.ofEpochMilli(atMs), zone).toLocalTime().withNano(0)}"
        if (nextLine != lastScheduleLine) {
            lastScheduleLine = nextLine
            PlannerLog.add(context, nextLine)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    private fun work(context: Context) {
        val store = PlanStore(context)
        val profile = store.profile() ?: return PlannerLog.add(context, "nothing to do: no setup")
        val settings = store.settings() ?: return PlannerLog.add(context, "nothing to do: no laptop address")
        val config = Store(context).config() ?: return PlannerLog.add(context, "nothing to do: no windows")
        store.updateStatus { it.copy(lastAttemptMs = System.currentTimeMillis()) }
        val llm = OllamaClient(settings)
        try {
            val plan = store.plan()
            if (plan == null) firstPlan(context, store, profile, config, llm) else night(context, store, profile, config, plan, llm)
        } catch (e: LlmUnavailable) {
            PlannerLog.add(context, "laptop not reached: ${e.message}" + (e.cause?.let { " (${it.javaClass.simpleName})" } ?: ""))
            store.updateStatus { it.copy(note = PlannerReport.short("Laptop not reached: ${e.message}")) }
        }
    }

    private fun firstPlan(context: Context, store: PlanStore, profile: Profile, config: SpellConfig, llm: OllamaClient) {
        val start = NightClock.nextWakingDate(config.windows, LocalDateTime.now())
        val ctx = PlanContexts.initial(profile, config.windows, start)
        val problems = SetupChecks.problems(ctx)
        if (problems.isNotEmpty()) {
            PlannerLog.add(context, "setup problem: ${problems.first()}")
            store.updateStatus { it.copy(note = problems.first()) }
            return
        }
        PlannerLog.add(context, "first plan: asking ${llm.address()}")
        val state = CompactState.render(profile, null, start, emptyList())
        val r = MasterPlanner(llm).plan(profile, ctx, state)
        report(context, r.report)
        when (r) {
            is MasterPlanner.Result.Ok -> {
                store.savePlan(r.plan)
                PlannerLog.add(context, "plan accepted, starts $start")
                store.updateStatus { it.copy(lastSuccessMs = System.currentTimeMillis(), note = "", nightDoneFor = null) }
                writeBooks(context, store, profile, config, r.plan, start, llm)
            }
            is MasterPlanner.Result.Failed -> {
                PlannerLog.add(context, "no draft passed the checks")
                store.updateStatus { it.copy(note = "No draft passed the checks. Trying again.") }
            }
        }
    }

    private fun night(context: Context, store: PlanStore, profile: Profile, config: SpellConfig, current: MasterPlan, llm: OllamaClient) {
        val date = NightClock.nextWakingDate(config.windows, LocalDateTime.now())
        PlannerLog.add(context, "night work for $date: asking ${llm.address()}")
        var plan = PlanKeeper.ensureWeek(context, store, current, date, config)
        val week = plan.weekIndexOf(date)
        if (week in 2..PlanRules.MAX_WEEKS && store.status().aiReplanWeek < week) {
            val prepared = PlanKeeper.prepareReplan(context, store, profile, plan, week, date, config)
            val state = CompactState.render(profile, plan, date, store.summaries())
            val r = MasterPlanner(llm, MasterPlanner.Settings(drafts = REPLAN_DRAFTS)).plan(profile, prepared.ctx, state, plan.intent, prepared.signals)
            report(context, r.report)
            if (r is MasterPlanner.Result.Ok) {
                plan = plan.merge(r.plan)
                store.savePlan(plan)
                PlannerLog.add(context, "week $week re-planned by the laptop")
            } else {
                PlannerLog.add(context, "week $week: the laptop's re-plan failed the checks; the code re-plan stays")
            }
            // A failed re-plan keeps the code re-plan; it isn't retried all night.
            store.updateStatus { it.copy(aiReplanWeek = week, codeReplanWeek = maxOf(it.codeReplanWeek, week)) }
        }
        writeBooks(context, store, profile, config, plan, date, llm)
    }

    /** Tomorrow's tasks and the day after's: never more than [NightClock.BOOK_DAYS] ahead. */
    private fun writeBooks(context: Context, store: PlanStore, profile: Profile, config: SpellConfig, plan: MasterPlan, first: LocalDate, llm: OllamaClient) {
        val zone = ZoneId.systemDefault()
        val yesterday = first.minusDays(1)
        val lines = History.dayLines(
            SpellLog.records(
                context,
                yesterday.atStartOfDay(zone).toInstant().toEpochMilli(),
                first.atStartOfDay(zone).toInstant().toEpochMilli(),
            ),
        )
        for (i in 0 until NightClock.BOOK_DAYS) {
            val date = first.plusDays(i.toLong())
            if (plan.weekFor(date) == null) continue
            val specs = PlanKeeper.compose(store, plan, date, config)
            val state = CompactState.render(profile, plan, date, store.summaries())
            val notes = ArrayList<String>()
            val book = TaskWriter(llm).write(date, plan.weekIndexOf(date), specs, state, lines, Random.Default, notes)
                .copy(planRevision = plan.revision)
            notes.forEach { PlannerLog.add(context, it) }
            // A day that has already begun keeps the book its takeovers were planned from.
            if (!LocalDateTime.now(zone).isBefore(config.windows.wakingPeriod(date).first)) {
                PlannerLog.add(context, "tasks for $date arrived after the day began; not used")
                continue
            }
            store.saveBook(book)
        }
        store.updateStatus { it.copy(nightDoneFor = first.toString(), lastSuccessMs = System.currentTimeMillis(), note = "") }
    }

    /** Draft outcomes to the planner log (rule names only); the full report to logcat. */
    private fun report(context: Context, lines: List<String>) {
        for (line in lines) {
            Log.i(TAG, line)
            PlannerLog.add(context, PlannerReport.visible(line))
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, PlannerAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The planner's alarm. */
class PlannerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlannerRunner.kick(context)
    }
}
