package com.vintechafrica.jvinbuddy

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import kotlin.math.abs
import kotlin.math.hypot

/** Hand signs the robot understands. LEFT/RIGHT are sides of the camera image. */
enum class HandSign { GUN, WAVE, PALM_STOP, THUMB_UP, THUMB_DOWN, PEACE, POINT_UP, FIST, LOVE, POINT_LEFT, POINT_RIGHT }

/**
 * Reads hand signs from the front camera with MediaPipe (offline).
 * A sign must be held steady for a moment before it fires, and the same sign
 * will not fire again until the hand changes. This stops flicker and repeats.
 *
 * Finger gun, waving, pointing sideways and the stop palm are worked out from
 * the 21 hand points. The rest come from MediaPipe's built-in gestures.
 */
class HandGestures(
    context: Context,
    private val prefs: Prefs,
    private val onSign: (HandSign) -> Unit,
) {
    private val recognizer: GestureRecognizer? = try {
        val base = BaseOptions.builder()
            .setModelAssetPath("gesture_recognizer.task")
            .setDelegate(Delegate.CPU)
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        GestureRecognizer.createFromOptions(context, options)
    } catch (e: Exception) {
        Log.e(TAG, "Gesture model failed to load", e)
        null
    }

    val enabled: Boolean get() = recognizer != null && prefs.gestures
    val loaded: Boolean get() = recognizer != null

    // Steadiness and repeat control
    private var candidate: HandSign? = null
    private var candidateSince = 0L
    private var lastFired: HandSign? = null
    private var lastFiredAt = 0L
    private var lastTs = 0L

    // Wave tracking: wrist x over the last 1.5 s while the palm is open
    private val palm = ArrayDeque<Pair<Long, Float>>()

    /** Runs on the camera thread. Bitmap must already be upright. */
    fun process(bitmap: Bitmap) {
        val r = recognizer ?: return
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTs) ts = lastTs + 1 // MediaPipe needs increasing timestamps
        lastTs = ts

        val result = try {
            r.recognizeForVideo(BitmapImageBuilder(bitmap).build(), ts)
        } catch (e: Exception) {
            Log.w(TAG, "Recognize failed: ${e.message}")
            return
        }

        val hands = result.landmarks()
        if (hands.isEmpty()) {
            reset()
            return
        }
        val lm = hands[0]
        val canned = result.gestures().firstOrNull()?.firstOrNull()
            ?.takeIf { it.score() > 0.5f }?.categoryName()

        val sign = classify(lm, canned, ts)
        if (sign == null) {
            candidate = null
            return
        }
        settle(sign, ts)
    }

    private fun classify(lm: List<NormalizedLandmark>, canned: String?, ts: Long): HandSign? {
        fun d(a: Int, b: Int) = hypot(lm[a].x() - lm[b].x(), lm[a].y() - lm[b].y())
        fun extended(tip: Int, pip: Int) = d(0, tip) > d(0, pip) * 1.1f

        val handSize = d(0, 9).coerceAtLeast(0.01f)
        val index = extended(8, 6)
        val middle = extended(12, 10)
        val ring = extended(16, 14)
        val pinky = extended(20, 18)
        val thumbOut = d(4, 9) > handSize * 0.65f

        // Only the index finger out: finger gun or pointing.
        if (index && !middle && !ring && !pinky) {
            palm.clear()
            if (thumbOut) return HandSign.GUN
            val vx = lm[8].x() - lm[5].x()
            val vy = lm[8].y() - lm[5].y()
            return when {
                abs(vx) > abs(vy) * 1.2f -> if (vx > 0) HandSign.POINT_RIGHT else HandSign.POINT_LEFT
                vy < 0 -> HandSign.POINT_UP
                else -> null
            }
        }

        if (canned == "Open_Palm" || (index && middle && ring && pinky)) {
            return palmSign(lm[0].x(), ts)
        }
        palm.clear()

        return when (canned) {
            "Thumb_Up" -> HandSign.THUMB_UP
            "Thumb_Down" -> HandSign.THUMB_DOWN
            "Victory" -> HandSign.PEACE
            "Pointing_Up" -> HandSign.POINT_UP
            "Closed_Fist" -> HandSign.FIST
            "ILoveYou" -> HandSign.LOVE
            else -> null
        }
    }

    /** Open palm moving side to side = wave. Open palm held still = stop. */
    private fun palmSign(x: Float, ts: Long): HandSign? {
        palm.addLast(ts to x)
        while (palm.isNotEmpty() && ts - palm.first().first > 1500) palm.removeFirst()

        var reversals = 0
        var dir = 0
        var anchor = palm.first().second
        for ((_, px) in palm) {
            val delta = px - anchor
            if (abs(delta) >= 0.04f) {
                val newDir = if (delta > 0) 1 else -1
                if (dir != 0 && newDir != dir) reversals++
                dir = newDir
                anchor = px
            }
        }
        if (reversals >= 2) return HandSign.WAVE

        val span = ts - palm.first().first
        val range = palm.maxOf { it.second } - palm.minOf { it.second }
        if (span >= 900 && range < 0.05f) return HandSign.PALM_STOP
        return null
    }

    private fun settle(sign: HandSign, ts: Long) {
        if (sign != candidate) {
            candidate = sign
            candidateSince = ts
            return
        }
        val holdMs = when (sign) {
            HandSign.GUN -> 250L
            HandSign.WAVE, HandSign.PALM_STOP -> 0L // already time-based
            HandSign.POINT_LEFT, HandSign.POINT_RIGHT -> 500L
            else -> 400L
        }
        if (ts - candidateSince < holdMs) return
        if (sign == lastFired) return            // hand must change first
        if (ts - lastFiredAt < 1500) return      // global cooldown
        lastFired = sign
        lastFiredAt = ts
        if (sign == HandSign.WAVE) palm.clear()
        onSign(sign)
    }

    private fun reset() {
        candidate = null
        palm.clear()
        // Hand left the frame: allow the same sign again next time.
        lastFired = null
    }

    fun shutdown() {
        recognizer?.close()
    }

    companion object { private const val TAG = "HandGestures" }
}
