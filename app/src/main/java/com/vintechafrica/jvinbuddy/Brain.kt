package com.vintechafrica.jvinbuddy

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Decides what the robot feels and how it moves.
 * Movement rules:
 *  - A scripted move (wiggle, spin...) runs first and blocks everything else.
 *  - Otherwise, if following is on and a fresh face is visible, steer toward it.
 *  - Otherwise stand still.
 * Emotion rules come from real events: faces, timers, bumps, battery, voice.
 */
class Brain(
    private val link: RobotLink,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val speak: (String, Emotion) -> Unit,
) {
    val emotion = MutableStateFlow(Emotion.NEUTRAL)
    val lookX = MutableStateFlow(0f)
    val follow = MutableStateFlow(true)

    /** True while listening, so motor noise does not drown out the mic. */
    @Volatile var paused = false

    private var face: FaceInfo? = null
    private var faceAt = 0L
    private var lastFaceSeen = SystemClock.elapsedRealtime()
    private var emotionLockedUntil = 0L
    private var moveJob: Job? = null
    private var lastDriveMag = 0
    private var lastLowBatteryWarn = 0L
    private var asleep = false

    // ---------------------------------------------------------------- tuning
    private val turnGain = 170f       // PWM per unit of face offset
    private val deadband = 0.12f      // ignore small offsets so it does not jitter
    private val forwardSpeed = 150
    private val farSize = 0.16f       // face smaller than this: drive closer
    private val nearSize = 0.34f      // face bigger than this: back off
    private val boredAfterMs = 30_000L
    private val sleepAfterMs = 120_000L

    fun start() {
        scope.launch {
            while (isActive) {
                tick()
                delay(100)
            }
        }
    }

    // ---------------------------------------------------------------- inputs
    fun onFace(info: FaceInfo?) {
        val now = SystemClock.elapsedRealtime()
        if (info != null) {
            val gone = now - lastFaceSeen
            face = info
            faceAt = now
            lastFaceSeen = now
            lookX.value = if (prefs.invertSteer) -info.x else info.x
            if (asleep) return
            if (gone > 10_000) {
                setEmotion(Emotion.CURIOUS, 1500)
                scope.launch {
                    delay(1500)
                    setEmotion(Emotion.HAPPY, 3000)
                    perform(Move.WIGGLE)
                }
            }
        } else {
            face = null
            lookX.value = lookX.value * 0.9f
        }
    }

    fun onBump() {
        if (lastDriveMag > 120 || asleep) return
        setEmotion(Emotion.SURPRISED, 2500)
        perform(Move.BACK_OFF)
        speak("Whoa!", Emotion.SURPRISED)
    }

    fun onBattery(volts: Float?) {
        if (volts == null) return
        val now = SystemClock.elapsedRealtime()
        if (volts < prefs.lowBattery && now - lastLowBatteryWarn > 5 * 60_000) {
            lastLowBatteryWarn = now
            setEmotion(Emotion.TIRED, 8000)
            speak("My battery is low. Please charge me soon.", Emotion.TIRED)
        }
    }

    fun onTap(): Boolean {
        if (asleep) {
            wake()
            return true
        }
        return false
    }

    /** Local voice commands. Returns true if handled, false to send to the AI. */
    fun handleCommand(raw: String): Boolean {
        val t = raw.lowercase()
        fun has(vararg words: String) = words.any { t.contains(it) }
        when {
            has("stop", "stay", "wait there") -> {
                follow.value = false; cancelMove(); link.stop()
                react(Emotion.NEUTRAL, "Okay, I'll stay here.")
            }
            has("follow me", "come here", "come to me", "follow") -> {
                follow.value = true; asleep = false
                react(Emotion.HAPPY, "Coming!"); perform(Move.WIGGLE)
            }
            has("turn left") -> { react(Emotion.NEUTRAL, "Left."); perform(Move.TURN_LEFT) }
            has("turn right") -> { react(Emotion.NEUTRAL, "Right."); perform(Move.TURN_RIGHT) }
            has("spin", "dance") -> { react(Emotion.HAPPY, "Watch this!"); perform(Move.SPIN) }
            has("good robot", "good boy", "good girl", "well done", "good job") -> {
                react(Emotion.HAPPY, "Thank you! That makes me happy."); perform(Move.WIGGLE)
            }
            has("bad robot", "go away", "shut up") -> {
                follow.value = false
                react(Emotion.SAD, "Okay. I'll give you some space."); perform(Move.TURN_AWAY)
            }
            has("go to sleep", "sleep") -> {
                follow.value = false; cancelMove(); link.stop(); asleep = true
                react(Emotion.SLEEPY, "Goodnight.", 60 * 60_000)
            }
            has("wake up") -> wake()
            else -> return false
        }
        return true
    }

    fun thinking() {
        setEmotion(Emotion.THINKING, 30_000)
        perform(Move.SHAKE)
    }

    fun answered(e: Emotion, reply: String) {
        react(e, reply)
        when (e) {
            Emotion.HAPPY -> perform(Move.WIGGLE)
            Emotion.SURPRISED -> perform(Move.BACK_OFF)
            Emotion.SAD -> perform(Move.TURN_AWAY_SMALL)
            else -> {}
        }
    }

    // ---------------------------------------------------------------- internals
    private fun wake() {
        asleep = false
        lastFaceSeen = SystemClock.elapsedRealtime()
        react(Emotion.HAPPY, "I'm awake!")
        perform(Move.WIGGLE)
    }

    private fun react(e: Emotion, text: String, holdMs: Long = 5000) {
        setEmotion(e, holdMs)
        speak(text, e)
    }

    fun setEmotion(e: Emotion, holdMs: Long) {
        emotion.value = e
        emotionLockedUntil = SystemClock.elapsedRealtime() + holdMs
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()

        // Idle emotions.
        if (now > emotionLockedUntil && !asleep) {
            val since = now - lastFaceSeen
            emotion.value = when {
                since > sleepAfterMs -> Emotion.SLEEPY
                since > boredAfterMs -> Emotion.BORED
                face != null -> Emotion.HAPPY
                else -> Emotion.NEUTRAL
            }
        }
        if (asleep) emotion.value = Emotion.SLEEPY

        if (moveJob?.isActive == true) return

        val f = face
        val fresh = f != null && now - faceAt < 500
        val sleepy = asleep || emotion.value == Emotion.SLEEPY
        if (paused || !follow.value || !fresh || sleepy) {
            drive(0, 0)
            return
        }

        // Positive offset = face is toward the robot's left (screen right as you look at it).
        var offset = f!!.x
        if (prefs.invertSteer) offset = -offset
        val turn = if (abs(offset) < deadband) 0 else (offset * turnGain).toInt()
        val fwd = when {
            f.size < farSize -> forwardSpeed
            f.size > nearSize -> -forwardSpeed
            else -> 0
        }
        // Turn in place when the face is far to one side.
        val fwdScaled = if (abs(offset) > 0.5f) 0 else fwd
        drive(fwdScaled - turn, fwdScaled + turn)
    }

    private fun drive(l: Int, r: Int) {
        lastDriveMag = maxOf(abs(l), abs(r))
        link.setDrive(l, r)
    }

    private fun cancelMove() {
        moveJob?.cancel()
        moveJob = null
    }

    fun perform(move: Move) {
        cancelMove()
        moveJob = scope.launch {
            for (step in move.steps) {
                drive(step.l, step.r)
                delay(step.ms)
            }
            drive(0, 0)
        }
    }

    data class Step(val l: Int, val r: Int, val ms: Long)

    enum class Move(val steps: List<Step>) {
        WIGGLE(List(4) { i -> if (i % 2 == 0) Step(170, -170, 140) else Step(-170, 170, 140) }),
        SHAKE(listOf(Step(140, -140, 120), Step(-140, 140, 120))),
        SPIN(listOf(Step(220, -220, 1300))),
        BACK_OFF(listOf(Step(-180, -180, 450))),
        TURN_LEFT(listOf(Step(-180, 180, 500))),
        TURN_RIGHT(listOf(Step(180, -180, 500))),
        TURN_AWAY(listOf(Step(200, -200, 750))),
        TURN_AWAY_SMALL(listOf(Step(170, -170, 300))),
    }
}
