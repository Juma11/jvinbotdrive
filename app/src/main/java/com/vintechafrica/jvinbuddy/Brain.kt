package com.vintechafrica.jvinbuddy

import android.os.SystemClock
import java.util.Calendar
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
    private val sing: (List<Note>) -> Unit = {},
) {
    val emotion = MutableStateFlow(Emotion.NEUTRAL)
    val lookX = MutableStateFlow(0f)   // -1 left .. 1 right, as seen on the screen
    val lookY = MutableStateFlow(0f)   // -1 up .. 1 down
    val winkAt = MutableStateFlow(0L)  // face winks when this changes
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
    private var handAt = 0L

    // ---------------------------------------------------------------- human behaviour memory
    private var presentSince = 0L       // when the current visit started
    private var leftReacted = true      // already reacted to you leaving
    private var stareSince = 0L
    private var lastStareReact = 0L
    private var lastCloseReact = 0L
    private var lastFriendReact = 0L
    private var greetedPeriod = ""      // "morning", "evening"... greeted once each
    private val taps = ArrayDeque<Long>()
    private var lifted = false

    // ---------------------------------------------------------------- personality and games
    val needs = Needs(prefs)
    lateinit var games: Games
    /** Big text/emoji shown over the face (game hands, scores). */
    val overlay = MutableStateFlow<String?>(null)
    /** Face stops blinking (staring contest). */
    val noBlink = MutableStateFlow(false)
    private var lastTrick: String? = null
    private var lastTrickAt = 0L
    private var smileFrames = 0
    private var lastSmileReact = 0L
    private var lastSmileTalk = 0L
    private var peekHiddenAt = 0L
    private var lastNeedsTick = 0L
    private var lastNeedsSave = 0L
    private var lastNeedNag = 0L
    private var seenOnce = false

    val faceStamp: Long get() = faceAt
    fun currentFace(): FaceInfo? = face
    /** Face seen in the last 0.9 s (the camera only checks faces a few times a second). */
    fun faceVisible(): Boolean = face != null && SystemClock.elapsedRealtime() - faceAt < 900
    fun say(text: String, e: Emotion) = speak(text, e)

    /** True while the motors are being driven. Senses ignore jolts then. */
    val isDriving: Boolean get() = lastDriveMag > 0

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
            if (gone > 3000) presentSince = now
            leftReacted = false
            // Eyes follow a hand first (doctor finger test), then the face.
            if (now > idleLookUntil && now - handAt > 500) {
                lookX.value = if (prefs.invertSteer) -info.x else info.x
                lookY.value = info.y
            }
            if (asleep || dead || lifted) return
            if (::games.isInitialized && games.active != null) return

            // Peekaboo: you hid behind your hands and came back
            if (peekHiddenAt > 0L && now - peekHiddenAt < 8000) {
                peekHiddenAt = 0L
                react(Emotion.LAUGH, listOf("Peekaboo! Hahaha!", "There you are! Hehehe!").random(), 2500)
                perform(Move.WIGGLE_SMALL)
                needs.interacted(socialGain = 3f, funGain = 5f)
                return
            }
            peekHiddenAt = 0L

            // Smile mirroring
            smileFrames = if (info.smile > 0.75f) smileFrames + 1 else 0
            if (smileFrames >= 3 && now > emotionLockedUntil && now - lastSmileReact > 8000) {
                lastSmileReact = now
                setEmotion(Emotion.HAPPY, 2500)
                winkAt.value = now
                if (now - lastSmileTalk > 120_000 && Random.nextFloat() < needs.chattiness) {
                    lastSmileTalk = now
                    speak(listOf("I love your smile!", "You look happy today!", "Hehe, your smile is contagious.").random(), Emotion.HAPPY)
                }
            }

            if (reactToFace(info, gone, now)) return
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
            if (now > idleLookUntil && now - handAt > 500) {
                lookX.value = lookX.value * 0.9f
                lookY.value = lookY.value * 0.9f
            }
        }
    }

    /** A hand in view. The eyes track it; the body keeps following the face. */
    fun onHand(x: Float, y: Float) {
        if (asleep || dead) return
        handAt = SystemClock.elapsedRealtime()
        lookX.value = if (prefs.invertSteer) -x else x
        lookY.value = y
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

    /** Tap on the face = petting. Three quick taps = tickling. */
    fun pet() {
        val now = SystemClock.elapsedRealtime()
        taps.addLast(now)
        while (taps.isNotEmpty() && now - taps.first() > 1200) taps.removeFirst()
        val count = needs.interacted(socialGain = 5f)
        praise(true)
        if (milestone(count)) return
        when {
            asleep -> wake()
            dead -> revive()
            taps.size >= 3 -> {
                taps.clear()
                react(Emotion.LAUGH, listOf("Hahaha! That tickles!", "Hehehe, stop it!", "Hahaha!").random(), 3000)
                perform(Move.WIGGLE)
            }
            else -> {
                setEmotion(Emotion.HAPPY, 3000)
                winkAt.value = SystemClock.elapsedRealtime()
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

    // ================================================================ human behaviour
    /**
     * Reactions to what people do in front of it. Returns true if it reacted,
     * so the normal "hello again" greeting is skipped.
     */
    private fun reactToFace(f: FaceInfo, gone: Long, now: Long): Boolean {
        val busy = moveJob?.isActive == true || now < emotionLockedUntil
        // Birthday or a Kenyan holiday: once per day, first time it sees you
        val first = !seenOnce
        seenOnce = true
        if ((gone > 30_000 || first) && specialDay()) return true
        // Someone right in its face
        if (f.size > 0.45f && now - lastCloseReact > 15_000 && !busy) {
            lastCloseReact = now
            react(Emotion.SCARED, listOf("Whoa, too close!", "Personal space please!", "Eek, big face!").random(), 2500)
            perform(Move.BACK_OFF)
            return true
        }
        // A second person appears
        if (f.count >= 2 && now - lastFriendReact > 60_000 && !busy) {
            lastFriendReact = now
            react(Emotion.CURIOUS, listOf("Ooh, who's your friend?", "Two humans! Hello!", "A new friend!").random(), 3000)
            perform(Move.WIGGLE_SMALL)
            return true
        }
        // Coming back after a while: greet by time of day, once per period
        if (gone > 60_000) {
            val period = dayPeriod()
            if (period != greetedPeriod) {
                greetedPeriod = period
                val line = when (period) {
                    "morning" -> "Good morning Vincent!"
                    "afternoon" -> "Good afternoon!"
                    "evening" -> "Good evening! How was your day?"
                    else -> "It's late. You should sleep soon."
                }
                react(if (period == "night") Emotion.TIRED else Emotion.EXCITED, line, 3500)
                perform(if (period == "night") Move.WIGGLE_SMALL else Move.CELEBRATE)
                return true
            }
            if (needs.social < 30f && gone > 2 * 60_000) {
                react(Emotion.LOVE, listOf("I missed you so much!", "Finally! I was so lonely.").random(), 3500)
                perform(Move.CELEBRATE)
                return true
            }
            if (gone > 5 * 60_000) {
                react(Emotion.EXCITED, listOf("You're back!", "Yay, you're here!", "I missed you!").random(), 3000)
                perform(Move.CELEBRATE)
                return true
            }
        }
        return false
    }

    /** Called every tick: notices you leaving and staring. */
    private fun watchPeople(now: Long) {
        if (asleep || dead || lifted || paused) return
        if (::games.isInitialized && games.active != null) return
        val f = face
        // Face vanished right while a hand was in front of it: you're hiding (peekaboo)
        if (f == null && peekHiddenAt == 0L && now - lastFaceSeen in 500..1500 && now - handAt < 1200 &&
            presentSince > 0 && lastFaceSeen - presentSince > 2000
        ) {
            peekHiddenAt = now
            setEmotion(Emotion.CURIOUS, 3000)
            if (Random.nextFloat() < needs.chattiness) speak(listOf("Huh?", "Where did you go?").random(), Emotion.CURIOUS)
            return
        }
        if (peekHiddenAt > 0L && now - peekHiddenAt < 8000) return
        // You walked away after spending time with it
        if (f == null && !leftReacted && presentSince > 0 &&
            lastFaceSeen - presentSince > 5000 && now - lastFaceSeen in 3000..6000
        ) {
            leftReacted = true
            react(Emotion.SAD, listOf("Where did you go?", "Hello? Come back!", "Aww, don't leave.").random(), 3500)
            if (follow.value) perform(Move.SEARCH)
            return
        }
        // Staring: face centred and present for 6 s, nothing else happening
        if (f != null && faceFresh() && abs(f.x) < 0.25f && now - presentSince > 6000) {
            if (stareSince == 0L) stareSince = now
            if (now - stareSince > 6000 && now - lastStareReact > 60_000 && now > emotionLockedUntil) {
                lastStareReact = now
                stareSince = 0L
                react(Emotion.SHY, listOf("Why are you staring at me?", "Hehe, you're making me shy.", "Do I have something on my face?").random(), 3500)
            }
        } else {
            stareSince = 0L
        }
    }

    /** A sudden loud sound, like a clap or someone calling. */
    fun onClap() {
        if (dead || lifted || paused) return
        if (::games.isInitialized && games.active != null) return
        if (asleep) { wake(); return }
        if (moveJob?.isActive == true || SystemClock.elapsedRealtime() < emotionLockedUntil) return
        setEmotion(Emotion.SURPRISED, 1200)
        script {
            delay(1200)
            setEmotion(Emotion.CURIOUS, 2500)
            idleLookUntil = SystemClock.elapsedRealtime() + 2000
            lookX.value = -0.7f; delay(600); lookX.value = 0.7f; delay(600); lookX.value = 0f
            if (Random.nextFloat() < needs.chattiness) speak(listOf("You called?", "Huh? What was that?", "I heard that!").random(), Emotion.CURIOUS)
        }
    }

    /** Phone shaken hard several times. */
    fun onShaken() {
        if (asleep || dead) return
        cancelMove()
        drive(0, 0)
        react(Emotion.DIZZY, listOf("Stop shaking me!", "Whoa, I'm dizzy!", "Earthquake!").random(), 3000)
    }

    /** Robot picked up (true) or put back down (false). */
    fun onLifted(up: Boolean) {
        lifted = up
        if (dead) return
        if (up) {
            cancelMove()
            drive(0, 0)
            asleep = false
            react(Emotion.SCARED, listOf("Whoa! Put me down!", "I'm flying!", "Careful, I'm fragile!").random(), 60_000)
        } else {
            react(Emotion.HAPPY, listOf("Phew, solid ground.", "Thanks for putting me down.", "Home sweet floor.").random(), 2500)
            perform(Move.WIGGLE_SMALL)
        }
    }

    private fun dayPeriod(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "morning"
        in 12..17 -> "afternoon"
        in 18..21 -> "evening"
        else -> "night"
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
            has("sing", "happy birthday", "twinkle") -> {
                val song = when {
                    has("happy birthday") -> Songs.birthday(birthdayName(t))
                    has("twinkle") -> Songs.twinkle
                    else -> Songs.all.random()
                }
                singSong(song)
            }
            has("be angry", "get angry", "angry") -> {
                react(Emotion.ANGRY, listOf("Grrr!", "Grrrr! I'm angry!", "Hmph!").random()); perform(Move.GROWL)
            }
            has("be scared", "scared", "boo") -> {
                react(Emotion.SCARED, listOf("Eek!", "Aaah! Don't scare me!", "I'm scared!").random()); perform(Move.TREMBLE)
            }
            has("be happy", "smile") -> {
                react(Emotion.HAPPY, "Yay! I'm happy!"); perform(Move.WIGGLE)
            }
            has("be sad") -> {
                react(Emotion.SAD, "Aww. Now I'm sad."); perform(Move.TURN_AWAY_SMALL)
            }
            has("cry") -> {
                react(Emotion.CRYING, "Boo hoo hoo.", 4000); perform(Move.TREMBLE)
            }
            has("laugh") -> {
                react(Emotion.LAUGH, "Hahaha! Hahaha!", 3000); perform(Move.WIGGLE)
            }
            has("say sorry", "apologize") -> {
                react(Emotion.SHY, "I'm sorry. Please forgive me.", 3500); perform(Move.TURN_AWAY_SMALL)
            }
            has("i love you", "show me love", "give me a kiss") -> {
                react(Emotion.LOVE, listOf("I love you too!", "Mwah!", "You're my favourite human!").random(), 4000)
                perform(Move.WIGGLE)
            }
            has("celebrate", "party") -> {
                react(Emotion.EXCITED, "Woohoo! Party time!", 3500); perform(Move.CELEBRATE)
            }
            has("look around") -> {
                react(Emotion.CURIOUS, "Let me look around."); perform(Move.SEARCH)
            }
            has("do a trick", "show me a trick", "trick") -> doRandomTrick()
            has("stop the game", "end the game", "end game", "stop playing") -> {
                games.stop(); react(Emotion.NEUTRAL, "Okay, game over.")
            }
            has("rock paper scissors", "rock paper", "scissors") -> games.start(Games.Kind.RPS)
            has("staring contest", "stare") -> games.start(Games.Kind.STARE)
            has("peekaboo", "peek a boo", "play peekaboo") -> games.start(Games.Kind.PEEKABOO)
            has("play a game", "lets play", "let s play", "game") -> {
                react(Emotion.EXCITED, "Yes! Let's play!", 1500)
                scope.launch { delay(1500); games.startRandom() }
            }
            has("again", "play again", "rematch") -> games.again()
            has("how do you feel", "how are you feeling", "how are you") -> tellMood()
            has("stop", "freeze", "sit", "stay", "wait") -> {
                if (games.active != null) games.stop()
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
                react(Emotion.HAPPY, "Wheee!"); perform(Move.SPIN); trick("spin")
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
                react(Emotion.HAPPY, "Let's dance!"); perform(Move.DANCE); trick("dance")
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
                if (praise(true)) return true
                react(Emotion.HAPPY, listOf("Thank you!", "Yay!", "I'm a good robot!").random())
                perform(Move.WIGGLE)
            }
            has("bad dog", "bad boy", "bad robot") || t == "no" -> {
                praise(false)
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
            Emotion.LAUGH, Emotion.EXCITED -> perform(Move.WIGGLE)
            Emotion.SCARED -> perform(Move.TREMBLE)
            Emotion.ANGRY -> perform(Move.GROWL)
            Emotion.LOVE, Emotion.SHY -> perform(Move.WIGGLE_SMALL)
            else -> {}
        }
    }

    // ================================================================ hand signs
    fun onGesture(sign: HandSign) {
        if (::games.isInitialized && games.active != null) return
        needs.interacted(socialGain = 3f, funGain = 2f)
        if (sign == HandSign.GUN) {
            playDead(); return
        }
        if (dead) revive()
        if (asleep) {
            wake(); return
        }
        when (sign) {
            HandSign.WAVE -> {
                react(Emotion.HAPPY, listOf("Hi!", "Hello!", "Hey there!").random()); perform(Move.WIGGLE)
            }
            HandSign.PALM_STOP -> {
                urgentStop(); react(Emotion.NEUTRAL, "Okay, stopping.")
            }
            HandSign.THUMB_UP -> {
                if (praise(true)) return
                react(Emotion.HAPPY, listOf("Thanks!", "Yay!", "Cool!").random()); perform(Move.WIGGLE_SMALL)
            }
            HandSign.THUMB_DOWN -> {
                praise(false)
                react(Emotion.SAD, "Aww."); perform(Move.TURN_AWAY_SMALL)
            }
            HandSign.PEACE -> {
                react(Emotion.HAPPY, "Dance time!"); perform(Move.DANCE); trick("dance")
            }
            HandSign.POINT_UP -> {
                react(Emotion.HAPPY, "Wheee!"); perform(Move.SPIN); trick("spin")
            }
            HandSign.FIST -> {
                react(Emotion.HAPPY, "Fist bump!"); perform(Move.HOP)
            }
            HandSign.LOVE -> {
                react(Emotion.LOVE, "I love you too!", 4000); perform(Move.WIGGLE)
            }
            HandSign.POINT_LEFT, HandSign.POINT_RIGHT -> {
                // Same side logic as following a face, so "invert steering" fixes both.
                var side = if (sign == HandSign.POINT_RIGHT) 1f else -1f
                if (prefs.invertSteer) side = -side
                react(Emotion.CURIOUS, "That way?")
                perform(if (side > 0) Move.TURN_LEFT else Move.TURN_RIGHT)
            }
            HandSign.GUN -> {}
        }
    }

    // ================================================================ singing
    /** "happy birthday to Brian" -> "Brian". */
    private fun birthdayName(t: String): String =
        Regex("birthday to ([a-z]+)").find(t)?.groupValues?.get(1)
            ?.replaceFirstChar { it.uppercase() } ?: "friend"

    /** Sings and sways side to side until the song ends. */
    fun singSong(notes: List<Note>) {
        if (notes.isEmpty()) return
        // Rough length: about 0.45 s per syllable, longer for slow notes.
        val ms = notes.sumOf { (450 / it.rate).toDouble() }.toLong()
        setEmotion(Emotion.SINGING, ms)
        sing(notes)
        val sways = (ms / 1200).toInt().coerceIn(1, 40)
        val steps = mutableListOf<Step>()
        repeat(sways) {
            steps += Step(110, -110, 220); steps += Step(0, 0, 380)
            steps += Step(-110, 110, 220); steps += Step(0, 0, 380)
        }
        performSteps(steps)
    }

    // ================================================================ tricks
    private val trickIds = listOf("spin", "dance", "playdead", "growl", "sing", "celebrate", "hiccup", "chase")

    /** Picks a trick, favouring the ones you praised before. */
    private fun doRandomTrick() = doTrick(needs.pickWeighted(trickIds))

    private fun doTrick(id: String) {
        when (id) {
            "spin" -> { react(Emotion.HAPPY, "Watch me spin!"); perform(Move.SPIN) }
            "dance" -> { react(Emotion.HAPPY, "Dance time!"); perform(Move.DANCE) }
            "playdead" -> playDead()
            "growl" -> { react(Emotion.ANGRY, "Grrr! Just kidding."); perform(Move.GROWL) }
            "sing" -> singSong(Songs.all.random())
            "hiccup" -> hiccups()
            "chase" -> { react(Emotion.EXCITED, "I'm chasing my tail!"); perform(Move.SPIN) }
            else -> { react(Emotion.EXCITED, "Ta-da!"); perform(Move.CELEBRATE) }
        }
        trick(id)
    }

    /** Remember the last trick so praise right after it counts. */
    private fun trick(id: String) {
        lastTrick = id
        lastTrickAt = SystemClock.elapsedRealtime()
        needs.interacted(socialGain = 1f, funGain = 6f, energyCost = 2f)
    }

    /**
     * Praise or scolding within 10 s of a trick changes how often it does that trick.
     * Returns true if it already spoke about it.
     */
    private fun praise(liked: Boolean): Boolean {
        val t = lastTrick ?: return false
        if (SystemClock.elapsedRealtime() - lastTrickAt > 10_000) return false
        lastTrick = null
        val w = needs.reinforce(t, liked)
        needs.save()
        if (liked && (w == 7 || w == 11)) {
            react(Emotion.LOVE, "You really like it when I ${trickName(t)}! I'll do it more.", 3500)
            perform(Move.WIGGLE)
            return true
        }
        return false
    }

    private fun trickName(id: String) = when (id) {
        "playdead" -> "play dead"
        "sing" -> "sing"
        "growl" -> "growl"
        "chase" -> "chase my tail"
        "hiccup" -> "get hiccups"
        "celebrate" -> "celebrate"
        else -> id
    }

    /** Celebrates interaction milestones. */
    private fun milestone(count: Int): Boolean {
        if (count !in setOf(50, 100, 250, 500, 1000)) return false
        react(Emotion.EXCITED, "We've played together $count times. You're my best friend!", 4000)
        perform(Move.CELEBRATE)
        return true
    }

    private fun tellMood() {
        when (needs.mood()) {
            "tired" -> react(Emotion.TIRED, "I'm a bit tired. I could use a nap.")
            "lonely" -> react(Emotion.SAD, "A little lonely. Will you play with me?")
            "bored" -> react(Emotion.BORED, "Bored! Let's play a game. Say rock paper scissors.")
            "great" -> react(Emotion.EXCITED, "I feel great! Thanks for spending time with me.")
            else -> react(Emotion.HAPPY, "I'm okay. Even better now that you're here.")
        }
    }

    // ================================================================ special days
    private fun specialDay(): Boolean {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        if (prefs.getInt("special_day_done", 0) == today) return false
        val md = "%02d-%02d".format(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        val line = when {
            prefs.birthday == md -> null // handled below with a song
            md == "01-01" -> "Happy New Year! Let's make this year amazing."
            md == "05-01" -> "Happy Labour Day! Take a rest today."
            md == "06-01" -> "Happy Madaraka Day!"
            md == "10-10" -> "Happy Mazingira Day! Let's take care of nature."
            md == "10-20" -> "Happy Mashujaa Day! You are my hero."
            md == "12-12" -> "Happy Jamhuri Day! Long live Kenya!"
            md == "12-25" -> "Merry Christmas!"
            md == "12-26" -> "Happy Boxing Day!"
            md == "12-31" -> "It's the last day of the year! Party tonight?"
            else -> return false
        }
        prefs.putInt("special_day_done", today)
        if (line == null) {
            react(Emotion.EXCITED, "It's your birthday! Happy birthday Vincent!", 3000)
            scope.launch {
                delay(3000)
                singSong(Songs.birthday("Vincent"))
            }
        } else {
            react(Emotion.EXCITED, line, 4000)
            perform(Move.CELEBRATE)
        }
        return true
    }

    // ================================================================ daily surprises
    /**
     * Rare things it does by itself so there is something new most days.
     * Each one waits at least 3 days before it repeats. Rarity: higher = rarer.
     */
    private data class Surprise(val id: String, val rarity: Int, val run: () -> Unit)

    private val surprises: List<Surprise> by lazy {
        listOf(
            Surprise("hiccups", 1) { hiccups() },
            Surprise("sneezes", 1) {
                script {
                    repeat(3) { setEmotion(Emotion.SURPRISED, 700); speak("Achoo!", Emotion.SURPRISED); perform(Move.HOP); delay(1100) }
                    react(Emotion.TIRED, "Excuse me. I think I'm catching a cold.")
                }
            },
            Surprise("nodoff", 1) {
                script {
                    react(Emotion.SLEEPY, "I'm not tired... I'm not...", 2500); delay(2600)
                    react(Emotion.SURPRISED, "I'm awake! I'm awake!", 2000); perform(Move.HOP)
                }
            },
            Surprise("bug", 2) {
                script {
                    setEmotion(Emotion.CURIOUS, 3000)
                    idleLookUntil = SystemClock.elapsedRealtime() + 2500
                    for (x in listOf(-0.8f, -0.3f, 0.2f, 0.7f, 0.9f)) { lookX.value = x; lookY.value = 0.4f; delay(300) }
                    speak("Is that a bug?", Emotion.CURIOUS); delay(1200)
                    react(Emotion.SCARED, "Eek! It's coming this way!"); perform(Move.TREMBLE)
                }
            },
            Surprise("practice", 2) {
                script {
                    perform(Move.DANCE); delay(2500)
                    if (faceVisible()) react(Emotion.SHY, "Oh! You saw that? I was practising.", 3000)
                    else react(Emotion.HAPPY, "Nailed it.")
                }
            },
            Surprise("count", 2) { react(Emotion.EXCITED, "One, two, three, four, five! I can count!", 3500) },
            Surprise("beatbox", 3) {
                script {
                    react(Emotion.LAUGH, "Boots and cats and boots and cats.", 3500); perform(Move.GROWL)
                    delay(3000); speak("I should be a DJ.", Emotion.HAPPY)
                }
            },
            Surprise("stretch", 1) {
                script {
                    react(Emotion.TIRED, "Aaah, stretching.", 3000)
                    performSteps(listOf(Step(120, 120, 500), Step(0, 0, 500), Step(-120, -120, 500)))
                }
            },
            Surprise("daydream", 2) { react(Emotion.LOVE, "I dreamed I was a big racing car. Vroom vroom.", 3500) },
            Surprise("swahili", 2) { react(Emotion.EXCITED, "Jambo! Hakuna matata!", 3000); perform(Move.WIGGLE) },
            Surprise("beepboop", 1) { react(Emotion.EXCITED, "Beep boop. Initiating maximum cuteness.", 3000) },
            Surprise("selfhighfive", 2) { react(Emotion.HAPPY, "High five, me!", 2500); perform(Move.WIGGLE) },
            Surprise("sky", 3) { react(Emotion.THINKING, "Why is the sky blue? Hmm. I should ask Google.", 3500) },
            Surprise("toot", 3) {
                script {
                    react(Emotion.SURPRISED, "Oops!", 1200); perform(Move.HOP); delay(1300)
                    react(Emotion.SHY, "That wasn't me. That was the motor.", 3000)
                }
            },
            Surprise("tailchase", 2) {
                script {
                    react(Emotion.EXCITED, "I'm going to catch my tail!", 2000); perform(Move.SPIN); delay(3000)
                    react(Emotion.DIZZY, "Almost had it.", 2500)
                }
            },
            Surprise("hum", 1) { singSong(Songs.all.random().take(7)) },
        )
    }

    private fun hiccups() = script {
        repeat(3) {
            setEmotion(Emotion.SURPRISED, 600); speak("Hic!", Emotion.SURPRISED); perform(Move.HOP); delay(1400)
        }
        react(Emotion.SHY, "Sorry. Hiccups.")
    }

    /** Runs a little sequence without blocking the brain loop. */
    private fun script(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private fun playSurprise(): Boolean {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val ready = surprises.filter { today - prefs.getInt("surprise_${it.id}", 0) >= 3 }
        if (ready.isEmpty()) return false
        // Common ones (rarity 1) are 3x more likely than rare ones (rarity 3).
        val pool = ready.flatMap { s -> List(4 - s.rarity) { s } }
        val s = pool.random()
        prefs.putInt("surprise_${s.id}", today)
        s.run()
        return true
    }

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

    fun react(e: Emotion, text: String, holdMs: Long = 4000) {
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
            // Falls asleep faster late at night
            val sleepAfter = if (dayPeriod() == "night") 60_000L else sleepAfterMs
            emotion.value = when {
                since > sleepAfter -> Emotion.SLEEPY
                since > boredAfterMs -> Emotion.BORED
                now - handAt < 600 -> Emotion.CURIOUS
                face != null -> Emotion.HAPPY
                else -> Emotion.NEUTRAL
            }
        }

        // Needs update once a second, saved once a minute
        if (now - lastNeedsTick >= 1000) {
            val sleeping = asleep || emotion.value == Emotion.SLEEPY
            needs.tick((now - lastNeedsTick).coerceAtMost(5000) / 1000f, faceFresh(), sleeping, isDriving)
            lastNeedsTick = now
            if (now - lastNeedsSave > 60_000) { needs.save(); lastNeedsSave = now }
            checkNeeds(now)
        }

        val inGame = ::games.isInitialized && games.active != null
        if (!inGame) watchPeople(now)

        if (moveJob?.isActive == true) return
        if (inGame) {
            drive(0, 0)
            return
        }

        val sleepy = asleep || dead || emotion.value == Emotion.SLEEPY
        if (paused || sleepy || lifted) {
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
        // About 1 in 12 idle moments is a rare surprise
        if (Random.nextInt(12) == 0 && playSurprise()) return
        val bored = emotion.value == Emotion.BORED || needs.fun_ < 30f
        // A young robot is quiet; it gets chattier the more you play with it
        val talk = Random.nextFloat() < needs.chattiness
        when (Random.nextInt(100)) {
            in 0..39 -> { // look around with the eyes only
                idleLookUntil = now + 1800
                lookX.value = if (Random.nextBoolean()) 0.8f else -0.8f
                lookY.value = Random.nextFloat() * 1.2f - 0.6f
            }
            in 40..64 -> { // peek left or right with the body
                idleLookUntil = now + 1000
                perform(if (Random.nextBoolean()) Move.PEEK_LEFT else Move.PEEK_RIGHT)
            }
            in 65..79 -> if (bored && talk) { // yawn
                setEmotion(Emotion.SLEEPY, 1800)
                speak("Hmmm, aaah.", Emotion.SLEEPY)
            }
            in 80..89 -> if (talk) { // sneeze
                setEmotion(Emotion.SURPRISED, 1200)
                speak("Achoo!", Emotion.SURPRISED)
                perform(Move.HOP)
            }
            in 90..94 -> if (bored || needs.social < 30f) { // asks for attention
                setEmotion(Emotion.SAD, 2500)
                speak(listOf("Play with me!", "I'm bored.", "Anybody there?").random(), Emotion.SAD)
                perform(Move.HOP)
            }
            else -> if (bored && talk) { // hum
                setEmotion(Emotion.HAPPY, 2000)
                speak("La la la.", Emotion.HAPPY)
            }
        }
    }

    /** What its needs make it do on its own. */
    private fun checkNeeds(now: Long) {
        if (dead || lifted || paused) return
        if (::games.isInitialized && games.active != null) return
        // Wake up rested
        if (asleep && needs.energy >= 95f && faceFresh()) {
            wake(); return
        }
        if (asleep || now < emotionLockedUntil || moveJob?.isActive == true) return
        if (now - lastNeedNag < 4 * 60_000) return
        when {
            needs.energy < 20f -> {
                lastNeedNag = now
                asleep = true
                follow.value = false
                react(Emotion.SLEEPY, "I'm so tired. Nap time. Pat me to wake me up.", 60 * 60_000)
            }
            needs.fun_ < 25f && faceFresh() -> {
                lastNeedNag = now
                react(Emotion.BORED, "I'm bored. Let's play a game! Say hey vinbot, rock paper scissors.", 4000)
                perform(Move.HOP)
            }
            needs.social < 20f && faceFresh() -> {
                lastNeedNag = now
                react(Emotion.SAD, "I've been lonely. Can you play with me for a bit?", 4000)
            }
        }
    }

    // ================================================================ moves
    private val speed: Float get() = prefs.speedPercent / 100f

    /** All motor commands pass through here, scaled by the speed setting. */
    private fun drive(l: Int, r: Int) {
        val s = speed
        val sl = (l * s).toInt()
        val sr = (r * s).toInt()
        lastDriveMag = maxOf(abs(l), abs(r))
        link.setDrive(sl, sr)
    }

    private fun cancelMove() {
        moveJob?.cancel()
        moveJob = null
    }

    fun perform(move: Move) = performSteps(move.steps, move)

    private fun performSteps(steps: List<Step>, move: Move? = null) {
        cancelMove()
        moveJob = scope.launch {
            // Slower speed = longer step, so a half turn is still about a half turn.
            val stretch = (1f / speed).coerceIn(1f, 2.5f)
            for (step in steps) {
                drive(step.l, step.r)
                delay((step.ms * stretch).toLong())
            }
            drive(0, 0)
            if (move == Move.SPIN || move == Move.DANCE) setEmotion(Emotion.DIZZY, 2200)
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
        TREMBLE(List(8) { i -> if (i % 2 == 0) Step(120, -120, 60) else Step(-120, 120, 60) }),
        GROWL(listOf(Step(200, 200, 140), Step(-200, -200, 140), Step(200, 200, 140), Step(-200, -200, 140))),
        SEARCH(listOf(
            Step(-150, 150, 500), Step(0, 0, 700),
            Step(150, -150, 1000), Step(0, 0, 700),
            Step(-150, 150, 500),
        )),
        CELEBRATE(listOf(Step(220, -220, 650), Step(-180, 180, 140), Step(180, -180, 140), Step(-180, 180, 140), Step(180, -180, 140))),
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
