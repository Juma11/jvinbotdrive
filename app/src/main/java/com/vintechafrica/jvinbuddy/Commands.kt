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
        // music
        "play music", "play some music", "play afrobeat", "play amapiano", "play hip hop", "play reggae",
        "play gospel", "play jazz", "play rock", "play house music", "play pop", "play chill music",
        "next song", "skip", "stop music", "stop the music", "louder", "quieter",
        "make a beat", "drop a beat", "beatbox", "amapiano beat", "afrobeat beat", "hip hop beat",
        "house beat", "dancehall beat", "make a song", "make me a song", "rap", "rap for me",
        // story and teach mode
        "tell your story", "tell me your story", "story time", "start recording", "record",
        "stop recording", "play recording", "replay",
        // emotions and tricks (Emo style)
        "be angry", "get angry", "angry", "be scared", "scared", "boo", "be happy", "smile",
        "be sad", "cry", "laugh", "say sorry", "i love you", "show me love", "give me a kiss",
        "celebrate", "party", "look around", "do a trick", "show me a trick", "trick",
        // games and feelings
        "rock paper scissors", "play rock paper scissors", "staring contest", "peekaboo", "play peekaboo",
        "play a game", "lets play", "game", "stop the game", "end the game", "stop playing", "again", "play again", "rematch",
        "how do you feel", "how are you feeling", "how are you",
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
