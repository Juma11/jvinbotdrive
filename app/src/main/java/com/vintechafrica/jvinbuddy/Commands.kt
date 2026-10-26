package com.vintechafrica.jvinbuddy

import org.json.JSONArray

/**
 * Every phrase the always-on ears listen for. The offline recognizer only
 * matches these, so it is fast and accurate even on the Spark 4.
 * Add a phrase here AND handle it in Brain.handleCommand.
 * Use plain English words only: made-up words are not in the speech model.
 */
object Commands {

    /**
     * Wake phrases. "VinBot" is not an English word, so the model hears it as one
     * of these sound-alikes. Phrases with words missing from the model are skipped
     * by Vosk automatically, so extra variants are harmless.
     */
    val WAKE = listOf(
        "hey vin bot", "hey vin", "hey ben bot", "hey bin bot", "hey been bot",
        "hey win bot", "hey when bot", "a vin bot", "hey robot",
    )

    /** Work with no wake word, always. Safety first. */
    val ALWAYS = listOf("stop", "freeze")

    val PHRASES = listOf(
        // shoot / play dead
        "bang", "bang bang", "shoot", "play dead",
        // stop
        "stop", "freeze", "sit", "sit down", "stay", "wait",
        // come / follow
        "come", "come here", "come on", "here boy", "follow me", "follow", "look at me", "watch me",
        // moves
        "go round", "go around", "turn around", "spin", "spin around", "roll over",
        "go back", "move back", "back up", "reverse",
        "go forward", "move forward", "forward", "go ahead",
        "turn left", "go left", "turn right", "go right",
        "dance", "jump",
        // singing
        "sing", "sing a song", "sing for me", "sing happy birthday", "happy birthday",
        "sing twinkle twinkle", "twinkle twinkle",
        // dog stuff
        "speak", "bark", "shake", "high five", "say hello", "fetch",
        "good boy", "good girl", "good dog", "good robot", "well done", "good job",
        "bad dog", "bad boy", "bad robot", "no",
        "go away", "shut up",
        "go to sleep", "sleep", "lie down", "wake up",
    )

    fun grammarJson(): String {
        val arr = JSONArray()
        (PHRASES + WAKE).distinct().forEach { arr.put(it) }
        arr.put("[unk]")
        return arr.toString()
    }

    fun isWake(text: String): Boolean = normalize(text) in WAKE

    fun isAlways(text: String): Boolean = normalize(text) in ALWAYS

    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
}
