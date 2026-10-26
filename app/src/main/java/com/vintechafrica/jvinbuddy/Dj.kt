package com.vintechafrica.jvinbuddy

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VinBot's music side:
 *  - beat():        makes a beat on the phone (offline, original, 5 styles) and dances
 *  - sing():        karaoke style: the phone plays the tune, VinBot speaks the lines on time
 *  - makeSong():    AI writes a short original rap, VinBot raps it over a fresh beat
 *  - playOnline():  streams free Creative Commons music from Jamendo (needs a free client ID)
 *  - openApp():     hands a song to Spotify / YouTube on the phone (licensed playback)
 */
class Dj(
    private val context: Context,
    private val brain: Brain,
    private val voice: Voice,
    private val ai: AiClient,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    /** What is playing now, for the status line; null = nothing. */
    val nowPlaying = MutableStateFlow<String?>(null)
    val isPlaying: Boolean get() = nowPlaying.value != null

    private var track: AudioTrack? = null
    private var media: MediaPlayer? = null
    private var job: Job? = null
    private var playlist: List<OnlineTrack> = emptyList()
    private var playlistIndex = 0

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    // ================================================================ stop
    fun stopAll() {
        job?.cancel()
        job = null
        stopSound()
        nowPlaying.value = null
        brain.overlay.value = null
    }

    private fun stopSound() {
        try { track?.stop() } catch (_: Exception) {}
        track?.release()
        track = null
        try { media?.stop() } catch (_: Exception) {}
        media?.release()
        media = null
    }

    // ================================================================ beats
    fun beat(style: Synth.Style?) {
        stopAll()
        val s = style ?: Synth.Style.entries.random()
        job = scope.launch {
            brain.react(Emotion.EXCITED, listOf("Drop the beat!", "${s.spoken.replaceFirstChar { it.uppercase() }} vibes, let's go!", "DJ VinBot in the house!").random(), 2500)
            val pcm = withContext(Dispatchers.Default) { Synth.toPcm(Synth.renderBeat(s, 4)) }
            delay(1800)
            track = Synth.play(pcm, loop = true)
            nowPlaying.value = "${s.spoken} beat ${s.bpm} bpm"
            brain.overlay.value = "🎶 ${s.spoken} beat"
            delay(1500)
            brain.overlay.value = null
            brain.danceTo(s.bpm, 120_000)
            delay(120_000) // a beat plays for 2 minutes, or until you say stop
            stopAll()
        }
    }

    // ================================================================ karaoke songs
    fun sing(song: Song) {
        stopAll()
        job = scope.launch {
            val (buf, starts) = withContext(Dispatchers.Default) { Synth.renderSong(song) }
            val pcm = Synth.toPcm(buf, 0.75f)
            val totalMs = pcm.size * 1000L / Synth.RATE
            brain.overlay.value = "🎤 ${song.title}"
            nowPlaying.value = song.title
            val t0 = SystemClock.elapsedRealtime()
            track = Synth.play(pcm, loop = false)
            brain.danceTo(song.bpm, totalMs)
            song.lines.forEachIndexed { i, line ->
                val wait = starts[i] - (SystemClock.elapsedRealtime() - t0)
                if (wait > 0) delay(wait)
                // Fit the line into its notes: more syllables = faster speech
                val lineMs = (song.melody[i].size + 1) * 60_000L / song.bpm
                val rate = (syllables(line) * 260f / lineMs).coerceIn(0.85f, 1.35f)
                voice.sayWith(line, 1.25f, rate)
                if (i == 0) brain.overlay.value = null
            }
            delay((totalMs - (SystemClock.elapsedRealtime() - t0)).coerceAtLeast(0))
            stopAll()
            brain.react(Emotion.HAPPY, listOf("Thank you, thank you!", "Did you like my song?").random(), 2500)
        }
    }

    // ================================================================ AI rap over a beat
    fun makeSong(topic: String?, style: Synth.Style?) {
        stopAll()
        val s = style ?: listOf(Synth.Style.AMAPIANO, Synth.Style.AFROBEAT, Synth.Style.HIPHOP).random()
        job = scope.launch {
            brain.setEmotion(Emotion.THINKING, 15_000)
            brain.say(if (topic != null) "A song about $topic? Let me write it." else "Let me write you a song.", Emotion.THINKING)
            val lines = writeLyrics(topic)
            val pcm = withContext(Dispatchers.Default) { Synth.toPcm(Synth.renderBeat(s, 4), 0.6f) }
            delay(1200)
            val bar = 4 * 60_000L / s.bpm
            track = Synth.play(pcm, loop = true).also { it.setVolume(0.55f) }
            nowPlaying.value = "VinBot rap (${s.spoken})"
            brain.overlay.value = "🎤 VinBot ft. ${s.spoken} beat"
            val total = bar * (lines.size + 2)
            brain.danceTo(s.bpm, total)
            val t0 = SystemClock.elapsedRealtime()
            lines.forEachIndexed { i, line ->
                val wait = (i + 1) * bar - (SystemClock.elapsedRealtime() - t0)
                if (wait > 0) delay(wait)
                if (i == 0) brain.overlay.value = null
                val rate = (syllables(line) * 240f / bar).coerceIn(0.95f, 1.45f)
                voice.sayWith(line, 1.0f, rate)
            }
            delay(((lines.size + 2) * bar - (SystemClock.elapsedRealtime() - t0)).coerceAtLeast(0))
            stopAll()
            brain.react(Emotion.EXCITED, listOf("Thank you, thank you! VinBot out.", "Mic drop!").random(), 2500)
            brain.perform(Brain.Move.CELEBRATE)
        }
    }

    private suspend fun writeLyrics(topic: String?): List<String> {
        if (Net.online(context) && ai.mode != "none") {
            val about = topic ?: listOf("being a robot in Nairobi", "friendship", "dancing", "my day with Vincent").random()
            val text = ai.complete(
                "Write an original, family friendly rap song about $about for a cute robot called VinBot. " +
                    "Exactly 8 lines, 6 to 9 words each, rhyming in pairs, fun and upbeat. " +
                    "One line per row. No title, no numbering, no emojis, lyrics only."
            )
            val lines = text?.lines()
                ?.map { it.replace(Regex("^[0-9.\\-*\\s]+"), "").replace(Regex("[*_#\"]"), "").trim() }
                ?.filter { it.split(" ").size >= 3 }
                ?.take(8)
            if (lines != null && lines.size >= 4) return lines
        }
        return Songs.offlineRaps.random()
    }

    private fun syllables(line: String): Int =
        line.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            .sumOf { w -> Regex("[aeiouy]+").findAll(w).count().coerceAtLeast(1) }

    // ================================================================ Jamendo (free Creative Commons music)
    data class OnlineTrack(val name: String, val artist: String, val audio: String)

    fun playOnline(query: String?, tag: String?) {
        stopAll()
        if (prefs.jamendoId.isBlank()) {
            brain.react(Emotion.SAD, "I need a free Jamendo music key in my settings to play music online.", 4000)
            return
        }
        if (!Net.online(context)) {
            brain.react(Emotion.SAD, "I'm offline. I can make you a beat instead. Say make a beat.", 3500)
            return
        }
        job = scope.launch {
            brain.setEmotion(Emotion.THINKING, 8000)
            val found = withContext(Dispatchers.IO) { searchJamendo(query, tag) }
            if (found.isEmpty()) {
                brain.react(
                    Emotion.SAD,
                    if (query != null) "I couldn't find $query on Jamendo. Say play $query on Spotify, or on YouTube."
                    else "I couldn't find music right now.",
                    4000,
                )
                return@launch
            }
            playlist = if (query == null) found.shuffled() else found
            playlistIndex = 0
            startTrack()
        }
    }

    fun next() {
        if (playlist.isEmpty()) {
            brain.react(Emotion.CURIOUS, "Nothing is playing. Say play some music.")
            return
        }
        playlistIndex = (playlistIndex + 1) % playlist.size
        startTrack()
    }

    private fun startTrack() {
        stopSound()
        val t = playlist.getOrNull(playlistIndex) ?: return
        brain.say("Playing ${t.name} by ${t.artist}.", Emotion.EXCITED)
        nowPlaying.value = "${t.name} - ${t.artist}"
        // Attribution required by Jamendo's terms
        brain.overlay.value = "♫ ${t.name} — ${t.artist} · Jamendo"
        media = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            setOnPreparedListener {
                it.start()
                brain.danceTo(112, 25_000)
                scope.launch { delay(6000); if (media === it) brain.overlay.value = null }
            }
            setOnCompletionListener { next() }
            setOnErrorListener { _, _, _ ->
                brain.say("That song won't play. Next one.", Emotion.SAD)
                scope.launch { delay(1500); next() }
                true
            }
            try {
                setDataSource(t.audio)
                prepareAsync()
            } catch (e: Exception) {
                brain.react(Emotion.SAD, "I couldn't load that song.")
            }
        }
    }

    private fun searchJamendo(query: String?, tag: String?): List<OnlineTrack> = try {
        val b = "https://api.jamendo.com/v3.0/tracks/".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", prefs.jamendoId)
            .addQueryParameter("format", "json")
            .addQueryParameter("limit", "20")
            .addQueryParameter("audioformat", "mp32")
            .addQueryParameter("order", "popularity_total")
        when {
            tag != null -> b.addQueryParameter("fuzzytags", tag)
            query != null -> b.addQueryParameter("search", query)
        }
        http.newCall(Request.Builder().url(b.build()).build()).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val arr = JSONObject(res.body?.string() ?: "{}").optJSONArray("results") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val audio = o.optString("audio")
                if (audio.isBlank()) null else OnlineTrack(o.optString("name"), o.optString("artist_name"), audio)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    // ================================================================ other music apps
    /** Licensed playback in the phone's own music app. VinBot steps aside while it plays. */
    fun openApp(query: String, app: String?) {
        stopAll()
        val (intent, name) = when {
            app != null && "spotify" in app -> Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage("com.spotify.music").putExtra(SearchManager.QUERY, query) to "Spotify"
            app != null && "youtube music" in app -> Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage("com.google.android.apps.youtube.music").putExtra(SearchManager.QUERY, query) to "YouTube Music"
            app != null && "youtube" in app -> Intent(Intent.ACTION_SEARCH)
                .setPackage("com.google.android.youtube").putExtra("query", query) to "YouTube"
            else -> Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, query) to "your music app"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            brain.say("Opening $query on $name.", Emotion.EXCITED)
            scope.launch {
                delay(1500)
                try { context.startActivity(intent) } catch (e: Exception) {
                    brain.react(Emotion.SAD, "I can't find $name on this phone.")
                }
            }
        } catch (_: Exception) {
        }
    }

    // ================================================================ volume
    fun volume(up: Boolean) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        brain.say(if (up) "Louder!" else "Quieter.", Emotion.HAPPY)
    }

    companion object {
        /** "play some amapiano" -> Jamendo tag. */
        fun genreTag(t: String): String? = when {
            "amapiano" in t -> "amapiano"
            "afro" in t -> "afrobeat"
            "hip hop" in t || "hiphop" in t || "rap" in t -> "hiphop"
            "reggae" in t -> "reggae"
            "gospel" in t -> "gospel"
            "jazz" in t -> "jazz"
            "rock" in t -> "rock"
            "house" in t -> "house"
            "pop" in t -> "pop"
            "chill" in t || "relax" in t -> "chillout"
            "classical" in t -> "classical"
            else -> null
        }
    }
}
