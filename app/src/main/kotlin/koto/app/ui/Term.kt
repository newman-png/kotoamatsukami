package koto.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import koto.core.art.EyeForm
import koto.core.art.EyeSprite

/**
 * Builds the idle screens: near-black, off-white words, no chrome. Commands are lines that
 * start with "> ", and words stand in for icons.
 */
class Term(private val activity: Activity) {
    private val ctx: Context = activity
    val small = Pixel.small(ctx)
    val medium = Pixel.medium(ctx)
    val large = Pixel.large(ctx)
    private val pad = (small * 1.4f).toInt()

    val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }

    private val scroll = ScrollView(ctx).apply {
        setBackgroundColor(Pixel.BLACK)
        isFillViewport = true
        addView(column)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Edge-to-edge is enforced from Android 15: keep text clear of the bars and keyboard.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    fun line(text: String, color: Int = Pixel.WHITE, size: Float = small): TextView =
        TextView(ctx).apply {
            this.text = text
            Pixel.style(this, size, color)
            setPadding(0, (size * 0.2f).toInt(), 0, (size * 0.2f).toInt())
            column.addView(this)
        }

    fun title(text: String): TextView = line(text, Pixel.GREY)

    fun gap(lines: Float = 1f) {
        column.addView(View(ctx), LinearLayout.LayoutParams(1, (small * 1.4f * lines).toInt()))
    }

    /** A tappable command: "> label". */
    fun command(label: String, color: Int = Pixel.WHITE, size: Float = medium, onClick: () -> Unit): TextView =
        line("> $label", color, size).apply {
            setPadding(0, (size * 0.45f).toInt(), 0, (size * 0.45f).toInt())
            setOnClickListener { onClick() }
        }

    fun eye(form: EyeForm, palette: IntArray = Pixel.IDLE_INK): View =
        EyeView(ctx, form, palette).also {
            column.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    fun input(text: String): EditText = EditText(ctx).apply {
        setText(text)
        Pixel.style(this, small, Pixel.WHITE)
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = false
        setHorizontallyScrolling(false)
        minLines = 10
        setBackgroundColor(0xFF141413.toInt())
        setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        textCursorDrawable = GradientDrawable().apply {
            setColor(Pixel.WHITE)
            setSize((small / 3).toInt(), small.toInt())
        }
        column.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    fun show() = activity.setContentView(scroll)
}

/** A still, muted eye for idle screens, scaled by whole pixels. */
@SuppressLint("ViewConstructor")
class EyeView(context: Context, form: EyeForm, palette: IntArray) : View(context) {
    private val bitmap = Bitmap.createBitmap(SPRITE, SPRITE, Bitmap.Config.ARGB_8888).also {
        Pixel.paintSprite(EyeSprite.render(SPRITE, form, 0.0, 1.0), palette, it, IntArray(SPRITE * SPRITE))
    }
    private val paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val dst = Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val scale = maxOf(1, (w * 0.7f / SPRITE).toInt())
        setMeasuredDimension(w, Pixel.EYE_ROWS.height() * scale)
    }

    override fun onDraw(canvas: Canvas) {
        val scale = height / Pixel.EYE_ROWS.height()
        val w = SPRITE * scale
        val left = (width - w) / 2
        dst.set(left, 0, left + w, height)
        canvas.drawBitmap(bitmap, Pixel.EYE_ROWS, dst, paint)
    }

    private companion object {
        const val SPRITE = 64
    }
}
