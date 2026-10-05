package koto.app.spell

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import koto.app.ai.PlanKeeper
import koto.app.data.PlanStore
import koto.app.data.Store
import koto.app.safety.CallGuard
import koto.app.safety.Driving
import koto.app.safety.Safety
import koto.core.plan.Books
import koto.core.plan.DayComposer
import koto.core.plan.Placement
import koto.core.safety.LockKind
import koto.core.spell.Escalation
import koto.core.spell.Followup
import koto.core.spell.ReactiveDetector
import koto.core.spell.ReactiveRule
import koto.core.spell.Source
import koto.core.spell.SpellTask
import koto.core.spell.TaskCatalog
import koto.core.spell.TaskKind
import koto.core.time.DayPlan
import koto.core.time.DayPlanner
import koto.core.time.FireContext
import koto.core.time.FireDecision
import koto.core.time.FirePolicy
import koto.core.time.Slot
import koto.core.time.SpellConfig
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Owns the hidden day plan and the single exact alarm that drives it. [tick] is idempotent:
 * it plans the current waking period if needed, fires or defers due slots through [FirePolicy],
 * and arms the alarm for whatever comes next. Call it whenever anything changes.
 *
 * Two things fire outside the alarm, both from the guard when a distraction app is opened:
 * a returning task waiting to ambush, and reactive spells.
 */
object SpellScheduler {
    private const val TAG = "koto.schedule"
    private const val DUE_TOLERANCE_MS = 5_000L
    private const val MISSED_AFTER_MS = 30 * 60_000L
    private const val COLLISION_PUSH_MS = 5 * 60_000L
    const val TEST_DELAY_MS = 20_000L

    private var reactive: Pair<ReactiveRule, ReactiveDetector>? = null

    fun tick(context: Context) {
        val store = Store(context)
        val config = store.config()
        if (config == null || !Safety.isArmed(context)) {
            cancel(context)
            return
        }
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)

        var plan = store.plan()
        val wakingDate = config.windows.wakingDateOf(now)
        if (wakingDate != null && plan?.wakingDate != wakingDate.toString()) {
            val fresh = planDay(context, config, wakingDate, now, zone)
            // Returning tasks and tests carry over into the new day.
            val carried = plan?.slots.orEmpty().filter { it.atMs > nowMs && it.source != Source.SCHEDULE }
            plan = DayPlan(wakingDate.toString(), (fresh + carried).sortedBy { it.atMs })
            Log.i(TAG, "planned ${fresh.size} takeovers (${fresh.count { it.siege }} sieges) for $wakingDate")
        }

        val remaining = ArrayList<Slot>()
        var fired = false
        for (slot in plan?.slots.orEmpty().sortedBy { it.atMs }) {
            when {
                slot.atMs > nowMs + DUE_TOLERANCE_MS -> remaining += slot
                nowMs - slot.atMs > MISSED_AFTER_MS -> Log.i(TAG, "dropped missed slot")
                fired -> remaining += slot.copy(atMs = nowMs + COLLISION_PUSH_MS)
                else -> when (val d = decide(context, config, now, slot)) {
                    FireDecision.Fire -> when (fire(context, config, now, slot)) {
                        Fired.STARTED -> fired = true
                        Fired.BUSY -> remaining += slot.copy(atMs = nowMs + COLLISION_PUSH_MS, deferrals = slot.deferrals + 1)
                        Fired.DROPPED -> Unit
                    }
                    is FireDecision.Defer -> {
                        Log.i(TAG, "deferred ${d.minutes} min: ${d.reason}")
                        remaining += slot.copy(atMs = nowMs + d.minutes * 60_000L, deferrals = slot.deferrals + 1)
                    }
                    is FireDecision.Drop -> Log.i(TAG, "dropped: ${d.reason}")
                }
            }
        }
        savePlan(store, plan, remaining)
        arm(context, remaining.minOfOrNull { it.atMs } ?: nextWakingStartMs(config, now, zone))
    }

    /** A skipped, unanswered or interrupted task comes back (see [koto.core.spell.Escalation]). */
    fun returnLater(context: Context, taskId: String, f: Followup.Return) {
        val nowMs = System.currentTimeMillis()
        val delay = if (f.maxDelayMs > f.minDelayMs) Random.Default.nextLong(f.minDelayMs, f.maxDelayMs + 1) else f.minDelayMs
        addSlot(
            context,
            Slot(
                atMs = nowMs + delay,
                taskId = taskId,
                source = Source.RETURN,
                level = f.level,
                ambushAfterMs = f.ambushAfterMs?.let { nowMs + it },
            ),
        )
    }

    /** The main screen's tests: a takeover in [TEST_DELAY_MS], windows ignored, safety not. */
    fun scheduleTest(context: Context, siege: Boolean) {
        val slot = Slot(
            atMs = System.currentTimeMillis() + TEST_DELAY_MS,
            source = Source.TEST,
            taskId = if (siege) TaskCatalog.TEST_SIEGE.id else null,
        )
        addSlot(context, slot)
    }

    /**
     * A distraction app was just opened (from another app). A returning task waiting to ambush
     * fires now; otherwise this open counts towards a reactive spell.
     */
    fun onDistractionOpen(context: Context) {
        if (!Safety.isArmed(context) || Spell.hasLiveTakeover()) return
        val store = Store(context)
        val config = store.config() ?: return
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val plan = store.plan()
        val slots = plan?.slots.orEmpty()

        val ambush = slots.filter { (it.ambushAfterMs ?: Long.MAX_VALUE) <= nowMs }.minByOrNull { it.atMs }
        if (ambush != null) {
            if (decide(context, config, now, ambush) == FireDecision.Fire && fire(context, config, now, ambush) != Fired.BUSY) {
                savePlan(store, plan, slots - ambush)
                tick(context)
            }
            return
        }

        val rule = config.reactive ?: return
        val detector = reactive?.takeIf { it.first == rule }?.second
            ?: ReactiveDetector(rule).also { reactive = rule to it }
        if (!detector.onOpen(nowMs)) return
        val slot = Slot(nowMs, taskId = TaskCatalog.STOP_SCROLLING.id, source = Source.REACTIVE)
        if (decide(context, config, now, slot) == FireDecision.Fire) fire(context, config, now, slot)
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    private enum class Fired { STARTED, BUSY, DROPPED }

    private fun fire(context: Context, config: SpellConfig, now: LocalDateTime, slot: Slot): Fired {
        val task = taskFor(context, config, now, slot)
        if (task == null) {
            Log.i(TAG, "dropped ${slot.taskId}: the siege no longer fits")
            return Fired.DROPPED
        }
        Log.i(TAG, "firing ${task.id} (${slot.source}, level ${slot.level})")
        return if (Spell.start(context, task, slot.source, slot.level)) Fired.STARTED else Fired.BUSY
    }

    /**
     * A pinned task (the day's book or the catalog), else a siege that fits the window and the
     * siege limit, else a pulse. Null when a planned siege no longer fits even cut short.
     */
    private fun taskFor(context: Context, config: SpellConfig, now: LocalDateTime, slot: Slot): SpellTask? {
        slot.taskId?.let { id -> TaskCatalog.byId(id) ?: PlanKeeper.task(context, id) }?.let {
            // Tests ignore the windows, so they aren't cut to them either.
            return if (slot.source == Source.TEST) it else fitted(it, config, now, slot.level)
        }
        if (slot.siege) {
            val limitMinutes = (config.limits.maxMs(LockKind.SIEGE) / 60_000L).toInt() - 1
            val siege = TaskCatalog.SIEGES
                .filter { it.normal.seconds / 60 <= limitMinutes && config.windows.allowsSpan(now, it.normal.seconds / 60 + 2) }
                .randomOrNull(Random.Default)
            if (siege != null) return siege
        }
        return TaskCatalog.pick(Random.Default, Store(context).recentTaskIds())
    }

    /**
     * The day's takeovers: from the plan's book when there is a plan, placed inside the windows
     * as they are now. Before the plan is ready, Layer 2's random pulses, and no sieges while the
     * plan is being prepared: early takeovers are meant to be easy.
     */
    private fun planDay(context: Context, config: SpellConfig, date: LocalDate, now: LocalDateTime, zone: ZoneId): List<Slot> {
        val book = try {
            PlanKeeper.bookFor(context, date, config)
        } catch (e: RuntimeException) {
            Log.e(TAG, "no book for $date; using random pulses", e)
            null
        }
        if (book != null) {
            val times = Placement.place(date, config.windows, Books.siegeMinutes(book), Random.Default, notBefore = now.plusMinutes(2))
            return book.tasks.zip(times).mapNotNull { (task, t) ->
                t?.let { Slot(it.atZone(zone).toInstant().toEpochMilli(), taskId = task.id, siege = task.kind == TaskKind.SIEGE) }
            }
        }
        val times = DayPlanner.plan(
            date = date,
            windows = config.windows,
            count = config.spellsPerDay,
            random = Random.Default,
            notBefore = now.plusMinutes(2),
        )
        val siegeCount = if (PlanStore(context).profile() != null) 0 else config.siegesPerDay
        val sieges = times.indices.shuffled(Random.Default).take(siegeCount).toSet()
        return times.mapIndexed { i, t -> Slot(t.atZone(zone).toInstant().toEpochMilli(), siege = i in sieges) }
    }

    /** A siege as long as still fits before the windows close and inside the lock limit; null if not even five minutes do. */
    private fun fitted(task: SpellTask, config: SpellConfig, now: LocalDateTime, level: Int): SpellTask? {
        if (task.kind != TaskKind.SIEGE) return task
        val limit = PlanKeeper.maxSiegeMinutes(config)
        val minutes = Escalation.version(task, level).seconds / 60
        fun fits(m: Int) = m <= limit && config.windows.allowsSpan(now, m + Placement.SIEGE_MARGIN_MINUTES)
        if (fits(minutes)) return task
        val shorter = (minutes / 5 * 5 downTo DayComposer.MIN_SIEGE step 5).firstOrNull { fits(it) } ?: return null
        return Books.shortened(task, shorter)
    }

    private fun addSlot(context: Context, slot: Slot) {
        val store = Store(context)
        val plan = store.plan() ?: DayPlan("", emptyList())
        store.savePlan(plan.copy(slots = (plan.slots + slot).sortedBy { it.atMs }))
        tick(context)
    }

    private fun savePlan(store: Store, plan: DayPlan?, slots: List<Slot>) {
        val sorted = slots.sortedBy { it.atMs }
        store.savePlan(plan?.copy(slots = sorted) ?: sorted.takeIf { it.isNotEmpty() }?.let { DayPlan("", it) })
    }

    private fun decide(context: Context, config: SpellConfig, now: LocalDateTime, slot: Slot): FireDecision {
        val files = Safety.files(context)
        return FirePolicy.decide(
            FireContext(
                now = now,
                windows = config.windows,
                disabled = files.disabled,
                safeMode = files.safeMode != null,
                inCall = CallGuard.inCall(context),
                driving = Driving.isDriving(context),
                lockActive = Spell.hasLiveTakeover() || (slot.source != Source.TEST && Spell.isCoolingDown()),
                deferrals = slot.deferrals,
                ignoreWindows = slot.source == Source.TEST,
            ),
        )
    }

    private fun nextWakingStartMs(config: SpellConfig, now: LocalDateTime, zone: ZoneId): Long {
        var start = now.toLocalDate().atTime(config.windows.waking.start)
        if (!start.isAfter(now)) start = start.plusDays(1)
        return start.atZone(zone).toInstant().toEpochMilli()
    }

    private fun arm(context: Context, atMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, SpellAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
