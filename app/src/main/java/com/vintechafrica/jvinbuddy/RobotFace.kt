package com.vintechafrica.jvinbuddy

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated robot face.
 * - Eyes follow your face or hand, left/right AND up/down, with a soft spring.
 * - Blinks, winks when petted, breathes (slow bob).
 * - Per-emotion animations: happy squint + blush, heart eyes with floating hearts,
 *   X eyes, spinning dizzy eyes, falling tear, rising Zzz, thinking dots,
 *   sound bars while listening, talking mouth while speaking.
 */
@Composable
fun RobotFace(
    emotion: Emotion,
    lookX: Float,
    lookY: Float,
    winkAt: Long,
    listening: Boolean,
    speaking: Boolean,
    modifier: Modifier = Modifier,
) {
    // ---------------------------------------------------------------- blink + wink
    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2500, 6000))
            blink = true
            delay(130)
            blink = false
            if (Random.nextInt(5) == 0) { // sometimes a double blink
                delay(150)
                blink = true
                delay(110)
                blink = false
            }
        }
    }
    var winking by remember { mutableStateOf(false) }
    LaunchedEffect(winkAt) {
        if (winkAt > 0L) {
            winking = true
            delay(450)
            winking = false
        }
    }

    // ---------------------------------------------------------------- animated values
    val curiousTilt = if (emotion == Emotion.CURIOUS) 0.8f else 1f
    val openL by animateFloatAsState(
        if (blink) 0.06f else emotion.eyeOpen, tween(if (blink) 70 else 260), label = "openL"
    )
    val openR by animateFloatAsState(
        if (blink || winking) 0.06f else emotion.eyeOpen * curiousTilt,
        tween(if (blink || winking) 70 else 260), label = "openR"
    )
    val eyeScale by animateFloatAsState(
        if (emotion == Emotion.SURPRISED) 1.18f else 1f, spring(dampingRatio = 0.4f), label = "scale"
    )
    val targetX = if (emotion == Emotion.THINKING) 0.6f else lookX
    val targetY = if (emotion == Emotion.THINKING) -0.6f else lookY
    val lx by animateFloatAsState(targetX.coerceIn(-1f, 1f), spring(0.75f, 140f), label = "lx")
    val ly by animateFloatAsState(targetY.coerceIn(-1f, 1f), spring(0.75f, 140f), label = "ly")
    val curve by animateFloatAsState(emotion.mouthCurve, tween(300), label = "curve")
    val brow by animateFloatAsState(emotion.browTilt, tween(300), label = "brow")
    val color by animateColorAsState(Color(emotion.color), tween(400), label = "color")

    val loop = rememberInfiniteTransition(label = "loop")
    val talk by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(150), RepeatMode.Reverse), label = "talk")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
    val bob by loop.animateFloat(
        -1f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob"
    )
    val spin by loop.animateFloat(
        0f, 2f * PI.toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin"
    )
    val rise by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "rise")

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val eyeW = w * 0.17f * eyeScale
        val eyeHMax = h * 0.42f * eyeScale
        val eyeY = h * 0.40f + bob * h * 0.012f + ly * h * 0.07f
        val shiftX = lx * w * 0.08f
        val stroke = h * 0.025f
        val black = Color.Black

        // ============================================================ eyes
        for (side in listOf(-1, 1)) {
            val cx = w / 2f + side * w * 0.2f + shiftX
            val open = if (side < 0) openL else openR
            val eh = (eyeHMax * open).coerceAtLeast(h * 0.02f)

            when (emotion) {
                Emotion.DEAD -> {
                    val r = eyeW * 0.45f
                    val xs = h * 0.045f
                    drawLine(color, Offset(cx - r, eyeY - r), Offset(cx + r, eyeY + r), xs, StrokeCap.Round)
                    drawLine(color, Offset(cx - r, eyeY + r), Offset(cx + r, eyeY - r), xs, StrokeCap.Round)
                    continue
                }
                Emotion.LOVE -> {
                    heart(Offset(cx, eyeY), eyeW * (0.62f + 0.08f * pulse), color)
                    continue
                }
                Emotion.DIZZY -> {
                    spiral(Offset(cx, eyeY), eyeW * 0.5f, spin * side, color, h * 0.02f)
                    continue
                }
                else -> {}
            }

            // Normal eye
            drawRoundRect(
                color = color,
                topLeft = Offset(cx - eyeW / 2f, eyeY - eh / 2f),
                size = Size(eyeW, eh),
                cornerRadius = CornerRadius(eyeW * 0.3f, eyeW * 0.3f),
            )
            // Happy squint: cut the bottom of the eye into a smile shape.
            if (emotion == Emotion.HAPPY && eh > h * 0.1f) {
                drawOval(
                    color = black,
                    topLeft = Offset(cx - eyeW * 0.75f, eyeY + eh * 0.12f),
                    size = Size(eyeW * 1.5f, eh * 0.9f),
                )
            }
            // Shine dot: moves a little with the gaze, makes the eye look wet and alive.
            if (eh > eyeHMax * 0.35f && emotion != Emotion.HAPPY) {
                drawCircle(
                    Color.White.copy(alpha = 0.75f),
                    radius = eyeW * 0.09f,
                    center = Offset(cx - eyeW * 0.2f + lx * eyeW * 0.12f, eyeY - eh * 0.27f + ly * eh * 0.08f),
                )
            }
            // Brows
            if (abs(brow) > 0.05f) {
                val by = eyeY - eyeHMax / 2f - h * 0.07f
                val k = h * 0.06f
                val (yl, yr) = if (brow > 0f) {
                    val t = brow * k
                    if (side < 0) (by + t) to (by - t) else (by - t) to (by + t)
                } else {
                    val lift = brow * k * 1.5f
                    (by + lift) to (by + lift)
                }
                drawLine(color, Offset(cx - eyeW / 2f, yl), Offset(cx + eyeW / 2f, yr), stroke, StrokeCap.Round)
            }
        }

        // ============================================================ cheeks
        if (emotion == Emotion.HAPPY || emotion == Emotion.LOVE) {
            for (side in listOf(-1, 1)) {
                val cx = w / 2f + side * w * 0.27f + shiftX
                drawCircle(Color(0xFFFF6FA8).copy(alpha = 0.28f), radius = eyeW * 0.24f, center = Offset(cx, eyeY + eyeHMax * 0.5f))
            }
        }

        // ============================================================ mouth
        val mx = w / 2f + shiftX * 0.5f
        val my = h * 0.78f + ly * h * 0.02f
        val mw = w * 0.09f
        when {
            speaking -> {
                val mh = h * (0.02f + talk * 0.07f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(mx - mw * 0.6f, my - mh / 2f),
                    size = Size(mw * 1.2f, mh),
                    cornerRadius = CornerRadius(mh / 2f, mh / 2f),
                )
            }
            emotion.mouthO -> drawCircle(color, radius = h * 0.05f, center = Offset(mx, my), style = Stroke(stroke))
            else -> {
                val path = Path().apply {
                    moveTo(mx - mw, my)
                    quadraticBezierTo(mx, my + curve * h * 0.12f, mx + mw, my)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
        }

        // ============================================================ effects
        when (emotion) {
            Emotion.SAD -> { // falling tear from the left eye
                val tx = w / 2f - w * 0.2f + shiftX - eyeW * 0.25f
                val ty = eyeY + eyeHMax * 0.3f + rise * h * 0.4f
                tear(Offset(tx, ty), h * 0.025f, Color(0xFF64B5F6).copy(alpha = 1f - rise))
            }
            Emotion.SLEEPY -> { // Zzz rising
                for (i in 0..2) {
                    val t = (rise + i / 3f) % 1f
                    zee(
                        Offset(w * 0.78f + t * w * 0.08f, h * 0.38f - t * h * 0.3f),
                        h * (0.035f + 0.035f * t), color.copy(alpha = 1f - t), h * 0.012f,
                    )
                }
            }
            Emotion.LOVE -> { // small hearts floating up
                for (i in 0..3) {
                    val t = (rise + i / 4f) % 1f
                    val x = w * (0.12f + 0.25f * i) + sin(t * 6f + i) * w * 0.02f
                    heart(Offset(x, h * 1.0f - t * h * 0.9f), h * 0.04f, Color(0xFFFF6FA8).copy(alpha = 1f - t))
                }
            }
            Emotion.DIZZY -> { // stars circling
                for (i in 0..2) {
                    val a = spin + i * 2.1f
                    val c = Offset(w / 2f + cos(a) * w * 0.3f, h * 0.1f + sin(a) * h * 0.04f)
                    star(c, h * 0.025f, Color(0xFFFFD54F))
                }
            }
            Emotion.THINKING -> if (!speaking) {
                for (i in 0..2) {
                    val a = ((pulse + i * 0.3f) % 1f).coerceIn(0.2f, 1f)
                    drawCircle(color.copy(alpha = a), radius = h * 0.018f, center = Offset(w * 0.86f + i * h * 0.05f, h * 0.14f))
                }
            }
            else -> {}
        }

        // ============================================================ listening bars
        if (listening) {
            for (i in 0..4) {
                val bh = h * (0.02f + 0.06f * abs(sin(spin * 2f + i * 0.9f)))
                val bx = w * 0.86f + i * w * 0.018f
                drawLine(
                    Color(0xFFFF5252), Offset(bx, h * 0.9f - bh / 2f), Offset(bx, h * 0.9f + bh / 2f),
                    w * 0.008f, StrokeCap.Round,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- shapes
private fun DrawScope.heart(c: Offset, s: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y + s * 0.75f)
        cubicTo(c.x - s * 1.3f, c.y - s * 0.1f, c.x - s * 0.7f, c.y - s * 1.1f, c.x, c.y - s * 0.4f)
        cubicTo(c.x + s * 0.7f, c.y - s * 1.1f, c.x + s * 1.3f, c.y - s * 0.1f, c.x, c.y + s * 0.75f)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.spiral(c: Offset, r: Float, rot: Float, color: Color, width: Float) {
    val p = Path()
    val steps = 60
    for (k in 0..steps) {
        val t = k / steps.toFloat()
        val a = t * 3f * 2f * PI.toFloat() + rot
        val x = c.x + r * t * cos(a)
        val y = c.y + r * t * sin(a)
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    drawPath(p, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

private fun DrawScope.tear(c: Offset, r: Float, color: Color) {
    drawCircle(color, radius = r, center = c)
    val p = Path().apply {
        moveTo(c.x - r * 0.9f, c.y - r * 0.3f)
        lineTo(c.x, c.y - r * 2.2f)
        lineTo(c.x + r * 0.9f, c.y - r * 0.3f)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.zee(c: Offset, s: Float, color: Color, width: Float) {
    val l = c.x - s / 2f
    val r = c.x + s / 2f
    val t = c.y - s / 2f
    val b = c.y + s / 2f
    drawLine(color, Offset(l, t), Offset(r, t), width, StrokeCap.Round)
    drawLine(color, Offset(r, t), Offset(l, b), width, StrokeCap.Round)
    drawLine(color, Offset(l, b), Offset(r, b), width, StrokeCap.Round)
}

private fun DrawScope.star(c: Offset, r: Float, color: Color) {
    val p = Path()
    for (i in 0 until 10) {
        val rr = if (i % 2 == 0) r else r * 0.45f
        val a = -PI.toFloat() / 2f + i * PI.toFloat() / 5f
        val x = c.x + rr * cos(a)
        val y = c.y + rr * sin(a)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, color)
}
