package koto.app.spell

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import koto.app.data.SpellLog
import koto.app.data.Store
import koto.app.notify.Notices
import koto.app.safety.CallGuard
import koto.app.safety.Safety
import koto.app.safety.Watchdog
import koto.core.art.Variation
import koto.core.safety.LockKind
import koto.core.safety.LockLease
import koto.core.safety.LockLimits
import koto.core.spell.Distractions
import koto.core.spell.Escalation
import koto.core.spell.Feedback
import koto.core.spell.Followup
import koto.core.spell.Outcome
import koto.core.spell.Source
import koto.core.spell.SpellTask
import koto.core.spell.TaskKind
import koto.core.spell.Takeover
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

/**
 * The one live takeover in this process. Owns its lease, cue, timers and log entry. Main thread only.
 *
 * Every exit path goes through [finish], which releases the lease first. While the lease is
 * active the heartbeat keeps the watchdog quiet; if this thread stops, the watchdog kills the
 * process and the screen is released anyway.
 *
 * A pulse holds the screen. A siege calls like a pulse; once begun it takes a siege lease and
 * holds only the distraction apps, which it bounces back to the siege screen.
 */
object Spell {
    private const val TAG = "koto.spell"
    private const val TICK_MS = 250L
    private const val CALL_CHECK_MS = 500L
    private const val HEARTBEAT_MS = 5_000L
    private const val BOUNCE_MIN_INTERVAL_MS = 1_200L

    /** Minimum gap between the end of one takeover and the start of the next (tests excepted). */
    private const val COOLDOWN_MS = 3 * 60_000L

    /** A siege lease outlasts its timer by this much, so the release cue plays inside it. */
    private const val SIEGE_MARGIN_MS = 60_000L

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

    /** Where the current takeover came from and its escalation level. */
    var source: Source = Source.SCHEDULE
        private set
    var level: Int = 0
        private set

    /** Whether the last finished takeover cost a mark (a real skip; tests never do). */
    var marked: Boolean = false
        private set

    /** The feedback given for the last finished takeover, if any. */
    var feedback: Feedback? = null
        private set

    /** Elapsed time the last takeover ended; the next one waits a little (see [isCoolingDown]). */
    private var lastEndedAt: Long = Long.MIN_VALUE / 2

    /** Elapsed time of the last bounce away from a distraction app during a siege. */
    var lastBlockedAt: Long = 0L
        private set

    private var lease: LockLease? = null
    private var cue: CueEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastCallCheck = 0L
    private var lastHeartbeat = 0L
    private var lastBounce = 0L
    private var mutedForCall = false

    /** True while the user is being held: a live takeover under an active lease. */
    fun isHolding(): Boolean {
        val t = takeover ?: return false
        val l = lease ?: return false
        return !t.ended && l.isActive(System.currentTimeMillis(), SystemClock.elapsedRealtime())
    }

    /** A takeover that the screen should show, including its release animation. */
    fun hasLiveTakeover(): Boolean = takeover?.ended == false

    /** True for a few minutes after a takeover ends, so two never land back to back. */
    fun isCoolingDown(): Boolean = SystemClock.elapsedRealtime() - lastEndedAt < COOLDOWN_MS

    /** A siege is running (begun, not ended). */
    fun inSiege(): Boolean {
        val t = takeover ?: return false
        return t.kind == TaskKind.SIEGE && t.phase is Takeover.Phase.Active
    }

    fun start(context: Context, task: SpellTask, source: Source, level: Int): Boolean {
        if (takeover?.ended == false) return false
        val ctx = context.applicationContext
        app = ctx
        val files = Safety.files(ctx)
        if (files.disabled || files.safeMode != null) return false

        val limits = limits(ctx)
        val nowElapsed = SystemClock.elapsedRealtime()
        // Every takeover starts under a pulse lease; a siege moves to its own lease on begin.
        val l = LockLease.grant(
            id = UUID.randomUUID().toString(),
            kind = LockKind.PULSE,
            requestedMs = limits.maxMs(LockKind.PULSE),
            limits = limits,
            nowWallMs = System.currentTimeMillis(),
            nowElapsedMs = nowElapsed,
        )
        if (!holdLease(ctx, l)) return false

        val version = Escalation.version(task, level)
        takeover = Takeover(
            id = l.id,
            task = task,
            version = version,
            skippable = Escalation.skippable(level),
            shownAtMs = nowElapsed,
            leaseEndMs = l.deadlineElapsedMs,
        )
        this.source = source
        this.level = level
        variation = Variation.random(Random.Default)
        feedback = null
        marked = false
        mutedForCall = false
        lastCallCheck = nowElapsed
        lastHeartbeat = nowElapsed
        Store(ctx).pushRecentTask(task.id)
        SpellLog.started(ctx, l.id, task, floor = level >= Escalation.FLOOR_LEVEL, level, source, version.seconds, System.currentTimeMillis())

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
        val ctx = app ?: return
        if (!t.begin(SystemClock.elapsedRealtime())) return
        if (t.kind == TaskKind.SIEGE) {
            val taskMs = t.version.seconds * 1_000L
            val siegeLease = LockLease.grant(
                id = t.id,
                kind = LockKind.SIEGE,
                requestedMs = taskMs + SIEGE_MARGIN_MS,
                limits = limits(ctx),
                nowWallMs = System.currentTimeMillis(),
                nowElapsedMs = SystemClock.elapsedRealtime(),
            )
            if (holdLease(ctx, siegeLease)) {
                t.relock(siegeLease.deadlineElapsedMs)
                acquireWakeLock(ctx, siegeLease.durationMs)
            }
            Notices.cancelSpell(ctx)
            cue?.beginSiege(taskMs)
        } else {
            cue?.beginPulse(t.version.bpm)
        }
        notifyListeners()
    }

    fun done() {
        val t = takeover ?: return
        if (t.done(SystemClock.elapsedRealtime())) finish(Outcome.DONE)
    }

    fun skip() {
        val t = takeover ?: return
        if (t.skip(SystemClock.elapsedRealtime())) finish(Outcome.SKIPPED)
    }

    /** Stop a running siege early. */
    fun stop() {
        val t = takeover ?: return
        if (t.abandon(SystemClock.elapsedRealtime())) finish(Outcome.ABANDONED)
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

    /** The one-tap answer after a finished task. */
    fun giveFeedback(value: Feedback) {
        val t = takeover ?: return
        val ctx = app ?: return
        if (t.outcome != Outcome.DONE || feedback != null) return
        feedback = value
        SpellLog.feedback(ctx, t.id, value)
        notifyListeners()
    }

    /**
     * Called by the guard when another app comes to the front. A pulse, or a siege still calling,
     * brings the takeover back unless the app is safe. A running siege only bounces distraction
     * apps: [goHome] sends the blocked app to the background first.
     */
    fun onForeground(context: Context, packageName: String, safe: Boolean, goHome: () -> Unit) {
        if (!isHolding()) return
        if (packageName == context.packageName) return
        val now = SystemClock.elapsedRealtime()
        if (inSiege()) {
            val blocked = Store(context).config()?.distractions ?: Distractions.DEFAULT_PACKAGES
            if (packageName !in blocked) return
            if (now - lastBounce < BOUNCE_MIN_INTERVAL_MS) return
            lastBounce = now
            lastBlockedAt = now
            goHome()
            launch(context)
            notifyListeners()
            return
        }
        if (safe) return
        if (CallGuard.inCall(context)) {
            yieldTo("call")
            return
        }
        if (now - lastBounce < BOUNCE_MIN_INTERVAL_MS) return
        lastBounce = now
        launch(context)
    }

    /** Reopens the takeover screen (from the main screen, during a siege). */
    fun show(context: Context) {
        if (hasLiveTakeover()) launch(context)
    }

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    private fun limits(context: Context): LockLimits = Store(context).config()?.limits ?: LockLimits()

    private fun holdLease(context: Context, l: LockLease): Boolean {
        try {
            Safety.files(context).writeLease(l)
        } catch (e: IOException) {
            Log.e(TAG, "cannot write lease; not holding anything", e)
            return false
        }
        Safety.files(context).writeHeartbeat(SystemClock.elapsedRealtime())
        Watchdog.arm(context, l)
        lease = l
        return true
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
                val inCall = CallGuard.inCall(ctx)
                if (inSiege()) {
                    // A running siege doesn't end for a call; its tick just goes quiet.
                    if (inCall != mutedForCall) {
                        mutedForCall = inCall
                        cue?.mute(inCall)
                    }
                } else if (inCall) {
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
        if (!inSiege() && CallGuard.inCall(ctx)) yieldTo("call")
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
            Outcome.SKIPPED, Outcome.ABANDONED, Outcome.TIMEOUT -> cue?.release(withTone = false)
            Outcome.YIELDED, Outcome.ESCAPED -> cue?.stopNow()
        }
        cue = null
        // Keep the CPU up through the release tone; release exactly this lock, not a later one.
        val held = wakeLock
        wakeLock = null
        main.postDelayed({ held?.let { if (it.isHeld) it.release() } }, 3_000)

        val t = takeover
        lastEndedAt = SystemClock.elapsedRealtime()
        marked = Escalation.costsMark(outcome) && source != Source.TEST
        if (ctx != null && t != null) {
            runCatching {
                SpellLog.ended(ctx, t.id, outcome, t.latencyMs, t.activeMs(lastEndedAt), marked, System.currentTimeMillis())
            }
            Log.i(TAG, "takeover ${t.task.id} ended: $outcome level=$level latency=${t.latencyMs}")
        }
        notifyListeners()
        // Schedule the follow-up after the screen has seen this ending.
        val next = Escalation.after(outcome, level, source)
        if (ctx != null && t != null && next is Followup.Return) {
            main.post { runCatching { SpellScheduler.returnLater(ctx, t.task.id, next) } }
        }
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
