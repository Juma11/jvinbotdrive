package com.vintechafrica.jvinbuddy

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Games VinBot plays with you, using its camera:
 *  - Rock paper scissors: real hand shapes, best of three
 *  - Staring contest: it watches your eyes; blink and you lose
 *  - Peekaboo: hide your face, it worries; show it, it laughs
 */
class Games(
    private val brain: Brain,
    private val hands: HandGestures?,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    enum class Kind { RPS, STARE, PEEKABOO }

    @Volatile var active: Kind? = null
        private set
    private var last: Kind? = null
    private var job: Job? = null

    fun start(kind: Kind) {
        stop()
        last = kind
        active = kind
        job = scope.launch {
            try {
                when (kind) {
                    Kind.RPS -> rockPaperScissors()
                    Kind.STARE -> staringContest()
                    Kind.PEEKABOO -> peekaboo()
                }
                brain.needs.interacted(socialGain = 6f, funGain = 20f, energyCost = 3f)
            } finally {
                cleanup()
            }
        }
    }

    fun startRandom() = start(Kind.entries.random())

    fun again() = start(last ?: Kind.RPS)

    fun stop() {
        job?.cancel()
        job = null
        cleanup()
    }

    private fun cleanup() {
        active = null
        hands?.muted = false
        brain.overlay.value = null
        brain.noBlink.value = false
    }

    // ================================================================ rock paper scissors
    private val icons = mapOf("rock" to "✊", "paper" to "✋", "scissors" to "✌️")

    private suspend fun rockPaperScissors() {
        val h = hands
        if (h == null || !h.loaded || !prefs.gestures) {
            brain.react(Emotion.SAD, "I need my hand sign eyes for that. Turn on hand gestures in my settings.")
            return
        }
        h.muted = true
        var you = 0
        var me = 0
        brain.react(Emotion.EXCITED, "Rock paper scissors! Best of three. Show me your hand when I say shoot.", 6000)
        delay(4200)

        var round = 1
        while (you < 2 && me < 2 && round <= 6) {
            // Count in
            for ((word, icon) in listOf("Rock" to "rock", "paper" to "paper", "scissors" to "scissors")) {
                brain.overlay.value = icons[icon]
                brain.say(word, Emotion.EXCITED)
                delay(650)
            }
            brain.say("Shoot!", Emotion.EXCITED)
            brain.overlay.value = "?"
            val shootAt = SystemClock.elapsedRealtime()

            // Read your hand: same shape on 2 frames in a row, within 2.5 s
            var yours: String? = null
            var prev: String? = null
            while (SystemClock.elapsedRealtime() - shootAt < 2500 && yours == null) {
                val s = h.shape
                val fresh = SystemClock.elapsedRealtime() - h.shapeAt < 400
                if (s != null && fresh) {
                    if (s == prev) yours = s
                    prev = s
                } else {
                    prev = null
                }
                delay(80)
            }
            if (yours == null) {
                brain.overlay.value = null
                brain.react(Emotion.CURIOUS, "I didn't see your hand. Hold it up to my camera. Let's go again.", 3000)
                delay(3200)
                round++
                continue
            }

            val mine = listOf("rock", "paper", "scissors").random()
            brain.overlay.value = "You ${icons[yours]}    Me ${icons[mine]}"
            when {
                yours == mine -> {
                    brain.react(Emotion.SURPRISED, listOf("Same! Draw.", "Great minds think alike!").random(), 2500)
                }
                beats(mine, yours) -> {
                    me++
                    brain.react(Emotion.LAUGH, listOf("I win this one!", "Ha! ${mine.replaceFirstChar { it.uppercase() }} beats $yours!").random(), 2500)
                    brain.perform(Brain.Move.WIGGLE_SMALL)
                }
                else -> {
                    you++
                    brain.react(Emotion.ANGRY, listOf("Grr, you got me!", "${yours.replaceFirstChar { it.uppercase() }} beats $mine. Not fair!").random(), 2500)
                    brain.perform(Brain.Move.GROWL)
                }
            }
            delay(2600)
            brain.overlay.value = "You $you : $me Me"
            delay(1500)
            round++
        }

        when {
            me > you -> {
                brain.overlay.value = "🏆 VinBot wins $me : $you"
                brain.react(Emotion.EXCITED, "I'm the champion! Say again for a rematch.", 4000)
                brain.perform(Brain.Move.CELEBRATE)
            }
            you > me -> {
                brain.overlay.value = "🏆 You win $you : $me"
                brain.react(Emotion.SAD, "You beat me. Well played! Say again if you dare.", 4000)
            }
            else -> brain.react(Emotion.NEUTRAL, "That's enough for now. Say again to play more.", 3000)
        }
        delay(4000)
    }

    private fun beats(a: String, b: String) =
        (a == "rock" && b == "scissors") || (a == "scissors" && b == "paper") || (a == "paper" && b == "rock")

    // ================================================================ staring contest
    private suspend fun staringContest() {
        if (!brain.faceVisible()) {
            brain.react(Emotion.CURIOUS, "Look at me first, then say staring contest.")
            return
        }
        brain.react(Emotion.EXCITED, "Staring contest! Don't blink. Ready... go!", 3000)
        delay(3000)
        brain.noBlink.value = true
        brain.overlay.value = "👀"
        brain.setEmotion(Emotion.NEUTRAL, 60_000)

        val start = SystemClock.elapsedRealtime()
        val robotGivesUpAt = start + Random.nextLong(10_000, 25_000)
        var lastStamp = brain.faceStamp
        var closedFrames = 0
        while (true) {
            delay(80)
            val now = SystemClock.elapsedRealtime()
            if (now - brain.faceStamp > 1500) {
                brain.noBlink.value = false
                brain.react(Emotion.LAUGH, "You looked away! I win!", 3000)
                brain.perform(Brain.Move.WIGGLE)
                break
            }
            if (brain.faceStamp != lastStamp) {
                lastStamp = brain.faceStamp
                val eyes = brain.currentFace()?.eyesOpen ?: -1f
                closedFrames = if (eyes in 0f..0.3f) closedFrames + 1 else 0
                if (closedFrames >= 1 && now - start > 1000) {
                    brain.noBlink.value = false
                    brain.react(Emotion.LAUGH, listOf("You blinked! I win!", "Gotcha! You blinked!").random(), 3000)
                    brain.perform(Brain.Move.CELEBRATE)
                    break
                }
            }
            if (now > robotGivesUpAt) {
                brain.noBlink.value = false
                brain.setEmotion(Emotion.SLEEPY, 700) // big dramatic blink
                delay(700)
                brain.react(Emotion.SAD, "Noooo, I blinked! You win.", 3000)
                break
            }
        }
        val secs = (SystemClock.elapsedRealtime() - start) / 1000
        brain.overlay.value = "⏱ ${secs}s"
        delay(3000)
    }

    // ================================================================ peekaboo
    private suspend fun peekaboo() {
        brain.react(Emotion.HAPPY, "Let's play peekaboo! Hide your face with your hands.", 3500)
        delay(3500)
        var rounds = 0
        var idleSince = SystemClock.elapsedRealtime()
        while (rounds < 4) {
            // Wait for you to hide
            while (brain.faceVisible()) {
                delay(100)
                if (SystemClock.elapsedRealtime() - idleSince > 25_000) {
                    brain.react(Emotion.BORED, "You're not hiding. Let's play later.", 3000)
                    return
                }
            }
            delay(600)
            if (brain.faceVisible()) continue
            brain.react(Emotion.CURIOUS, listOf("Where did you go?", "Hello? Where are you?", "Huh? Gone?").random(), 6000)

            // Wait for you to come back
            val hiddenAt = SystemClock.elapsedRealtime()
            while (!brain.faceVisible()) {
                delay(80)
                if (SystemClock.elapsedRealtime() - hiddenAt > 10_000) {
                    brain.react(Emotion.SAD, "I can't find you. Game over.", 3000)
                    return
                }
            }
            rounds++
            brain.react(Emotion.LAUGH, listOf("Peekaboo! Hahaha!", "There you are! Hahaha!", "Boo! Hehehe!").random(), 2500)
            brain.perform(Brain.Move.WIGGLE)
            idleSince = SystemClock.elapsedRealtime()
            delay(2500)
        }
        brain.react(Emotion.HAPPY, "That was fun! Say again to play more.", 3000)
        delay(3000)
    }
}
