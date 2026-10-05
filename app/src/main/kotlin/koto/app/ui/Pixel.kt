package koto.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.widget.TextView
import koto.core.art.Ink
import kotlin.math.max
import kotlin.math.roundToInt

/** Palette, font and sizes. Black, off-white, red. Red only ever means a spell is active. */
object Pixel {
    const val BLACK = 0xFF0A0A0A.toInt()
    const val WHITE = 0xFFE8E4DA.toInt()
    const val RED = 0xFFB3001B.toInt()
    const val RED_BRIGHT = 0xFFE0182F.toInt()
    const val RED_DARK = 0xFF7A0010.toInt()
    const val GREY = 0xFF6E6A62.toInt()

    /** Ink → colour while a spell is active (CLEAR lets the red flood through). */
    val SPELL_INK = intArrayOf(0, BLACK, RED_BRIGHT, RED_DARK, WHITE)

    /** Ink → colour during a running siege: an ember on black, present but not shouting. */
    val EMBER_INK = intArrayOf(0, BLACK, 0xFF9E0E22.toInt(), 0xFF4A0610.toInt(), 0xFF3A3835.toInt())

    /** Ink → colour on idle screens: the eye is dark and muted. */
    val IDLE_INK = intArrayOf(0, BLACK, 0xFF3A0A10.toInt(), 0xFF22070B.toInt(), 0xFF2C2A27.toInt())

    /** The sprite rows (of 64) that contain the eye; the lids span about 62% of its height. */
    val EYE_ROWS = Rect(0, 11, 64, 53)

    private var typeface: Typeface? = null

    /** Departure Mono: a monospaced pixel font on an 11 px grid. */
    fun font(context: Context): Typeface =
        typeface ?: Typeface.createFromAsset(context.assets, "fonts/DepartureMono-Regular.otf").also { typeface = it }

    /** Integer scale of the font's 11 px grid for this screen, so glyphs land on whole pixels. */
    fun scale(context: Context): Int {
        val width = context.resources.displayMetrics.widthPixels
        return max(2, (width / 270f).roundToInt())
    }

    fun small(context: Context): Float = 11f * scale(context)
    fun medium(context: Context): Float = 11f * (scale(context) * 3 / 2)
    fun large(context: Context): Float = 22f * scale(context)

    fun paint(context: Context, size: Float, color: Int): Paint = Paint().apply {
        typeface = font(context)
        textSize = size
        this.color = color
        isAntiAlias = false
        isFilterBitmap = false
        isSubpixelText = false
    }

    /** Hard-edged text on a framework TextView. */
    fun style(view: TextView, size: Float, color: Int) {
        view.typeface = font(view.context)
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        view.setTextColor(color)
        view.paint.isAntiAlias = false
        view.paint.isSubpixelText = false
        view.includeFontPadding = false
        view.setLineSpacing(size * 0.25f, 1f)
    }

    /** Copies an [Ink] index grid into [bitmap] with the given palette. */
    fun paintSprite(ink: IntArray, palette: IntArray, bitmap: Bitmap, scratch: IntArray) {
        for (i in ink.indices) scratch[i] = palette[ink[i].coerceIn(Ink.CLEAR, Ink.WHITE)]
        bitmap.setPixels(scratch, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    /** Greedy word wrap for Canvas text. */
    fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            var line = ""
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                    line = candidate
                } else {
                    lines += line
                    line = word
                }
            }
            lines += line
        }
        return lines
    }
}
