package com.vintechafrica.jvinbuddy

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Answers that need no internet. Checked before the AI, online or offline,
 * because they are instant. Add your own lines here.
 */
object LocalReplies {

    private val greeting = Regex("^(hi|hello|hey|habari|jambo|sasa|niaje|good (morning|afternoon|evening))\\b")
    private val jokes = listOf(
        "Why did the robot go on holiday? It needed to recharge.",
        "I told my motor a joke. It did not get it, it just kept turning.",
        "What is a robot's favourite snack? Micro chips.",
        "Why was the robot so calm? It had nerves of steel.",
    )

    fun answer(raw: String, battery: Float?, online: Boolean): Pair<Emotion, String>? {
        val t = raw.lowercase(Locale.ROOT).trim()
        fun has(vararg words: String) = words.any { t.contains(it) }
        val now = Date()
        return when {
            has("what time", "the time") ->
                Emotion.NEUTRAL to "It's ${SimpleDateFormat("h:mm a", Locale.UK).format(now)}."
            has("what day", "the date", "today's date", "what is today") ->
                Emotion.NEUTRAL to "Today is ${SimpleDateFormat("EEEE, d MMMM", Locale.UK).format(now)}."
            has("your battery", "battery level", "battery status", "how is your battery") ->
                if (battery != null) Emotion.NEUTRAL to "My battery is at %.1f volts.".format(battery)
                else Emotion.CURIOUS to "I can't feel my battery yet. Vincent has not wired my battery sensor."
            has("your name", "who are you") -> Emotion.HAPPY to "I'm JVin, your robot buddy."
            has("who made you", "who built you", "who created you") ->
                Emotion.HAPPY to "Vincent built me at Vintech Africa."
            has("how are you") -> Emotion.HAPPY to "I'm great. My wheels are ready to roll."
            has("what can you do") ->
                Emotion.CURIOUS to if (online) "I can follow your face, do tricks, and answer questions."
                else "I'm offline, so I can follow your face, do tricks and tell the time. Questions need internet."
            has("are you online", "do you have internet") ->
                if (online) Emotion.HAPPY to "Yes, I'm online." else Emotion.SAD to "No, I'm offline right now."
            has("thank you", "thanks", "asante") -> Emotion.HAPPY to "You're welcome!"
            greeting.containsMatchIn(t) && t.split(" ").size <= 4 -> Emotion.HAPPY to "Hello! Good to see you."
            // Offline only: online, the AI tells better jokes.
            !online && has("joke", "funny") -> Emotion.HAPPY to jokes.random()
            else -> null
        }
    }

    fun offline(): Pair<Emotion, String> =
        Emotion.SAD to "I'm offline, so I can't look that up. I can still follow you and do tricks."
}
