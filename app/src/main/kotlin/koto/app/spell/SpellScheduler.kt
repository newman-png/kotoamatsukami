package koto.app.spell

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import koto.app.data.Store
import koto.app.safety.CallGuard
import koto.app.safety.Driving
import koto.app.safety.Safety
import koto.core.safety.LockKind
import koto.core.spell.Followup
import koto.core.spell.ReactiveDetector
import koto.core.spell.ReactiveRule
import koto.core.spell.Source
import koto.core.spell.SpellTask
import koto.core.spell.TaskCatalog
import koto.core.time.DayPlan
import koto.core.time.DayPlanner
import koto.core.time.FireContext
import koto.core.time.FireDecision
import koto.core.time.FirePolicy
import koto.core.time.Slot
import koto.core.time.SpellConfig
import java.time.Instant
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
            val times = DayPlanner.plan(
                date = wakingDate,
                windows = config.windows,
                count = config.spellsPerDay,
                random = Random.Default,
                notBefore = now.plusMinutes(2),
            )
            val sieges = times.indices.shuffled(Random.Default).take(config.siegesPerDay).toSet()
            val fresh = times.mapIndexed { i, t -> Slot(t.atZone(zone).toInstant().toEpochMilli(), siege = i in sieges) }
            // Returning tasks and tests carry over into the new day.
            val carried = plan?.slots.orEmpty().filter { it.atMs > nowMs && it.source != Source.SCHEDULE }
            plan = DayPlan(wakingDate.toString(), (fresh + carried).sortedBy { it.atMs })
            Log.i(TAG, "planned ${times.size} takeovers (${sieges.size} sieges) for $wakingDate")
        }

        val remaining = ArrayList<Slot>()
        var fired = false
        for (slot in plan?.slots.orEmpty().sortedBy { it.atMs }) {
            when {
                slot.atMs > nowMs + DUE_TOLERANCE_MS -> remaining += slot
                nowMs - slot.atMs > MISSED_AFTER_MS -> Log.i(TAG, "dropped missed slot")
                fired -> remaining += slot.copy(atMs = nowMs + COLLISION_PUSH_MS)
                else -> when (val d = decide(context, config, now, slot)) {
                    FireDecision.Fire -> {
                        fired = fire(context, config, now, slot)
                        if (!fired) remaining += slot.copy(atMs = nowMs + COLLISION_PUSH_MS, deferrals = slot.deferrals + 1)
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
            if (decide(context, config, now, ambush) == FireDecision.Fire && fire(context, config, now, ambush)) {
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

    private fun fire(context: Context, config: SpellConfig, now: LocalDateTime, slot: Slot): Boolean {
        val task = taskFor(context, config, now, slot)
        Log.i(TAG, "firing ${task.id} (${slot.source}, level ${slot.level})")
        return Spell.start(context, task, slot.source, slot.level)
    }

    /** A pinned task, else a siege that fits the window and the siege limit, else a pulse. */
    private fun taskFor(context: Context, config: SpellConfig, now: LocalDateTime, slot: Slot): SpellTask {
        slot.taskId?.let(TaskCatalog::byId)?.let { return it }
        if (slot.siege) {
            val limitMinutes = (config.limits.maxMs(LockKind.SIEGE) / 60_000L).toInt() - 1
            val siege = TaskCatalog.SIEGES
                .filter { it.normal.seconds / 60 <= limitMinutes && config.windows.allowsSpan(now, it.normal.seconds / 60 + 2) }
                .randomOrNull(Random.Default)
            if (siege != null) return siege
        }
        return TaskCatalog.pick(Random.Default, Store(context).recentTaskIds())
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
