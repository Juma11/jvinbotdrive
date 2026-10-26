package com.vintechafrica.jvinbuddy

/**
 * Robot singing: each syllable is spoken at its own pitch, so text-to-speech
 * follows a melody. It sounds robotic on purpose. That is the charm.
 * Only public domain songs and original lyrics here.
 *
 * pitch: 1.0 = normal voice. rate: lower = longer note.
 */
data class Note(val text: String, val pitch: Float, val rate: Float = 1.0f)

object Songs {

    // Note pitches (major scale, base = 1.0)
    private const val C = 1.0f
    private const val D = 1.12f
    private const val E = 1.26f
    private const val F = 1.33f
    private const val G = 1.5f
    private const val A = 1.68f
    private const val B = 1.89f
    private const val C2 = 2.0f

    private fun line(vararg n: Pair<String, Float>, last: Float = 0.6f): List<Note> =
        n.mapIndexed { i, (t, p) -> Note(t, p, if (i == n.lastIndex) last else 1.0f) }

    /** Twinkle Twinkle Little Star (public domain). */
    val twinkle: List<Note> =
        line("Twin" to C, "kle" to C, "twin" to G, "kle" to G, "lit" to A, "tle" to A, "star" to G) +
        line("How" to F, "I" to F, "won" to E, "der" to E, "what" to D, "you" to D, "are" to C) +
        line("Up" to G, "a" to G, "bove" to F, "the" to F, "world" to E, "so" to E, "high" to D) +
        line("Like" to G, "a" to G, "dia" to F, "mond" to F, "in" to E, "the" to E, "sky" to D)

    /** Happy Birthday (public domain). */
    fun birthday(name: String = "friend"): List<Note> {
        // Base G = 1.0
        val g = 1.0f; val a = 1.12f; val b = 1.26f; val c = 1.33f; val d = 1.5f
        val e = 1.68f; val f = 1.78f; val g2 = 2.0f
        return line("Hap" to g, "py" to g, "birth" to a, "day" to g, "to" to c, "you" to b) +
            line("Hap" to g, "py" to g, "birth" to a, "day" to g, "to" to d, "you" to c) +
            line("Hap" to g, "py" to g, "birth" to g2, "day" to e, "dear" to c, name to b) +
            line("Hap" to f, "py" to f, "birth" to e, "day" to c, "to" to d, "you" to c, last = 0.5f)
    }

    /** Original song for VinBot. */
    val robotSong: List<Note> =
        line("Beep" to C, "boop" to E, "I'm" to G, "a" to G, "lit" to A, "tle" to G, "bot" to E) +
        line("Rol" to F, "ling" to F, "round" to E, "the" to E, "house" to D, "a" to D, "lot" to C) +
        line("Wheels" to G, "go" to G, "spin" to A, "and" to A, "eyes" to B, "go" to A, "bright" to G) +
        line("I" to F, "will" to E, "be" to D, "your" to E, "friend" to D, "to" to D, "night" to C, last = 0.5f)

    /** A short original one for variety. */
    val batterySong: List<Note> =
        line("Charge" to G, "me" to E, "up" to C, "and" to E, "watch" to G, "me" to G, "go" to C2) +
        line("Fast" to A, "or" to G, "slow" to E, "or" to G, "fast" to A, "or" to G, "slow" to E) +
        line("When" to F, "my" to F, "bat" to E, "tery's" to E, "run" to D, "ning" to D, "low" to C) +
        line("Plug" to D, "me" to E, "in" to F, "and" to E, "off" to D, "we" to D, "go" to C, last = 0.5f)

    val all: List<List<Note>> get() = listOf(twinkle, robotSong, batterySong)

    private val melody = listOf(C, E, G, E, F, D, E, C, G, A, G, E, D, E, C)

    /** Fit any lyrics (e.g. written by the AI) to a simple repeating melody. */
    fun fromLyrics(lyrics: String): List<Note> {
        val lines = lyrics.split(Regex("[.!?\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        val notes = mutableListOf<Note>()
        var i = 0
        for (l in lines) {
            val words = l.split(Regex("\\s+")).filter { it.isNotEmpty() }
            words.forEachIndexed { wi, w ->
                val last = wi == words.lastIndex
                notes += Note(w, if (last) C else melody[i % melody.size], if (last) 0.6f else 1.0f)
                i++
            }
        }
        return notes
    }
}
