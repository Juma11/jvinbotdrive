package com.vintechafrica.jvinbuddy

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random

/** Cartoon robot face: two glowing eyes, brows, a mouth. Everything animates between emotions. */
@Composable
fun RobotFace(
    emotion: Emotion,
    lookX: Float,
    listening: Boolean,
    speaking: Boolean,
    modifier: Modifier = Modifier,
) {
    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2500, 6000))
            blink = true
            delay(130)
            blink = false
        }
    }

    val open by animateFloatAsState(if (blink) 0.06f else emotion.eyeOpen, tween(if (blink) 80 else 300), label = "open")
    val curve by animateFloatAsState(emotion.mouthCurve, tween(300), label = "curve")
    val brow by animateFloatAsState(emotion.browTilt, tween(300), label = "brow")
    val look by animateFloatAsState(lookX.coerceIn(-1f, 1f), tween(220), label = "look")
    val color by animateColorAsState(Color(emotion.color), tween(400), label = "color")

    val loop = rememberInfiniteTransition(label = "loop")
    val talk by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(150), RepeatMode.Reverse), label = "talk")
    val pulse by loop.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
    val bob by loop.animateFloat(
        -1f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob"
    )

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val eyeW = w * 0.17f
        val eyeHMax = h * 0.42f
        val eyeY = h * 0.40f + bob * h * 0.012f
        val shift = look * w * 0.06f
        val stroke = h * 0.025f

        // Eyes and brows
        for (side in listOf(-1, 1)) {
            val cx = w / 2f + side * w * 0.2f + shift
            val eh = (eyeHMax * open).coerceAtLeast(h * 0.02f)
            if (emotion == Emotion.DEAD) {
                val r = eyeW * 0.45f
                val xStroke = h * 0.045f
                drawLine(color, Offset(cx - r, eyeY - r), Offset(cx + r, eyeY + r), xStroke, StrokeCap.Round)
                drawLine(color, Offset(cx - r, eyeY + r), Offset(cx + r, eyeY - r), xStroke, StrokeCap.Round)
                continue
            }
            if (emotion == Emotion.LOVE) {
                val r = eyeW * 0.26f
                drawCircle(color, r, Offset(cx - r * 0.95f, eyeY - r * 0.4f))
                drawCircle(color, r, Offset(cx + r * 0.95f, eyeY - r * 0.4f))
                val heart = Path().apply {
                    moveTo(cx - r * 1.93f, eyeY - r * 0.15f)
                    lineTo(cx + r * 1.93f, eyeY - r * 0.15f)
                    lineTo(cx, eyeY + r * 2.0f)
                    close()
                }
                drawPath(heart, color)
                continue
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(cx - eyeW / 2f, eyeY - eh / 2f),
                size = Size(eyeW, eh),
                cornerRadius = CornerRadius(eyeW * 0.3f, eyeW * 0.3f),
            )

            if (abs(brow) > 0.05f) {
                val by = eyeY - eyeHMax / 2f - h * 0.07f
                val k = h * 0.06f
                val (yLeftEnd, yRightEnd) = if (brow > 0f) {
                    // Sad or worried: inner ends up.
                    val t = brow * k
                    if (side < 0) (by + t) to (by - t) else (by - t) to (by + t)
                } else {
                    // Surprised or curious: both brows raised, flat.
                    val lift = brow * k * 1.5f
                    (by + lift) to (by + lift)
                }
                drawLine(
                    color = color,
                    start = Offset(cx - eyeW / 2f, yLeftEnd),
                    end = Offset(cx + eyeW / 2f, yRightEnd),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }

        // Mouth
        val mx = w / 2f + shift * 0.5f
        val my = h * 0.78f
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

        // Thinking dots
        if (emotion == Emotion.THINKING && !speaking) {
            for (i in 0..2) {
                val a = ((pulse + i * 0.3f) % 1f).coerceIn(0.2f, 1f)
                drawCircle(color.copy(alpha = a), radius = h * 0.018f, center = Offset(w * 0.86f + i * h * 0.05f, h * 0.14f))
            }
        }

        // Listening indicator
        if (listening) {
            drawCircle(Color(0xFFFF5252).copy(alpha = pulse), radius = h * 0.03f, center = Offset(w * 0.93f, h * 0.88f))
        }
    }
}
