package com.vintechafrica.jvinbuddy

/**
 * Songs VinBot "sings" karaoke style: the phone plays the melody as music
 * (one note per beat), and VinBot speaks each line on time over it.
 * Only public domain songs and original lyrics here.
 *
 * melody: one list of pitches per line, 1.0 = base note.
 */
data class Song(val title: String, val lines: List<String>, val melody: List<List<Float>>, val bpm: Int = 132)

object Songs {
    // Major scale relative pitches
    private const val C = 1.0f
    private const val D = 1.122f
    private const val E = 1.26f
    private const val F = 1.335f
    private const val G = 1.498f
    private const val A = 1.682f
    private const val B = 1.888f
    private const val C2 = 2.0f
    private const val G0 = 0.749f
    private const val A0 = 0.841f
    private const val B0 = 0.944f

    /** Twinkle Twinkle Little Star (public domain). */
    val twinkle = Song(
        "Twinkle Twinkle",
        listOf("Twinkle twinkle little star", "How I wonder what you are", "Up above the world so high", "Like a diamond in the sky"),
        listOf(listOf(C, C, G, G, A, A, G), listOf(F, F, E, E, D, D, C), listOf(G, G, F, F, E, E, D), listOf(G, G, F, F, E, E, D)),
    )

    /** Happy Birthday (public domain). */
    fun birthday(name: String = "friend") = Song(
        "Happy Birthday",
        listOf("Happy birthday to you", "Happy birthday to you", "Happy birthday dear $name", "Happy birthday to you"),
        listOf(
            listOf(G0, G0, A0, G0, C, B0),
            listOf(G0, G0, A0, G0, D, C),
            listOf(G0, G0, G, E, C, B0, A0),
            listOf(F, F, E, C, D, C),
        ),
        bpm = 120,
    )

    /** Original song for VinBot. */
    val robotSong = Song(
        "Little Bot",
        listOf("Beep boop I'm a little bot", "Rolling round the house a lot", "Wheels go spin and eyes go bright", "I will be your friend tonight"),
        listOf(listOf(C, E, G, G, A, G, E), listOf(F, F, E, E, D, D, C), listOf(G, G, A, A, B, A, G), listOf(F, E, D, E, D, D, C)),
    )

    /** Another original. */
    val batterySong = Song(
        "Charge Me Up",
        listOf("Charge me up and watch me go", "Fast or slow or fast or slow", "When my battery's running low", "Plug me in and off we go"),
        listOf(listOf(G, E, C, E, G, G, C2), listOf(A, G, E, G, A, G, E), listOf(F, F, E, E, D, D, C), listOf(D, E, F, E, D, D, C)),
        bpm = 140,
    )

    val all: List<Song> get() = listOf(twinkle, robotSong, batterySong)

    /** Original raps for when there is no internet. */
    val offlineRaps: List<List<String>> = listOf(
        listOf(
            "Yo, my name is VinBot and I roll so smooth",
            "Four little wheels and I got the groove",
            "Made in Nairobi, built by Vincent's hand",
            "Smartest little robot in the whole wide land",
            "I see your face and I follow you near",
            "Wave me a hello and I'm happy right here",
            "Charge me up, I'm ready to go",
            "VinBot out, that's the end of the show",
        ),
        listOf(
            "Beep beep boop, let me tell you my story",
            "Circuits and code, that's my glory",
            "Blue eyes glowing in the dark of the night",
            "Hand signs, games, I do it all right",
            "Rock paper scissors, I'm the champ of the crew",
            "Peekaboo, staring, I'm watching you",
            "Jambo Kenya, hakuna matata",
            "VinBot style, that's the robot data",
        ),
    )
}
