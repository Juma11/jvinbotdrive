package com.vintechafrica.jvinbuddy

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Story mode: VinBot performs a script, step by step, so you can film it.
 * Edit the script in settings; one step per line. Lines starting with # are notes.
 *
 *   say HAPPY: Hello there!        speak with a face (any emotion name, or leave it out)
 *   photo: Meet Vincent.           show your photo (pick it in settings), optional line to say
 *   logo: Made by Vintech Africa.  show the Vintech logo, optional line to say
 *   caption: Nairobi, 2026         big text on screen
 *   hide                           remove photo / logo / caption
 *   emotion LOVE 3                 make a face for 3 seconds
 *   sleep 2 / wake                 eyes closed for 2 s / wake up happy
 *   move forward 1.5               drive forward slowly for 1.5 s (back, also "fast")
 *   turn left 0.6                  turn (right)
 *   do spin                        any VinBot command: spin, dance, play dead, celebrate...
 *   beat amapiano 8                background beat for 8 s (afrobeat, hip hop, house...)
 *   wait 2                         pause
 */
class Story(
    private val brain: Brain,
    private val voice: Voice,
    private val scope: CoroutineScope,
) {
    val running = MutableStateFlow(false)
    private var job: Job? = null

    fun play(script: String, countdown: Boolean = false) {
        stop()
        job = scope.launch {
            running.value = true
            brain.showRunning = true
            try {
                if (countdown) {
                    for (n in 3 downTo 1) {
                        brain.overlay.value = "$n"
                        delay(1000)
                    }
                    brain.overlay.value = null
                }
                for (raw in script.lines()) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    step(line)
                }
            } finally {
                brain.showRunning = false
                brain.overlay.value = null
                brain.overlayImage.value = null
                brain.dj.stopAll()
                running.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun step(line: String) {
        val word = line.substringBefore(' ').substringBefore(':').lowercase()
        val rest = line.substringAfter(word, "").trimStart(' ', ':').trim()
        when (word) {
            "say" -> {
                val (emotion, text) = splitEmotion(rest)
                sayAndWait(text, emotion)
            }
            "photo", "logo" -> {
                brain.overlayImage.value = word
                if (rest.isNotEmpty()) sayAndWait(rest, Emotion.HAPPY) else delay(1500)
            }
            "caption" -> brain.overlay.value = rest
            "hide" -> {
                brain.overlayImage.value = null
                brain.overlay.value = null
            }
            "emotion" -> {
                val parts = rest.split(Regex("\\s+"))
                val secs = parts.getOrNull(1)?.toFloatOrNull() ?: 2f
                brain.setEmotion(Emotion.parse(parts.getOrNull(0)), (secs * 1000).toLong())
                delay((secs * 1000).toLong())
            }
            "sleep" -> {
                val secs = rest.toFloatOrNull() ?: 2f
                brain.setEmotion(Emotion.SLEEPY, (secs * 1000).toLong())
                delay((secs * 1000).toLong())
            }
            "wake" -> {
                brain.setEmotion(Emotion.SURPRISED, 600); delay(600)
                brain.setEmotion(Emotion.HAPPY, 2000)
                brain.winkAt.value = SystemClock.elapsedRealtime()
                delay(800)
            }
            "move" -> {
                val p = rest.lowercase().split(Regex("\\s+"))
                val back = p.getOrNull(0) == "back" || p.getOrNull(0) == "backward"
                val secs = p.firstNotNullOfOrNull { it.toFloatOrNull() } ?: 1f
                val pwm = if ("fast" in p) 210 else 140
                drive(if (back) -pwm else pwm, if (back) -pwm else pwm, secs)
            }
            "turn" -> {
                val p = rest.lowercase().split(Regex("\\s+"))
                val right = p.getOrNull(0) == "right"
                val secs = p.firstNotNullOfOrNull { it.toFloatOrNull() } ?: 0.5f
                drive(if (right) 170 else -170, if (right) -170 else 170, secs)
            }
            "do" -> {
                if (!brain.handleCommand(rest)) {
                    // Not a command: maybe a move name (wiggle, hop, tremble, growl...)
                    Brain.Move.entries.firstOrNull { it.name.equals(rest.replace(' ', '_'), true) }
                        ?.let { brain.perform(it) }
                }
                delay(400)
                waitWhileBusy(8000)
            }
            "beat" -> {
                val p = rest.lowercase().split(Regex("\\s+"))
                val secs = p.firstNotNullOfOrNull { it.toFloatOrNull() } ?: 8f
                if ("stop" in p) brain.dj.stopAll()
                else brain.dj.backgroundBeat(Synth.Style.from(rest.lowercase()) ?: Synth.Style.AMAPIANO, (secs * 1000).toLong())
            }
            "wait" -> delay(((rest.toFloatOrNull() ?: 1f) * 1000).toLong())
            else -> {
                // Unknown word: treat the whole line as something to say
                sayAndWait(line, Emotion.HAPPY)
            }
        }
    }

    private fun splitEmotion(rest: String): Pair<Emotion, String> {
        val head = rest.substringBefore(':', "")
        if (head.isNotEmpty() && !head.contains(' ')) {
            val e = Emotion.entries.firstOrNull { it.name.equals(head, true) }
            if (e != null) return e to rest.substringAfter(':').trim()
        }
        return Emotion.HAPPY to rest
    }

    private suspend fun sayAndWait(text: String, emotion: Emotion) {
        if (text.isBlank()) return
        val maxMs = 3000L + text.length * 90L
        brain.setEmotion(emotion, maxMs)
        voice.say(text, emotion)
        val start = SystemClock.elapsedRealtime()
        // Wait for speech to start, then to finish
        while (!voice.speaking.value && SystemClock.elapsedRealtime() - start < 1500) delay(50)
        while (voice.speaking.value && SystemClock.elapsedRealtime() - start < maxMs) delay(50)
        delay(250)
    }

    private suspend fun drive(l: Int, r: Int, secs: Float) {
        brain.performSteps(listOf(Brain.Step(l, r, (secs * 1000).toLong())))
        delay((secs * 1000).toLong() + 300)
    }

    private suspend fun waitWhileBusy(maxMs: Long) {
        val start = SystemClock.elapsedRealtime()
        while ((brain.isMoving() || voice.speaking.value) && SystemClock.elapsedRealtime() - start < maxMs) delay(100)
    }

    companion object {
        /** VinBot's origin story, about 60 seconds. Edit it in settings. */
        val DEFAULT = """
# VinBot origin story (about 60 s). One step per line. Edit me!
sleep 2
wake
say HAPPY: Hi. I'm VinBot. And I exist because my creator got bored.
do wiggle
photo: Meet Vincent. Mechatronics engineer. Nairobi.
say BORED: One quiet afternoon, he had nothing to fix.
hide
say THINKING: Now, when a normal person is bored, they scroll TikTok.
say CURIOUS: When a mechatronics engineer is bored, he looks at an old phone and an old robot car, and thinks... what if?
move forward 1.5
say EXCITED: The phone became my brain. The camera, my eyes. The microphone, my ears. The speaker, my mouth.
move back 1
say HAPPY: Now I follow you. I read your hands. I even play dead.
do play dead
wait 6
beat amapiano 10
say EXCITED: I dance to amapiano, and I make my own beats.
do spin
wait 1
say LOVE: So next time you're bored, don't waste it. Build something.
logo: I'm VinBot. Made in Kenya, by Vintech Africa.
wait 3
hide
""".trimIndent()
    }
}
