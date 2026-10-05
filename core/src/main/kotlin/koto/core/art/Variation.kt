package koto.core.art

import kotlin.random.Random

/**
 * Per-takeover visual variation, so the eye never becomes wallpaper. Only visuals vary;
 * the vibration, buzz and metronome never do.
 */
data class Variation(
    /** Milliseconds for one full rotation of the pattern. */
    val rotationPeriodMs: Long,
    /** The eyelid opens from a single line over this long. */
    val openMs: Long,
    /** The first line of text, typed before the command. */
    val openingLine: String,
) {
    /** Milliseconds per chunky rotation frame. */
    val rotationStepMs: Long get() = rotationPeriodMs / ROTATION_STEPS

    companion object {
        const val ROTATION_STEPS = 24
        const val MIN_ROTATION_PERIOD_MS = 5_200L
        const val MAX_ROTATION_PERIOD_MS = 8_400L

        val OPENING_LINES = listOf("Look.", "Now.", "Here.", "Attend.", "Listen.", "It is time.", "Eyes here.")

        fun random(random: Random): Variation = Variation(
            rotationPeriodMs = random.nextLong(MIN_ROTATION_PERIOD_MS, MAX_ROTATION_PERIOD_MS + 1),
            openMs = random.nextLong(700, 1_401),
            openingLine = OPENING_LINES.random(random),
        )
    }
}

/**
 * Animation timing shared by every screen. Kept slow on purpose: nothing may change luminance
 * faster than 2 Hz (no strobing, no fast flashing), including for a sleep-deprived user.
 */
object Motion {
    /** Redraw period for text typing and dissolves. Redrawing is not flashing: content changes monotonically. */
    const val FRAME_MS = 125L

    /** Red flood dissolve in: steps over duration. */
    const val FLOOD_IN_MS = 1_000L
    const val FLOOD_STEPS = 8

    /** Fade back to black after release. */
    const val RELEASE_MS = 1_400L

    /** Cursor blink half-period: on 500 ms, off 500 ms = 1 Hz. */
    const val CURSOR_HALF_PERIOD_MS = 500L

    /** Typing speed. */
    const val TYPE_MS_PER_CHAR = 35L

    /** Eyelid opening is quantised into this many steps. */
    const val OPEN_STEPS = 6

    /** Highest rate at which any element may alternate between two luminance states. */
    const val MAX_FLICKER_HZ = 2.0

    fun cursorHz(): Double = 1000.0 / (2 * CURSOR_HALF_PERIOD_MS)

    /** Eye frame changes per second at a given rotation period. */
    fun rotationFps(v: Variation): Double = 1000.0 / v.rotationStepMs
}
