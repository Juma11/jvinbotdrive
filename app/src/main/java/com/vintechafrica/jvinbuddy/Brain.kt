package com.vintechafrica.jvinbuddy

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * The pet's personality: what it feels, how it moves, how it reacts.
 * Movement priority:
 *  1. A trick or reaction (wiggle, spin, play dead...) runs first.
 *  2. Otherwise, if following is on and a face is visible, steer toward it.
 *  3. Otherwise small idle life: looking around, peeking, yawning.
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

    /** True while listening for a question, so the motors stay quiet. */
    @Volatile var paused = false

    private var face: FaceInfo? = null
    private var faceAt = 0L
    private var lastFaceSeen = SystemClock.elapsedRealtime()
    private var emotionLockedUntil = 0L
    private var moveJob: Job? = null
    private var deadJob: Job? = null
    private var lastDriveMag = 0
    private var lastLowBatteryWarn = 0L
    private var asleep = false
    private var dead = false
    private var nextIdleAt = SystemClock.elapsedRealtime() + 8_000
    private var idleLookUntil = 0L

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

    // ================================================================ inputs
    fun onFace(info: FaceInfo?) {
        val now = SystemClock.elapsedRealtime()
        if (info != null) {
            val gone = now - lastFaceSeen
            face = info
            faceAt = now
            lastFaceSeen = now
            if (now > idleLookUntil) lookX.value = if (prefs.invertSteer) -info.x else info.x
            if (asleep || dead) return
            if (gone > 10_000) {
                setEmotion(Emotion.CURIOUS, 1200)
                scope.launch {
                    delay(1200)
                    setEmotion(Emotion.HAPPY, 3000)
                    perform(Move.WIGGLE)
                    if (gone > 60_000) speak(listOf("Hello!", "There you are!", "Hi Vincent!").random(), Emotion.HAPPY)
                }
            }
        } else {
            face = null
            if (now > idleLookUntil) lookX.value = lookX.value * 0.9f
        }
    }

    fun onBump() {
        if (lastDriveMag > 120 || asleep || dead) return
        setEmotion(Emotion.SURPRISED, 2500)
        perform(Move.BACK_OFF)
        speak(listOf("Whoa!", "Hey!", "Careful!").random(), Emotion.SURPRISED)
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

    /** Tap on the face = petting. */
    fun pet() {
        when {
            asleep -> wake()
            dead -> revive()
            else -> {
                setEmotion(Emotion.HAPPY, 3000)
                perform(Move.WIGGLE_SMALL)
                speak(listOf("Hehe!", "That tickles!", "I like that.", "More please!").random(), Emotion.HAPPY)
            }
        }
    }

    /** Called the instant "stop" is heard, before the sentence ends. */
    fun urgentStop() {
        cancelMove()
        drive(0, 0)
        follow.value = false
    }

    fun listeningForQuestion() {
        cancelMove()
        drive(0, 0)
        setEmotion(Emotion.CURIOUS, 8000)
    }

    // ================================================================ commands
    /** Returns true if handled, false to send to the AI. */
    fun handleCommand(raw: String): Boolean {
        val t = Commands.normalize(raw)
        if (t.isEmpty()) return false
        fun has(vararg phrases: String) = phrases.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }

        // Shooting works even when it is already "dead" (shoot it again).
        if (has("bang", "shoot", "play dead")) {
            playDead(); return true
        }
        if (has("wake up")) {
            wake(); return true
        }
        if (dead) revive()
        if (asleep && !has("go to sleep", "sleep", "lie down")) wake()

        when {
            has("stop", "freeze", "sit", "stay", "wait") -> {
                urgentStop()
                react(Emotion.NEUTRAL, if (has("sit")) "Sitting." else listOf("Okay.", "Staying.").random())
            }
            has("go away", "shut up") -> {
                follow.value = false
                react(Emotion.SAD, "Okay. I'll give you some space.")
                perform(Move.TURN_AWAY)
            }
            has("go round", "go around", "turn around") -> {
                react(Emotion.HAPPY, "Round I go!"); perform(Move.HALF_TURN)
            }
            has("spin", "roll over") -> {
                react(Emotion.HAPPY, "Wheee!"); perform(Move.SPIN)
            }
            has("go back", "move back", "back up", "reverse") -> {
                follow.value = false
                react(Emotion.NEUTRAL, "Backing up."); perform(Move.BACK)
            }
            has("go forward", "move forward", "forward", "go ahead") -> {
                react(Emotion.NEUTRAL, "Going."); perform(Move.FORWARD)
            }
            has("turn left", "go left") -> {
                react(Emotion.NEUTRAL, "Left."); perform(Move.TURN_LEFT)
            }
            has("turn right", "go right") -> {
                react(Emotion.NEUTRAL, "Right."); perform(Move.TURN_RIGHT)
            }
            has("come", "here boy", "follow") -> {
                follow.value = true
                react(Emotion.HAPPY, listOf("Coming!", "On my way!").random())
                if (!faceFresh()) perform(Move.FORWARD) else perform(Move.WIGGLE_SMALL)
            }
            has("look at me", "watch me") -> {
                follow.value = true
                react(Emotion.CURIOUS, "I'm watching you.")
            }
            has("dance") -> {
                react(Emotion.HAPPY, "Let's dance!"); perform(Move.DANCE)
            }
            has("jump") -> {
                react(Emotion.SURPRISED, "I can't jump, I have wheels! But watch this."); perform(Move.HOP)
            }
            has("speak", "bark") -> {
                react(Emotion.HAPPY, "Woof! Woof!"); perform(Move.HOP)
            }
            has("shake", "high five", "say hello") -> {
                react(Emotion.HAPPY, "Hi there!"); perform(Move.WIGGLE)
            }
            has("fetch") -> {
                react(Emotion.SAD, "I don't have arms yet. Ask Vincent to build me some.")
            }
            has("good boy", "good girl", "good dog", "good robot", "well done", "good job") -> {
                react(Emotion.HAPPY, listOf("Thank you!", "Yay!", "I'm a good robot!").random())
                perform(Move.WIGGLE)
            }
            has("bad dog", "bad boy", "bad robot") || t == "no" -> {
                react(Emotion.SAD, listOf("Sorry.", "Aww.", "I'll be good.").random())
                perform(Move.TURN_AWAY_SMALL)
            }
            has("go to sleep", "sleep", "lie down") -> {
                urgentStop(); asleep = true
                react(Emotion.SLEEPY, "Goodnight.", 60 * 60_000)
            }
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
            Emotion.HAPPY -> perform(Move.WIGGLE_SMALL)
            Emotion.SURPRISED -> perform(Move.BACK_OFF)
            Emotion.SAD -> perform(Move.TURN_AWAY_SMALL)
            else -> {}
        }
    }

    // ================================================================ tricks
    private fun playDead() {
        deadJob?.cancel()
        dead = true
        asleep = false
        setEmotion(Emotion.DEAD, 6000)
        speak(listOf("Argh! You got me!", "Ouch! I'm hit!", "Noooo!").random(), Emotion.DEAD)
        perform(Move.DIE)
        deadJob = scope.launch {
            delay(5000)
            if (dead) {
                dead = false
                setEmotion(Emotion.HAPPY, 3000)
                speak("Just kidding!", Emotion.HAPPY)
                perform(Move.WIGGLE)
            }
        }
    }

    private fun revive() {
        deadJob?.cancel()
        dead = false
        setEmotion(Emotion.HAPPY, 2000)
    }

    private fun wake() {
        asleep = false
        lastFaceSeen = SystemClock.elapsedRealtime()
        react(Emotion.HAPPY, "I'm awake!")
        perform(Move.WIGGLE)
    }

    private fun react(e: Emotion, text: String, holdMs: Long = 4000) {
        setEmotion(e, holdMs)
        speak(text, e)
    }

    fun setEmotion(e: Emotion, holdMs: Long) {
        emotion.value = e
        emotionLockedUntil = SystemClock.elapsedRealtime() + holdMs
    }

    private fun faceFresh() = face != null && SystemClock.elapsedRealtime() - faceAt < 500

    // ================================================================ main loop
    private fun tick() {
        val now = SystemClock.elapsedRealtime()

        // Mood from what is happening.
        if (dead) {
            emotion.value = Emotion.DEAD
        } else if (asleep) {
            emotion.value = Emotion.SLEEPY
        } else if (now > emotionLockedUntil) {
            val since = now - lastFaceSeen
            emotion.value = when {
                since > sleepAfterMs -> Emotion.SLEEPY
                since > boredAfterMs -> Emotion.BORED
                face != null -> Emotion.HAPPY
                else -> Emotion.NEUTRAL
            }
        }

        if (moveJob?.isActive == true) return

        val sleepy = asleep || dead || emotion.value == Emotion.SLEEPY
        if (paused || sleepy) {
            drive(0, 0)
            return
        }

        val f = face
        if (follow.value && f != null && faceFresh()) {
            followFace(f)
            return
        }
        drive(0, 0)
        idleLife(now)
    }

    private fun followFace(f: FaceInfo) {
        // Positive offset = face toward the robot's left (screen right as you look at it).
        var offset = f.x
        if (prefs.invertSteer) offset = -offset
        val turn = if (abs(offset) < deadband) 0 else (offset * turnGain).toInt()
        val fwd = when {
            f.size < farSize -> forwardSpeed
            f.size > nearSize -> -forwardSpeed
            else -> 0
        }
        val fwdScaled = if (abs(offset) > 0.5f) 0 else fwd
        drive(fwdScaled - turn, fwdScaled + turn)
    }

    /** Small things a pet does on its own so it never looks frozen. */
    private fun idleLife(now: Long) {
        if (now < nextIdleAt) return
        nextIdleAt = now + Random.nextLong(7_000, 18_000)
        val bored = emotion.value == Emotion.BORED
        when (Random.nextInt(100)) {
            in 0..39 -> { // look around with the eyes only
                idleLookUntil = now + 1800
                lookX.value = if (Random.nextBoolean()) 0.8f else -0.8f
            }
            in 40..64 -> { // peek left or right with the body
                idleLookUntil = now + 1000
                perform(if (Random.nextBoolean()) Move.PEEK_LEFT else Move.PEEK_RIGHT)
            }
            in 65..79 -> if (bored) { // yawn
                setEmotion(Emotion.SLEEPY, 1800)
                speak("Hmmm, aaah.", Emotion.SLEEPY)
            }
            in 80..89 -> { // sneeze
                setEmotion(Emotion.SURPRISED, 1200)
                speak("Achoo!", Emotion.SURPRISED)
                perform(Move.HOP)
            }
            else -> if (bored) { // hum
                setEmotion(Emotion.HAPPY, 2000)
                speak("La la la.", Emotion.HAPPY)
            }
        }
    }

    // ================================================================ moves
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

    /** Timings assume your 4WD on a smooth floor. Tune ms values if turns are too short or long. */
    enum class Move(val steps: List<Step>) {
        WIGGLE(List(4) { i -> if (i % 2 == 0) Step(170, -170, 140) else Step(-170, 170, 140) }),
        WIGGLE_SMALL(listOf(Step(150, -150, 110), Step(-150, 150, 110))),
        SHAKE(listOf(Step(140, -140, 120), Step(-140, 140, 120))),
        SPIN(listOf(Step(220, -220, 1300))),
        HALF_TURN(listOf(Step(220, -220, 650))),
        FORWARD(listOf(Step(180, 180, 800))),
        BACK(listOf(Step(-180, -180, 700))),
        BACK_OFF(listOf(Step(-180, -180, 450))),
        TURN_LEFT(listOf(Step(-180, 180, 450))),
        TURN_RIGHT(listOf(Step(180, -180, 450))),
        PEEK_LEFT(listOf(Step(-140, 140, 200), Step(0, 0, 600), Step(140, -140, 200))),
        PEEK_RIGHT(listOf(Step(140, -140, 200), Step(0, 0, 600), Step(-140, 140, 200))),
        TURN_AWAY(listOf(Step(200, -200, 750))),
        TURN_AWAY_SMALL(listOf(Step(170, -170, 300))),
        HOP(listOf(Step(200, 200, 150), Step(-200, -200, 150))),
        DIE(listOf(Step(-230, -230, 200), Step(230, -230, 350), Step(0, 0, 100))),
        DANCE(
            listOf(
                Step(200, -200, 300), Step(-200, 200, 300),
                Step(180, 180, 250), Step(-180, -180, 250),
                Step(220, -220, 650),
                Step(150, -150, 120), Step(-150, 150, 120), Step(150, -150, 120), Step(-150, 150, 120),
            )
        ),
    }
}
