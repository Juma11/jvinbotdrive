package com.vintechafrica.jvindrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min

object C {
    const val BG = Color.BLACK
    val CARD = Color.rgb(28, 28, 30)
    val CARD2 = Color.rgb(44, 44, 46)
    const val TEXT = Color.WHITE
    val SUB = Color.rgb(142, 142, 147)
    val BLUE = Color.rgb(10, 132, 255)
    val GREEN = Color.rgb(48, 209, 88)
    val RED = Color.rgb(255, 69, 58)
    val ORANGE = Color.rgb(255, 159, 10)
    val CYAN = Color.rgb(0, 200, 255)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Int = 18) = GradientDrawable().apply {
    cornerRadius = dp(radiusDp).toFloat()
    setColor(color)
}

fun Context.oval(color: Int, strokeColor: Int? = null, strokeDp: Int = 0) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
}

fun Context.text(t: CharSequence, size: Float = 14f, color: Int = C.TEXT, bold: Boolean = false, lines: Int = 0) =
    TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        if (lines > 0) {
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }
    }

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** Small tappable label. */
fun Context.chip(t: String, color: Int, onClick: (() -> Unit)? = null) = text(t, 12f, C.TEXT, bold = true).apply {
    background = rounded(color, 14)
    setPadding(dp(10), dp(5), dp(10), dp(5))
    gravity = Gravity.CENTER
    if (onClick != null) {
        isClickable = true
        setOnClickListener { onClick() }
    }
}

fun Context.tile(title: String, sub: String?, accent: Int, onClick: () -> Unit) = hbox().apply {
    background = rounded(C.CARD)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(8) }
    isClickable = true
    setOnClickListener { onClick() }
    addView(View(this@tile).apply {
        background = oval(accent)
        layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
    })
    addView(vbox().apply {
        setPadding(dp(12), 0, 0, 0)
        addView(text(title, 15f, bold = true, lines = 1))
        if (sub != null) addView(text(sub, 12f, C.SUB, lines = 2))
    })
}

/**
 * On-screen joystick. Reports x (right +) and y (forward +) in -1..1,
 * and snaps back to center on release.
 */
class PadView(c: Context, private val onMove: (Float, Float, Boolean) -> Unit) : View(c) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = C.CYAN
        strokeWidth = c.dp(2).toFloat()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.CARD }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.CYAN }
    private var kx = 0f
    private var ky = 0f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - ring.strokeWidth
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawCircle(cx + kx * r * 0.65f, cy + ky * r * 0.65f, r * 0.32f, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.65f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = (e.x - cx) / r
                var dy = (e.y - cy) / r
                val d = hypot(dx, dy)
                if (d > 1f) {
                    dx /= d
                    dy /= d
                }
                kx = dx
                ky = dy
                onMove(dx, -dy, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                kx = 0f
                ky = 0f
                onMove(0f, 0f, false)
            }
        }
        invalidate()
        return true
    }
}
