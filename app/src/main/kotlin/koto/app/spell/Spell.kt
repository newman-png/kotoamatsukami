package koto.app.spell

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import koto.app.data.Store
import koto.app.notify.Notices
import koto.app.safety.CallGuard
import koto.app.safety.Safety
import koto.app.safety.Watchdog
import koto.core.art.Variation
import koto.core.safety.LockKind
import koto.core.safety.LockLease
import koto.core.safety.LockLimits
import koto.core.spell.Outcome
import koto.core.spell.SpellTask
import koto.core.spell.Takeover
import java.util.UUID
import kotlin.random.Random

/**
 * The one live takeover in this process. Owns its lease, cue and timers. Main thread only.
 *
 * Every exit path goes through [finish], which releases the lease first. While the lease is
 * active the heartbeat keeps the watchdog quiet; if this thread stops, the watchdog kills the
 * process and the screen is released anyway.
 */
object Spell {
    private const val TAG = "koto.spell"
    private const val TICK_MS = 250L
    private const val CALL_CHECK_MS = 500L
    private const val HEARTBEAT_MS = 5_000L
    private const val BOUNCE_MIN_INTERVAL_MS = 1_200L
    private const val YIELD_RETURN_MINUTES = 10

    fun interface Listener {
        fun onSpellChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var app: Context? = null

    var takeover: Takeover? = null
        private set
    var variation: Variation = Variation.random(Random.Default)
        private set
    private var lease: LockLease? = null
    private var cue: CueEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastCallCheck = 0L
    private var lastHeartbeat = 0L
    private var lastBounce = 0L

    /** True while the user is being held: a live takeover under an active lease. */
    fun isHolding(): Boolean {
        val t = takeover ?: return false
        val l = lease ?: return false
        return !t.ended && l.isActive(System.currentTimeMillis(), SystemClock.elapsedRealtime())
    }

    /** A takeover that the screen should show, including its release animation. */
    fun hasLiveTakeover(): Boolean = takeover?.ended == false

    fun start(context: Context, task: SpellTask): Boolean {
        if (takeover?.ended == false) return false
        val ctx = context.applicationContext
        app = ctx
        val files = Safety.files(ctx)
        if (files.disabled || files.safeMode != null) return false

        val limits = Store(ctx).config()?.limits ?: LockLimits()
        val nowElapsed = SystemClock.elapsedRealtime()
        val l = LockLease.grant(
            id = UUID.randomUUID().toString(),
            kind = LockKind.PULSE,
            requestedMs = limits.maxMs(LockKind.PULSE),
            limits = limits,
            nowWallMs = System.currentTimeMillis(),
            nowElapsedMs = nowElapsed,
        )
        try {
            files.writeLease(l)
        } catch (e: java.io.IOException) {
            Log.e(TAG, "cannot write lease; not taking over", e)
            return false
        }
        files.writeHeartbeat(nowElapsed)
        Watchdog.arm(ctx, l)
        lease = l

        val version = task.normal
        takeover = Takeover(l.id, task, version, skippable = true, shownAtMs = nowElapsed, leaseEndMs = l.deadlineElapsedMs)
        variation = Variation.random(Random.Default)
        lastCallCheck = nowElapsed
        lastHeartbeat = nowElapsed
        Store(ctx).pushRecentTask(task.id)

        acquireWakeLock(ctx, l.durationMs)
        val engine = CueEngine(ctx) { onFocusLost() }
        cue = engine
        try {
            engine.start()
        } catch (e: RuntimeException) {
            Log.e(TAG, "cue failed; the screen carries the takeover alone", e)
        }
        Notices.showSpell(ctx, version.command)
        launch(ctx)
        main.removeCallbacks(loop)
        main.post(loop)
        notifyListeners()
        return true
    }

    fun begin() {
        val t = takeover ?: return
        if (t.begin(SystemClock.elapsedRealtime())) {
            cue?.setTempo(t.version.bpm)
            notifyListeners()
        }
    }

    fun done() {
        val t = takeover ?: return
        if (t.done(SystemClock.elapsedRealtime())) finish(Outcome.DONE)
    }

    fun skip() {
        val t = takeover ?: return
        if (t.skip(SystemClock.elapsedRealtime())) finish(Outcome.SKIPPED)
    }

    /** A call or emergency takes priority. The task returns later at no cost. */
    fun yieldTo(reason: String) {
        val t = takeover ?: return
        Log.i(TAG, "yield: $reason")
        if (t.yieldTo(SystemClock.elapsedRealtime())) finish(Outcome.YIELDED)
    }

    /** Used by the escape hatch and safe mode. Safe to call at any time. */
    fun abort() {
        val t = takeover ?: return
        if (t.escape(SystemClock.elapsedRealtime())) finish(Outcome.ESCAPED)
    }

    /** Called by the guard when another app comes to the front. Brings the takeover back. */
    fun onForeground(context: Context, packageName: String, safe: Boolean) {
        if (!isHolding()) return
        if (packageName == context.packageName || safe) return
        if (CallGuard.inCall(context)) {
            yieldTo("call")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBounce < BOUNCE_MIN_INTERVAL_MS) return
        lastBounce = now
        launch(context)
    }

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    private fun launch(context: Context) {
        try {
            context.startActivity(TakeoverActivity.intent(context))
        } catch (e: RuntimeException) {
            // Background start refused; the full-screen notification is still there.
            Log.w(TAG, "takeover launch refused", e)
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            val t = takeover ?: return
            if (t.ended) return
            val nowElapsed = SystemClock.elapsedRealtime()
            val l = lease
            if (l == null || !l.isActive(System.currentTimeMillis(), nowElapsed)) {
                t.expire(nowElapsed)
                finish(Outcome.TIMEOUT)
                return
            }
            val changed = t.tick(nowElapsed)
            if (changed is Takeover.Phase.Ended) {
                finish(changed.outcome)
                return
            }
            val ctx = app
            if (ctx != null && nowElapsed - lastCallCheck >= CALL_CHECK_MS) {
                lastCallCheck = nowElapsed
                if (CallGuard.inCall(ctx)) {
                    yieldTo("call")
                    return
                }
            }
            if (ctx != null && nowElapsed - lastHeartbeat >= HEARTBEAT_MS) {
                lastHeartbeat = nowElapsed
                Safety.files(ctx).writeHeartbeat(nowElapsed)
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun onFocusLost() {
        val ctx = app ?: return
        if (CallGuard.inCall(ctx)) yieldTo("call")
    }

    private fun finish(outcome: Outcome) {
        main.removeCallbacks(loop)
        val ctx = app
        // Release the lock before anything else can fail.
        lease = null
        if (ctx != null) {
            runCatching { Safety.files(ctx).clearLease() }
            runCatching { Watchdog.cancel(ctx) }
            runCatching { Notices.cancelSpell(ctx) }
        }
        when (outcome) {
            Outcome.DONE -> cue?.release(withTone = true)
            Outcome.SKIPPED, Outcome.TIMEOUT -> cue?.release(withTone = false)
            Outcome.YIELDED, Outcome.ESCAPED -> cue?.stopNow()
        }
        cue = null
        main.postDelayed({ releaseWakeLock() }, 3_000)
        val t = takeover
        if (ctx != null && t != null && outcome == Outcome.YIELDED) {
            runCatching { SpellScheduler.returnLater(ctx, t.task.id, YIELD_RETURN_MINUTES) }
        }
        Log.i(TAG, "takeover ${t?.task?.id} ended: $outcome latency=${t?.latencyMs}")
        notifyListeners()
    }

    private fun acquireWakeLock(context: Context, durationMs: Long) {
        releaseWakeLock()
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "koto:takeover").apply {
            setReferenceCounted(false)
            acquire(durationMs + 10_000)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notifyListeners() {
        for (l in listeners.toList()) l.onSpellChanged()
    }
}
