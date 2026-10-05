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
 */
object SpellScheduler {
    private const val TAG = "koto.schedule"
    private const val DUE_TOLERANCE_MS = 5_000L
    private const val MISSED_AFTER_MS = 30 * 60_000L
    private const val COLLISION_PUSH_MS = 5 * 60_000L
    const val TEST_DELAY_MS = 20_000L

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
            val carried = plan?.slots.orEmpty().filter { it.atMs > nowMs && (it.test || it.taskId != null) }
            plan = DayPlan(wakingDate.toString(), (times.map { Slot(it.atZone(zone).toInstant().toEpochMilli()) } + carried).sortedBy { it.atMs })
            Log.i(TAG, "planned ${times.size} takeovers for $wakingDate")
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
                        val task = slot.taskId?.let(TaskCatalog::byId) ?: TaskCatalog.pick(Random.Default, store.recentTaskIds())
                        fired = Spell.start(context, task)
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
        val next = remaining.sortedBy { it.atMs }
        store.savePlan(plan?.copy(slots = next) ?: next.takeIf { it.isNotEmpty() }?.let { DayPlan("", it) })
        arm(context, next.firstOrNull()?.atMs ?: nextWakingStartMs(config, now, zone))
    }

    /** A task that yielded (call, emergency) comes back later, pinned, at no cost. */
    fun returnLater(context: Context, taskId: String, minutes: Int) {
        addSlot(context, Slot(System.currentTimeMillis() + minutes * 60_000L, taskId = taskId))
    }

    /** The main screen's test: a takeover in [TEST_DELAY_MS], windows ignored, safety not. */
    fun scheduleTest(context: Context) {
        addSlot(context, Slot(System.currentTimeMillis() + TEST_DELAY_MS, test = true))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    private fun addSlot(context: Context, slot: Slot) {
        val store = Store(context)
        val plan = store.plan() ?: DayPlan("", emptyList())
        store.savePlan(plan.copy(slots = (plan.slots + slot).sortedBy { it.atMs }))
        tick(context)
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
                lockActive = Spell.isHolding(),
                deferrals = slot.deferrals,
                ignoreWindows = slot.test,
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
