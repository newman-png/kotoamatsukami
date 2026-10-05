package koto.app.spell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import koto.app.ui.Pixel
import koto.core.art.Bayer
import koto.core.art.EyeForm
import koto.core.art.EyeSprite
import koto.core.art.Motion
import koto.core.art.Variation
import koto.core.spell.Feedback
import koto.core.spell.Outcome
import koto.core.spell.TaskKind
import koto.core.spell.Takeover
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min

/**
 * The takeover, drawn by hand: a dithered red flood, the Mangekyō in chunky frames, and terminal
 * text. All motion is slow (see [Motion]); nothing flashes.
 *
 * Calling and pulse tasks are red. A begun siege drains to black with a dim ember eye: the lock,
 * not the screen, holds it. After a finished task the red drains away, the answer (if any) and
 * the one-tap feedback stay for a few seconds, then the eye closes.
 */
@SuppressLint("ViewConstructor")
class TakeoverView(context: Context, private val actions: Actions) : View(context) {

    interface Actions {
        fun begin()
        fun done()
        fun skip()
        fun stop()
        fun feedback(value: Feedback)
        fun emergency()
        fun released()
    }

    private var takeover: Takeover? = null
    private var variation: Variation = Variation.random(kotlin.random.Random.Default)
    private var feedback: Feedback? = null
    private var marked = false
    private var blockedAt = 0L
    private val createdAt = SystemClock.uptimeMillis()
    private var activeAt = 0L
    private var releaseAt = 0L
    private var chosenAt = 0L
    private var releasedNotified = false

    private val small = Pixel.small(context)
    private val medium = Pixel.medium(context)
    private val large = Pixel.large(context)
    private val text = Pixel.paint(context, large, Pixel.WHITE)
    private val pixels = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    private val sprite = 64
    private val eyeBitmap = Bitmap.createBitmap(sprite, sprite, Bitmap.Config.ARGB_8888)
    private val eyeScratch = IntArray(sprite * sprite)
    private var eyeKey = ""
    private val eyeDst = Rect()

    private var floodBitmap: Bitmap? = null
    private var floodScratch = IntArray(0)
    private var floodLevel = -1
    private val floodDst = Rect()

    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private var multiTouch = false

    fun bind(t: Takeover, v: Variation, chosen: Feedback?, lastBlockedElapsed: Long, costMark: Boolean) {
        variation = v
        marked = costMark
        blockedAt = lastBlockedElapsed
        val now = SystemClock.uptimeMillis()
        if (t.phase is Takeover.Phase.Active && activeAt == 0L) {
            // Reopened in the middle of a siege (e.g. bounced from a blocked app): already drained, no red flash.
            activeAt = if (takeover == null && t.kind == TaskKind.SIEGE) now - SIEGE_DRAIN_MS else now
        }
        if (t.ended && releaseAt == 0L) releaseAt = now
        if (chosen != null && feedback == null) chosenAt = now
        feedback = chosen
        takeover = t
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val block = maxOf(4, w / 90)
        val fw = ceil(w / block.toFloat()).toInt()
        val fh = ceil(h / block.toFloat()).toInt()
        floodBitmap = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        floodScratch = IntArray(fw * fh)
        floodLevel = -1
        floodDst.set(0, 0, fw * block, fh * block)
        // Integer scale of the sprite, limited so the text below always fits.
        val scale = maxOf(1, min(w * 0.86f / sprite, h * 0.34f / Pixel.EYE_ROWS.height()).toInt())
        val eyeW = sprite * scale
        val left = (w - eyeW) / 2
        val top = (h * 0.10f).toInt()
        eyeDst.set(left, top, left + eyeW, top + Pixel.EYE_ROWS.height() * scale)
    }

    override fun onDraw(canvas: Canvas) {
        val t = takeover ?: return
        val now = SystemClock.uptimeMillis()
        val age = now - createdAt
        val siege = t.kind == TaskKind.SIEGE && activeAt > 0
        val releasing = releaseAt > 0
        val finishAt = if (releasing) releaseAt + maxOf(Motion.RELEASE_MS, answerMs(t)) else Long.MAX_VALUE

        // Red flood: dissolves in; drains when a siege begins and when the task ends.
        var flood = ((age - 150).toFloat() / Motion.FLOOD_IN_MS).coerceIn(0f, 1f)
        if (siege) flood = min(flood, 1f - ((now - activeAt).toFloat() / SIEGE_DRAIN_MS).coerceIn(0f, 1f))
        if (releasing) flood = min(flood, 1f - ((now - releaseAt).toFloat() / Motion.RELEASE_MS).coerceIn(0f, 1f))
        drawFlood(canvas, quantise(flood, Motion.FLOOD_STEPS))

        // Eye: opens from a single line, turns in chunky steps, closes at the very end.
        val opening = (age.toFloat() / variation.openMs).coerceIn(0f, 1f)
        val closing = if (releasing) ((finishAt - now).toFloat() / Motion.RELEASE_MS).coerceIn(0f, 1f) else 1f
        val stepMs = if (siege) variation.rotationStepMs * 2 else variation.rotationStepMs
        val step = ((age / stepMs) % Variation.ROTATION_STEPS).toInt()
        drawEye(canvas, step, quantise(min(opening, closing), Motion.OPEN_STEPS), dim = siege || flood < 0.5f)

        hits.clear()
        if (siege && !releasing) drawSiege(canvas, t) else drawCall(canvas, t, age, now, releasing)

        if (releasing && now >= finishAt) {
            if (!releasedNotified) {
                releasedNotified = true
                actions.released()
            }
            return
        }
        postInvalidateDelayed(Motion.FRAME_MS)
    }

    /** How long the answer and feedback stay after a finished task. */
    private fun answerMs(t: Takeover): Long = when {
        t.outcome != Outcome.DONE -> 0L
        chosenAt > 0 -> chosenAt - releaseAt + AFTER_CHOICE_MS
        else -> FEEDBACK_MS
    }

    private fun drawFlood(canvas: Canvas, level: Float) {
        val bmp = floodBitmap ?: return
        val steps = (level * 64).toInt()
        if (steps != floodLevel) {
            floodLevel = steps
            val w = bmp.width
            for (y in 0 until bmp.height) for (x in 0 until w) {
                floodScratch[y * w + x] = if (Bayer.t8(x, y) * 64 < steps) Pixel.RED else Pixel.BLACK
            }
            bmp.setPixels(floodScratch, 0, w, 0, 0, w, bmp.height)
        }
        canvas.drawBitmap(bmp, null, floodDst, pixels)
    }

    private fun drawEye(canvas: Canvas, step: Int, openness: Float, dim: Boolean) {
        val key = "$step/${(openness * 100).toInt()}/$dim"
        if (key != eyeKey) {
            eyeKey = key
            val rotation = step * 2 * PI / Variation.ROTATION_STEPS
            val ink = EyeSprite.render(sprite, EyeForm.MANGEKYO, rotation, openness.toDouble())
            Pixel.paintSprite(ink, if (dim) Pixel.EMBER_INK else Pixel.SPELL_INK, eyeBitmap, eyeScratch)
        }
        canvas.drawBitmap(eyeBitmap, Pixel.EYE_ROWS, eyeDst, pixels)
    }

    /** Calling, a running pulse, and every ending. */
    private fun drawCall(canvas: Canvas, t: Takeover, age: Long, now: Long, releasing: Boolean) {
        val margin = width * 0.07f
        val maxWidth = width - 2 * margin
        val bottomWords = height - margin * 1.2f

        // The way to an emergency call is visible from the very first frame.
        text.textSize = small
        if (!releasing) word(canvas, "emergency", margin, bottomWords, text) { actions.emergency() }

        // Opening line, then the command, typed.
        var y = eyeDst.bottom + medium * 1.6f
        text.textSize = medium
        canvas.drawText(variation.openingLine.take((age / Motion.TYPE_MS_PER_CHAR).toInt()), margin, y, text)

        val commandStart = variation.openingLine.length * Motion.TYPE_MS_PER_CHAR + 300
        val typed = ((age - commandStart) / Motion.TYPE_MS_PER_CHAR).toInt().coerceIn(0, t.version.command.length)
        text.textSize = large
        val lines = Pixel.wrap(t.version.command.take(typed), text, maxWidth)
        var baseline = y
        for (line in lines) {
            baseline += large * 1.3f
            canvas.drawText(line, margin, baseline, text)
        }
        y = baseline
        val typedAll = typed >= t.version.command.length
        val cursorOn = (now / Motion.CURSOR_HALF_PERIOD_MS) % 2 == 0L
        if (!releasing && typed > 0 && (!typedAll || cursorOn)) {
            val cx = margin + text.measureText(lines.last()) + large * 0.15f
            canvas.drawRect(cx, baseline - large * 0.72f, cx + large * 0.45f, baseline + large * 0.08f, text)
        }
        if (!typedAll) return

        text.textSize = small
        for (line in Pixel.wrap(t.version.detail, text, maxWidth)) {
            if (line.isEmpty()) continue
            y += small * 1.5f
            canvas.drawText(line, margin, y, text)
        }

        val primary = maxOf(height * 0.80f, y + large * 2.4f).coerceAtMost(bottomWords - small * 2.5f)
        when (val phase = t.phase) {
            is Takeover.Phase.Calling -> if (!releasing) {
                text.textSize = large
                word(canvas, "> begin", margin, primary, text) { actions.begin() }
                if (t.skippable) {
                    text.textSize = small
                    word(canvas, "skip", width - margin - text.measureText("skip"), bottomWords, text) { actions.skip() }
                }
            }
            is Takeover.Phase.Active -> if (!releasing) {
                val secs = ceil((t.activeRemainingMs(SystemClock.elapsedRealtime()) ?: 0) / 1000.0).toInt()
                text.textSize = medium
                canvas.drawText(clock(secs), margin, y + medium * 1.8f, text)
                text.textSize = large
                word(canvas, "> done", margin, primary, text) { actions.done() }
            }
            is Takeover.Phase.Ended -> drawEnding(canvas, t, phase.outcome, y, primary, margin, maxWidth, now)
        }
    }

    private fun drawEnding(
        canvas: Canvas,
        t: Takeover,
        outcome: Outcome,
        top: Float,
        primary: Float,
        margin: Float,
        maxWidth: Float,
        now: Long,
    ) {
        val line = when (outcome) {
            Outcome.DONE -> t.task.reveal.ifEmpty { "Done." }
            Outcome.SKIPPED -> if (marked) "Later. Marked." else "Later."
            Outcome.ABANDONED -> if (marked) "Stopped. Marked." else "Stopped."
            Outcome.ESCAPED -> "Released."
            Outcome.TIMEOUT, Outcome.YIELDED -> ""
        }
        var y = top
        text.textSize = medium
        for (l in Pixel.wrap(line, text, maxWidth)) {
            y += medium * 1.6f
            canvas.drawText(l, margin, y, text)
        }
        if (outcome != Outcome.DONE) return

        // One tap, optional, gone in a few seconds.
        val chosen = feedback
        if (chosen != null) {
            text.color = Pixel.GREY
            canvas.drawText(chosen.label, margin, primary, text)
            text.color = Pixel.WHITE
            return
        }
        if (now - releaseAt >= FEEDBACK_MS) return
        var x = margin
        for (f in Feedback.entries) {
            word(canvas, f.label, x, primary, text) { actions.feedback(f) }
            x += text.measureText(f.label) + medium * 1.4f
        }
    }

    /** A running siege: black, a dim eye, the time left. Only the distraction apps are held. */
    private fun drawSiege(canvas: Canvas, t: Takeover) {
        val margin = width * 0.07f
        val maxWidth = width - 2 * margin
        val bottomWords = height - margin * 1.2f
        var y = eyeDst.bottom + medium * 1.6f

        text.textSize = medium
        canvas.drawText(t.version.command.substringBefore('.') + ".", margin, y, text)

        val secs = ceil((t.activeRemainingMs(SystemClock.elapsedRealtime()) ?: 0) / 1000.0).toInt()
        text.textSize = large * 1.5f
        y += large * 2f
        canvas.drawText(clock(secs), margin, y, text)

        text.textSize = small
        y += small * 2.2f
        val note = if (SystemClock.elapsedRealtime() - blockedAt < NOT_NOW_MS) "Not now." else "Distractions are locked."
        for (l in Pixel.wrap(note, text, maxWidth)) {
            canvas.drawText(l, margin, y, text)
            y += small * 1.5f
        }

        word(canvas, "emergency", margin, bottomWords, text) { actions.emergency() }
        if (t.skippable) word(canvas, "stop", width - margin - text.measureText("stop"), bottomWords, text) { actions.stop() }
    }

    private fun clock(totalSeconds: Int): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }

    private fun word(canvas: Canvas, label: String, x: Float, baseline: Float, paint: Paint, action: () -> Unit) {
        canvas.drawText(label, x, baseline, paint)
        val pad = paint.textSize * 0.6f
        val w = paint.measureText(label)
        hits += RectF(x - pad, baseline - paint.textSize - pad, x + w + pad, baseline + pad) to action
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> multiTouch = false
            MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
            MotionEvent.ACTION_UP -> if (!multiTouch) {
                hits.firstOrNull { it.first.contains(event.x, event.y) }?.second?.invoke()
            }
        }
        return true
    }

    private fun quantise(v: Float, steps: Int): Float = (v * steps).toInt() / steps.toFloat()

    private companion object {
        /** The feedback words stay this long after a finished task. */
        const val FEEDBACK_MS = 6_000L

        /** After a tap, the chosen word shows briefly before the eye closes. */
        const val AFTER_CHOICE_MS = 700L

        /** A begun siege drains from red to black over this long. */
        const val SIEGE_DRAIN_MS = 1_600L

        /** "Not now." stays this long after a blocked app is bounced. */
        const val NOT_NOW_MS = 4_000L
    }
}
