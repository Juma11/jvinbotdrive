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
 * Emo-style animated face.
 * Eyes are glowing rounded blocks. Black eyelids slide down, tilt and push up
 * from below to make each expression, and every change morphs smoothly.
 * Eyes follow your face or hand (left/right and up/down), blink, wink, and
 * each emotion has its own extras: tears, Zzz, hearts, stars, notes, steam.
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
            delay(120)
            blink = false
            if (Random.nextInt(5) == 0) {
                delay(150); blink = true; delay(100); blink = false
            }
        }
    }
    var winking by remember { mutableStateOf(false) }
    LaunchedEffect(winkAt) {
        if (winkAt > 0L) {
            winking = true; delay(450); winking = false
        }
    }

    // ---------------------------------------------------------------- animated values
    val slow = tween<Float>(260)
    val curiousTilt = if (emotion == Emotion.CURIOUS) 0.8f else 1f
    val openL by animateFloatAsState(if (blink) 0.06f else emotion.eyeOpen, tween(if (blink) 70 else 260), label = "openL")
    val openR by animateFloatAsState(
        if (blink || winking) 0.06f else emotion.eyeOpen * curiousTilt, tween(if (blink || winking) 70 else 260), label = "openR"
    )
    val lidTop by animateFloatAsState(emotion.lidTop, slow, label = "lidTop")
    val lidTilt by animateFloatAsState(emotion.lidTilt, slow, label = "lidTilt")
    val lidBottom by animateFloatAsState(emotion.lidBottom, slow, label = "lidBottom")
    val eyeScale by animateFloatAsState(
        when (emotion) {
            Emotion.SURPRISED -> 1.18f
            Emotion.EXCITED -> 1.1f
            Emotion.SCARED -> 0.85f
            else -> 1f
        },
        spring(dampingRatio = 0.4f), label = "scale",
    )
    val targetX = when (emotion) {
        Emotion.THINKING -> 0.6f
        Emotion.SHY -> if (lookX >= 0f) 0.5f else -0.5f
        else -> lookX
    }
    val targetY = when (emotion) {
        Emotion.THINKING -> -0.6f
        Emotion.SHY, Emotion.SAD, Emotion.CRYING -> 0.5f
        else -> lookY
    }
    val lx by animateFloatAsState(targetX.coerceIn(-1f, 1f), spring(0.75f, 140f), label = "lx")
    val ly by animateFloatAsState(targetY.coerceIn(-1f, 1f), spring(0.75f, 140f), label = "ly")
    val curve by animateFloatAsState(emotion.mouthCurve, slow, label = "curve")
    val brow by animateFloatAsState(emotion.browTilt, slow, label = "brow")
    val color by animateColorAsState(Color(emotion.color), tween(400), label = "color")

    val loop = rememberInfiniteTransition(label = "loop")
    val talk by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(150), RepeatMode.Reverse), label = "talk")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
    val bob by loop.animateFloat(
        -1f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob"
    )
    val spin by loop.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    val rise by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "rise")
    val fast by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "fast")

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val black = Color.Black

        // Whole-face motion per emotion
        val jitterX = if (emotion == Emotion.SCARED) sin(spin * 20f) * w * 0.005f else 0f
        val bounceY = if (emotion == Emotion.LAUGH) -abs(sin(spin * 3f)) * h * 0.04f else 0f

        val eyeW = w * 0.17f * eyeScale
        val eyeHMax = h * 0.42f * eyeScale
        val eyeY = h * 0.40f + bob * h * 0.012f + ly * h * 0.07f + bounceY
        val shiftX = lx * w * 0.08f + jitterX
        val stroke = h * 0.025f

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
                Emotion.LOVE -> { heart(Offset(cx, eyeY), eyeW * (0.62f + 0.08f * pulse), color); continue }
                Emotion.DIZZY -> { spiral(Offset(cx, eyeY), eyeW * 0.5f, spin * side, color, h * 0.02f); continue }
                Emotion.EXCITED -> { star(Offset(cx, eyeY), eyeW * (0.62f + 0.1f * pulse), color); continue }
                else -> {}
            }

            val top = eyeY - eh / 2f
            val bottom = eyeY + eh / 2f
            // Soft glow, then the eye
            drawRoundRect(
                color.copy(alpha = 0.12f),
                topLeft = Offset(cx - eyeW * 0.6f, top - eyeW * 0.1f),
                size = Size(eyeW * 1.2f, eh + eyeW * 0.2f),
                cornerRadius = CornerRadius(eyeW * 0.4f, eyeW * 0.4f),
            )
            drawRoundRect(
                color,
                topLeft = Offset(cx - eyeW / 2f, top),
                size = Size(eyeW, eh),
                cornerRadius = CornerRadius(eyeW * 0.3f, eyeW * 0.3f),
            )
            // Shine dot (lids may cover it)
            if (eh > eyeHMax * 0.35f) {
                drawCircle(
                    Color.White.copy(alpha = 0.7f), radius = eyeW * 0.09f,
                    center = Offset(cx - eyeW * 0.2f + lx * eyeW * 0.12f, top + eh * 0.23f + ly * eh * 0.08f),
                )
            }

            // Top lid: comes down and tilts. Inner corner = side facing the nose.
            if (lidTop > 0.01f || abs(lidTilt) > 0.01f) {
                val base = top + eh * lidTop
                val tiltOff = lidTilt * eh * 0.35f
                val yInner = base + tiltOff
                val yOuter = base - tiltOff
                val leftY = if (side < 0) yOuter else yInner
                val rightY = if (side < 0) yInner else yOuter
                val lid = Path().apply {
                    moveTo(cx - eyeW, top - eh)
                    lineTo(cx + eyeW, top - eh)
                    lineTo(cx + eyeW, rightY)
                    lineTo(cx - eyeW, leftY)
                    close()
                }
                drawPath(lid, black)
            }
            // Bottom lid: curved, pushes up = smiling eyes
            if (lidBottom > 0.01f) {
                drawOval(
                    black,
                    topLeft = Offset(cx - eyeW * 0.8f, bottom - eh * lidBottom),
                    size = Size(eyeW * 1.6f, eh * 1.2f),
                )
            }

            // Raised brows (surprised, curious, scared)
            if (brow < -0.05f) {
                val by = eyeY - eyeHMax / 2f - h * 0.07f + brow * h * 0.09f
                drawLine(color, Offset(cx - eyeW / 2f, by), Offset(cx + eyeW / 2f, by), stroke, StrokeCap.Round)
            }
        }

        // ============================================================ cheeks
        val blush = when (emotion) {
            Emotion.SHY -> 0.55f
            Emotion.HAPPY, Emotion.LOVE, Emotion.SINGING, Emotion.LAUGH -> 0.28f
            else -> 0f
        }
        if (blush > 0f) {
            for (side in listOf(-1, 1)) {
                val cx = w / 2f + side * w * 0.27f + shiftX
                drawCircle(Color(0xFFFF6FA8).copy(alpha = blush), radius = eyeW * 0.24f, center = Offset(cx, eyeY + eyeHMax * 0.5f))
            }
        }

        // ============================================================ mouth
        val mx = w / 2f + shiftX * 0.5f
        val my = h * 0.78f + ly * h * 0.02f + bounceY
        val mw = w * 0.09f
        when {
            speaking -> {
                val mh = h * (0.02f + talk * 0.07f)
                drawRoundRect(
                    color, topLeft = Offset(mx - mw * 0.6f, my - mh / 2f), size = Size(mw * 1.2f, mh),
                    cornerRadius = CornerRadius(mh / 2f, mh / 2f),
                )
            }
            emotion == Emotion.LAUGH || emotion == Emotion.EXCITED -> { // big open grin
                val grin = Path().apply {
                    moveTo(mx - mw * 1.1f, my - h * 0.02f)
                    lineTo(mx + mw * 1.1f, my - h * 0.02f)
                    quadraticBezierTo(mx, my + h * (0.14f + 0.03f * talk), mx - mw * 1.1f, my - h * 0.02f)
                    close()
                }
                drawPath(grin, color)
            }
            emotion.mouthO -> drawCircle(
                color, radius = h * if (emotion == Emotion.SCARED) 0.035f else 0.05f,
                center = Offset(mx, my), style = Stroke(stroke),
            )
            emotion == Emotion.ANGRY -> { // gritted zigzag
                val z = Path().apply {
                    moveTo(mx - mw, my)
                    for (i in 1..6) lineTo(mx - mw + i * mw / 3f, my + if (i % 2 == 1) -h * 0.02f else h * 0.02f)
                }
                drawPath(z, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
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
            Emotion.SAD -> {
                val tx = w / 2f - w * 0.2f + shiftX - eyeW * 0.25f
                tear(Offset(tx, eyeY + eyeHMax * 0.3f + rise * h * 0.4f), h * 0.025f, Color(0xFF64B5F6).copy(alpha = 1f - rise))
            }
            Emotion.CRYING -> { // streams of tears from both eyes
                for (side in listOf(-1, 1)) {
                    for (k in 0..1) {
                        val t = (fast + k * 0.5f + if (side > 0) 0.25f else 0f) % 1f
                        val tx = w / 2f + side * w * 0.2f + shiftX + side * eyeW * 0.3f
                        tear(Offset(tx, eyeY + eyeHMax * 0.2f + t * h * 0.45f), h * 0.022f, Color(0xFF64B5F6).copy(alpha = 1f - t))
                    }
                }
            }
            Emotion.SLEEPY -> {
                for (i in 0..2) {
                    val t = (rise + i / 3f) % 1f
                    zee(Offset(w * 0.78f + t * w * 0.08f, h * 0.38f - t * h * 0.3f), h * (0.035f + 0.035f * t), color.copy(alpha = 1f - t), h * 0.012f)
                }
            }
            Emotion.LOVE -> {
                for (i in 0..3) {
                    val t = (rise + i / 4f) % 1f
                    val x = w * (0.12f + 0.25f * i) + sin(t * 6f + i) * w * 0.02f
                    heart(Offset(x, h - t * h * 0.9f), h * 0.04f, Color(0xFFFF6FA8).copy(alpha = 1f - t))
                }
            }
            Emotion.SINGING -> {
                for (i in 0..3) {
                    val t = (rise + i / 4f) % 1f
                    val x = (if (i % 2 == 0) w * 0.08f else w * 0.88f) + sin(t * 8f + i) * w * 0.03f
                    musicNote(Offset(x, h * 0.95f - t * h * 0.85f), h * 0.05f, color.copy(alpha = 1f - t))
                }
            }
            Emotion.DIZZY -> {
                for (i in 0..2) {
                    val a = spin + i * 2.1f
                    star(Offset(w / 2f + cos(a) * w * 0.3f, h * 0.1f + sin(a) * h * 0.04f), h * 0.025f, Color(0xFFFFD54F))
                }
            }
            Emotion.EXCITED -> { // twinkling sparkles
                val spots = listOf(0.08f to 0.15f, 0.92f to 0.2f, 0.12f to 0.8f, 0.88f to 0.75f, 0.5f to 0.08f)
                spots.forEachIndexed { i, (sx, sy) ->
                    val a = (0.5f + 0.5f * sin(spin * 2f + i * 1.3f)).coerceIn(0f, 1f)
                    star(Offset(w * sx, h * sy), h * 0.03f, Color(0xFFFFD740).copy(alpha = a))
                }
            }
            Emotion.ANGRY -> { // steam puffs from the top corners
                for (side in listOf(-1, 1)) {
                    for (k in 0..2) {
                        val t = (fast + k / 3f) % 1f
                        drawCircle(
                            Color.LightGray.copy(alpha = (1f - t) * 0.6f), radius = h * (0.015f + 0.03f * t),
                            center = Offset(w / 2f + side * w * (0.38f + 0.05f * t), h * 0.12f - t * h * 0.1f),
                        )
                    }
                }
            }
            Emotion.SCARED -> { // sweat drop
                val t = rise
                tear(Offset(w / 2f + w * 0.33f + shiftX, h * 0.18f + t * h * 0.15f), h * 0.02f, Color(0xFF81D4FA).copy(alpha = 1f - t))
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
                drawLine(Color(0xFFFF5252), Offset(bx, h * 0.9f - bh / 2f), Offset(bx, h * 0.9f + bh / 2f), w * 0.008f, StrokeCap.Round)
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

private fun DrawScope.musicNote(c: Offset, s: Float, color: Color) {
    drawOval(color, topLeft = Offset(c.x - s * 0.55f, c.y - s * 0.35f), size = Size(s * 1.1f, s * 0.75f))
    val stemX = c.x + s * 0.5f
    drawLine(color, Offset(stemX, c.y), Offset(stemX, c.y - s * 1.9f), s * 0.18f, StrokeCap.Round)
    drawLine(color, Offset(stemX, c.y - s * 1.9f), Offset(stemX + s * 0.7f, c.y - s * 1.4f), s * 0.18f, StrokeCap.Round)
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
