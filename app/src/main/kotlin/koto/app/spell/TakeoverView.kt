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
import koto.core.spell.Outcome
import koto.core.spell.Takeover
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min

/**
 * The takeover, drawn by hand: a dithered red flood, the Mangekyō in chunky frames, and terminal
 * text. All motion is slow (see [Motion]); nothing flashes.
 */
@SuppressLint("ViewConstructor")
class TakeoverView(context: Context, private val actions: Actions) : View(context) {

    interface Actions {
        fun begin()
        fun done()
        fun skip()
        fun emergency()
        fun released()
    }

    private var takeover: Takeover? = null
    private var variation: Variation = Variation.random(kotlin.random.Random.Default)
    private val createdAt = SystemClock.uptimeMillis()
    private var releaseAt = 0L
    private var releasedNotified = false

    // Text
    private val small = Pixel.small(context)
    private val medium = Pixel.medium(context)
    private val large = Pixel.large(context)
    private val textWhite = Pixel.paint(context, large, Pixel.WHITE)
    private val pixels = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    // Eye
    private val sprite = 64
    private val eyeBitmap = Bitmap.createBitmap(sprite, sprite, Bitmap.Config.ARGB_8888)
    private val eyeScratch = IntArray(sprite * sprite)
    private var eyeKey = -1L
    private val eyeDst = Rect()

    // Flood
    private var floodBitmap: Bitmap? = null
    private var floodScratch = IntArray(0)
    private var floodLevel = -1
    private val floodDst = Rect()

    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private var multiTouch = false

    fun bind(t: Takeover, v: Variation) {
        takeover = t
        variation = v
        if (t.ended && releaseAt == 0L) releaseAt = SystemClock.uptimeMillis()
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
        val releasing = releaseAt > 0
        // A revealed answer stays readable before the dissolve starts.
        val hold = if (t.outcome == Outcome.DONE && t.task.reveal.isNotEmpty()) REVEAL_HOLD_MS else 0L
        val releaseProgress = if (releasing) ((now - releaseAt - hold).toFloat() / Motion.RELEASE_MS).coerceIn(0f, 1f) else 0f

        // Red flood: dithered dissolve in, dissolve out on release.
        val floodIn = ((age - 150).toFloat() / Motion.FLOOD_IN_MS).coerceIn(0f, 1f)
        val flood = if (releasing) min(floodIn, 1f - releaseProgress) else floodIn
        drawFlood(canvas, quantise(flood, Motion.FLOOD_STEPS))

        // Eye: opens from a single line, turns in chunky steps, closes on release.
        val opening = quantise((age.toFloat() / variation.openMs).coerceIn(0f, 1f), Motion.OPEN_STEPS)
        val openness = if (releasing) min(opening, 1f - releaseProgress) else opening
        val step = (age / variation.rotationStepMs) % Variation.ROTATION_STEPS
        drawEye(canvas, step.toInt(), quantise(openness, Motion.OPEN_STEPS))

        hits.clear()
        drawText(canvas, t, age, now, releasing)

        if (releasing && releaseProgress >= 1f && !releasedNotified) {
            releasedNotified = true
            actions.released()
            return
        }
        postInvalidateDelayed(Motion.FRAME_MS)
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

    private fun drawEye(canvas: Canvas, step: Int, openness: Float) {
        val key = step * 1000L + (openness * 100).toInt()
        if (key != eyeKey) {
            eyeKey = key
            val rotation = step * 2 * PI / Variation.ROTATION_STEPS
            val ink = EyeSprite.render(sprite, EyeForm.MANGEKYO, rotation, openness.toDouble())
            Pixel.paintSprite(ink, Pixel.SPELL_INK, eyeBitmap, eyeScratch)
        }
        canvas.drawBitmap(eyeBitmap, Pixel.EYE_ROWS, eyeDst, pixels)
    }

    private fun drawText(canvas: Canvas, t: Takeover, age: Long, now: Long, releasing: Boolean) {
        val margin = width * 0.07f
        val maxWidth = width - 2 * margin
        val bottomWords = height - margin * 1.2f

        // The way to an emergency call is visible from the very first frame.
        textWhite.textSize = small
        if (!releasing) word(canvas, "emergency", margin, bottomWords, textWhite) { actions.emergency() }

        // Opening line, then the command, typed.
        var y = eyeDst.bottom + medium * 1.6f
        textWhite.textSize = medium
        canvas.drawText(variation.openingLine.take((age / Motion.TYPE_MS_PER_CHAR).toInt()), margin, y, textWhite)

        val commandStart = variation.openingLine.length * Motion.TYPE_MS_PER_CHAR + 300
        val typed = ((age - commandStart) / Motion.TYPE_MS_PER_CHAR).toInt().coerceIn(0, t.version.command.length)
        textWhite.textSize = large
        val lines = Pixel.wrap(t.version.command.take(typed), textWhite, maxWidth)
        var baseline = y
        for (line in lines) {
            baseline += large * 1.3f
            canvas.drawText(line, margin, baseline, textWhite)
        }
        y = baseline
        val typedAll = typed >= t.version.command.length
        val cursorOn = (now / Motion.CURSOR_HALF_PERIOD_MS) % 2 == 0L
        if (!releasing && typed > 0 && (!typedAll || cursorOn)) {
            val cx = margin + textWhite.measureText(lines.last()) + large * 0.15f
            canvas.drawRect(cx, baseline - large * 0.72f, cx + large * 0.45f, baseline + large * 0.08f, textWhite)
        }
        if (!typedAll) return

        textWhite.textSize = small
        for (line in Pixel.wrap(t.version.detail, textWhite, maxWidth)) {
            if (line.isEmpty()) continue
            y += small * 1.5f
            canvas.drawText(line, margin, y, textWhite)
        }

        val primary = maxOf(height * 0.80f, y + large * 2.4f).coerceAtMost(bottomWords - small * 2.5f)
        when (val phase = t.phase) {
            is Takeover.Phase.Calling -> if (!releasing) {
                textWhite.textSize = large
                word(canvas, "> begin", margin, primary, textWhite) { actions.begin() }
                if (t.skippable) {
                    textWhite.textSize = small
                    word(canvas, "skip", width - margin - textWhite.measureText("skip"), bottomWords, textWhite) { actions.skip() }
                }
            }
            is Takeover.Phase.Active -> if (!releasing) {
                val secs = ceil((t.activeRemainingMs(SystemClock.elapsedRealtime()) ?: 0) / 1000.0).toInt()
                textWhite.textSize = medium
                canvas.drawText("%d:%02d".format(secs / 60, secs % 60), margin, y + medium * 1.8f, textWhite)
                textWhite.textSize = large
                word(canvas, "> done", margin, primary, textWhite) { actions.done() }
            }
            is Takeover.Phase.Ended -> {
                val line = when (phase.outcome) {
                    Outcome.DONE -> t.task.reveal.ifEmpty { "Done." }
                    Outcome.SKIPPED -> "Later."
                    Outcome.ESCAPED -> "Released."
                    Outcome.TIMEOUT, Outcome.YIELDED -> ""
                }
                textWhite.textSize = medium
                for (l in Pixel.wrap(line, textWhite, maxWidth)) {
                    y += medium * 1.6f
                    canvas.drawText(l, margin, y, textWhite)
                }
            }
        }
    }

    private fun word(canvas: Canvas, text: String, x: Float, baseline: Float, paint: Paint, action: () -> Unit) {
        canvas.drawText(text, x, baseline, paint)
        val pad = paint.textSize * 0.6f
        val w = paint.measureText(text)
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
        const val REVEAL_HOLD_MS = 3_000L
    }
}
