package koto.core.art

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pixel colour indices. The app maps them to real colours per mood (spell / idle). */
object Ink {
    const val CLEAR = 0
    const val BLACK = 1
    const val RED = 2
    const val RED_DARK = 3
    const val WHITE = 4
}

/** The forms of the eye, from no tomoe to beyond Mangekyō. Layer 4 maps progress stages onto them. */
enum class EyeForm { BARE, TOMOE_1, TOMOE_2, TOMOE_3, MANGEKYO, ETERNAL }

/** 4x4 and 8x8 ordered-dither thresholds in [0, 1). Dithering replaces every smooth gradient. */
object Bayer {
    private val M4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    private val M8: IntArray = IntArray(64).also { m ->
        for (y in 0 until 8) for (x in 0 until 8) {
            val base = M4[(y % 4) * 4 + (x % 4)] * 4
            val quadrant = intArrayOf(0, 2, 3, 1)[(y / 4) * 2 + (x / 4)]
            m[y * 8 + x] = base + quadrant
        }
    }

    fun t4(x: Int, y: Int): Float = (M4[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f
    fun t8(x: Int, y: Int): Float = (M8[(y and 7) * 8 + (x and 7)] + 0.5f) / 64f
}

/**
 * Procedural pixel-art eye. Produces an index grid ([Ink]) of [size] x [size]; the app scales it
 * up with nearest-neighbour sampling. Everything is hard-edged; shading is ordered dithering.
 */
object EyeSprite {

    /**
     * @param rotation pattern rotation in radians (quantise it before calling for chunky frames)
     * @param openness 0 = closed (a single horizontal line), 1 = fully open
     */
    fun render(size: Int, form: EyeForm, rotation: Double, openness: Double): IntArray {
        val px = IntArray(size * size)
        val half = size / 2.0
        val open = openness.coerceIn(0.0, 1.0)
        val lid = BooleanArray(size * size)

        for (y in 0 until size) for (x in 0 until size) {
            val nx = (x + 0.5 - half) / half
            val ny = (y + 0.5 - half) / half
            lid[y * size + x] = insideLid(nx, ny, open)
        }

        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            val nx = (x + 0.5 - half) / half
            val ny = (y + 0.5 - half) / half
            if (!lid[i]) {
                // A (nearly) closed eye still shows its line: one row across the lid width.
                if (y == size / 2 && abs(nx) < LID_HALF_WIDTH && open < CLOSED_BELOW) px[i] = Ink.BLACK
                continue
            }
            if (isLidEdge(lid, x, y, size)) {
                px[i] = Ink.BLACK
                continue
            }
            px[i] = shade(form, nx, ny, rotation, x, y)
        }
        return px
    }

    private const val LID_HALF_WIDTH = 0.96
    private const val CLOSED_BELOW = 0.08
    private const val LID_HALF_HEIGHT = 0.62
    private const val IRIS = 0.47

    private fun insideLid(nx: Double, ny: Double, open: Double): Boolean {
        val u = nx / LID_HALF_WIDTH
        if (abs(u) >= 1) return false
        val h = LID_HALF_HEIGHT * open * (1 - u * u).pow(0.9)
        return abs(ny) <= h
    }

    private fun isLidEdge(lid: BooleanArray, x: Int, y: Int, size: Int): Boolean {
        if (x == 0 || y == 0 || x == size - 1 || y == size - 1) return true
        return !lid[y * size + x - 1] || !lid[y * size + x + 1] ||
            !lid[(y - 1) * size + x] || !lid[(y + 1) * size + x]
    }

    private fun shade(form: EyeForm, nx: Double, ny: Double, rotation: Double, x: Int, y: Int): Int {
        val r = sqrt(nx * nx + ny * ny) / IRIS
        if (r > 1.0) {
            // Sclera: off-white, falling into black shadow under the lids.
            val shadow = ((abs(ny) / LID_HALF_HEIGHT) * 1.25 + (abs(nx) - 0.6).coerceAtLeast(0.0)).toFloat()
            return if (shadow > Bayer.t4(x, y) + 0.35f) Ink.BLACK else Ink.WHITE
        }
        if (r > 0.92) return Ink.BLACK // limbal ring
        if (isHighlight(nx, ny)) return Ink.WHITE

        val theta = atan2(ny, nx)
        if (isPattern(form, r, theta, rotation)) return Ink.BLACK

        // Iris: bright at the centre, dithered into dark red towards the ring.
        val dark = ((r - 0.45) / 0.5).toFloat().coerceIn(0f, 1f)
        return if (dark > Bayer.t4(x, y)) Ink.RED_DARK else Ink.RED
    }

    private fun isHighlight(nx: Double, ny: Double): Boolean {
        val hx = nx + IRIS * 0.38
        val hy = ny + IRIS * 0.38
        return hx * hx + hy * hy < (IRIS * 0.09).pow(2)
    }

    private fun isPattern(form: EyeForm, r: Double, theta: Double, rot: Double): Boolean = when (form) {
        EyeForm.BARE -> r < 0.2
        EyeForm.TOMOE_1 -> pupilAndRing(r) || tomoe(r, theta, rot, 1)
        EyeForm.TOMOE_2 -> pupilAndRing(r) || tomoe(r, theta, rot, 2)
        EyeForm.TOMOE_3 -> pupilAndRing(r) || tomoe(r, theta, rot, 3)
        EyeForm.MANGEKYO -> blades(r, theta, rot, count = 4, twist = 1.25, reach = 0.92)
        EyeForm.ETERNAL -> blades(r, theta, rot, count = 4, twist = 1.25, reach = 0.92) ||
            blades(r, theta, rot + PI / 4, count = 4, twist = -1.0, reach = 0.6) ||
            (r in 0.62..0.67)
    }

    private fun pupilAndRing(r: Double): Boolean = r < 0.2 || (r in 0.585..0.625)

    /** Comma shapes sitting on the inner ring, tails trailing against the rotation. */
    private fun tomoe(r: Double, theta: Double, rot: Double, count: Int): Boolean {
        val ringR = 0.6
        val headR = 0.2
        val tailArc = 1.15
        val px = r * cos(theta)
        val py = r * sin(theta)
        for (k in 0 until count) {
            val a = rot + k * 2 * PI / count
            val dx = px - ringR * cos(a)
            val dy = py - ringR * sin(a)
            if (dx * dx + dy * dy < headR * headR) return true
            // Tail: a crescent that leaves the head on its outer side and thins to a point.
            val behind = wrap(a - theta)
            if (behind in 0.0..tailArc) {
                val f = behind / tailArc
                val centre = ringR + headR * 0.55 * (1 - f) + 0.06 * f
                val thickness = headR * 1.1 * (1 - f).pow(0.8)
                if (abs(r - centre) < thickness / 2) return true
            }
        }
        return false
    }

    /** Curved blades growing from a black core: a pinwheel Mangekyō. */
    private fun blades(r: Double, theta: Double, rot: Double, count: Int, twist: Double, reach: Double): Boolean {
        if (r < 0.3) return r > 0.15 || r < 0.09 // core with a thin red ring around the pupil
        if (r > reach) return false
        val t = (r - 0.3) / (reach - 0.3)
        val width = 1.3 * (1 - t).pow(1.15) + 0.06
        for (k in 0 until count) {
            val centre = rot + k * 2 * PI / count + twist * t
            if (abs(wrap(theta - centre)) < width / 2) return true
        }
        return false
    }

    /** Angle wrapped into (-PI, PI]. */
    private fun wrap(a: Double): Double {
        var v = a % (2 * PI)
        if (v > PI) v -= 2 * PI
        if (v <= -PI) v += 2 * PI
        return v
    }
}
