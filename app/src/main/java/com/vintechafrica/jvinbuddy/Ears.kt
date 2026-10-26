package com.vintechafrica.jvinbuddy

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * VinBot's ears. It owns the microphone the whole time; nothing else touches it,
 * so there are no Google beeps and no fights over the mic.
 *
 * Two modes:
 *  - COMMANDS: offline speech model listens only for the phrases in Commands.
 *    Results below a confidence level are ignored (stops noise false triggers).
 *  - CAPTURE:  after the wake word, records your sentence until you stop talking
 *    (or 7 s), then hands the audio over. Commands are read from it offline;
 *    questions are sent as audio to Gemini, which hears accents well.
 *
 * Also notices a sudden loud sound (a clap) from the volume level.
 */
class Ears(
    private val context: Context,
    private val onPhrase: (String) -> Unit,
    private val onUrgentStop: () -> Unit,
    private val onCaptured: (ByteArray) -> Unit = {},
    private val onClap: () -> Unit = {},
) {
    val status = MutableStateFlow("ears loading")
    val capturing = MutableStateFlow(false)

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    @Volatile private var wantRunning = false
    @Volatile private var paused = false
    @Volatile private var captureRequested = false
    private var thread: Thread? = null

    fun load() {
        if (model != null) return
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        StorageService.unpack(
            context, "model-en-us", "model",
            { m ->
                model = m
                status.value = "ears ready"
                if (wantRunning) startThread()
            },
            { e ->
                Log.e(TAG, "Model load failed", e)
                status.value = "ears failed"
            },
        )
    }

    fun start() {
        wantRunning = true
        if (model != null && thread == null) startThread()
    }

    fun stop() {
        wantRunning = false
        thread?.interrupt()
        thread = null
        if (model != null) status.value = "ears off"
    }

    fun setPause(p: Boolean) {
        paused = p
    }

    /** Record the next sentence (called after the wake word or a double tap). */
    fun capture() {
        captureRequested = true
    }

    fun shutdown() {
        stop()
        model?.close()
        model = null
    }

    /** Offline best-guess of what was said in a recording (free speech, no phrase list). */
    fun transcribe(pcm: ByteArray): String {
        val m = model ?: return ""
        var rec: Recognizer? = null
        return try {
            rec = Recognizer(m, RATE.toFloat())
            rec.acceptWaveForm(pcm, pcm.size)
            JSONObject(rec.finalResult).optString("text").trim()
        } catch (e: Exception) {
            ""
        } finally {
            rec?.close()
        }
    }

    // ================================================================ mic loop
    @SuppressLint("MissingPermission")
    private fun startThread() {
        val m = model ?: return
        val t = Thread({ loop(m) }, "vinbot-ears")
        thread = t
        t.start()
    }

    @SuppressLint("MissingPermission")
    private fun loop(m: Model) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // The previous loop may still be letting go of the mic: retry for up to 2 s.
        var record: AudioRecord? = null
        for (i in 0 until 7) {
            val r = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4),
                )
            } catch (e: Exception) {
                null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { record = r; break }
            r?.release()
            try { Thread.sleep(300) } catch (_: InterruptedException) { return }
        }
        if (record == null) {
            main.post { status.value = "mic busy" }
            thread = null
            return
        }

        val rec = Recognizer(m, RATE.toFloat(), Commands.grammarJson()).apply { setWords(true) }
        val buf = ShortArray(CHUNK)
        val bytes = ByteArray(CHUNK * 2)
        var noise = 300.0
        var lastClap = 0L
        var wasPaused = false

        // Capture state
        var capturing = false
        val captured = ByteArrayOutputStream()
        var capStart = 0L
        var spoke = false
        var quietSince = 0L

        record.startRecording()
        main.post { status.value = "ears on" }
        try {
            while (!Thread.currentThread().isInterrupted && wantRunning) {
                val n = record.read(buf, 0, CHUNK)
                if (n <= 0) continue
                val now = SystemClock.elapsedRealtime()

                if (paused) {
                    wasPaused = true
                    continue // our own voice: throw it away
                }
                if (wasPaused) {
                    wasPaused = false
                    rec.reset()
                }

                // Volume of this 100 ms
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n)

                // Start capturing if asked
                if (captureRequested) {
                    captureRequested = false
                    capturing = true
                    captured.reset()
                    capStart = now
                    spoke = false
                    quietSince = 0L
                    main.post { this.capturing.value = true }
                }

                if (capturing) {
                    for (i in 0 until n) {
                        bytes[2 * i] = (buf[i].toInt() and 0xFF).toByte()
                        bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte()
                    }
                    captured.write(bytes, 0, n * 2)
                    val speech = rms > noise * 2.5 + 250
                    if (speech) { spoke = true; quietSince = 0L } else if (quietSince == 0L) quietSince = now
                    val elapsed = now - capStart
                    val done = (spoke && quietSince > 0 && now - quietSince > 900) ||
                        elapsed > 7000 || (!spoke && elapsed > 4000)
                    if (done) {
                        capturing = false
                        val audio = if (spoke) captured.toByteArray() else ByteArray(0)
                        rec.reset()
                        main.post {
                            this.capturing.value = false
                            onCaptured(audio)
                        }
                    }
                    continue
                }

                // Background noise level (slow average of quiet moments)
                if (rms < noise * 2) noise = noise * 0.95 + rms * 0.05

                // Clap: a short, very loud spike
                if (rms > noise * 8 + 2000 && now - lastClap > 10_000) {
                    lastClap = now
                    main.post { onClap() }
                }

                // Command listening
                for (i in 0 until n) {
                    bytes[2 * i] = (buf[i].toInt() and 0xFF).toByte()
                    bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte()
                }
                if (rec.acceptWaveForm(bytes, n * 2)) {
                    val (text, conf) = parse(rec.result)
                    if (text.isNotEmpty() && conf >= minConf(text)) main.post { onPhrase(text) }
                } else {
                    val partial = try { JSONObject(rec.partialResult).optString("partial") } catch (_: Exception) { "" }
                    if (partial == "stop" || partial == "freeze") main.post { onUrgentStop() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ears loop error", e)
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            rec.close()
            main.post { if (thread == null || !wantRunning) status.value = "ears off" }
        }
    }

    /** Text and the lowest word confidence. "[unk]" words make the whole result fail. */
    private fun parse(json: String): Pair<String, Float> = try {
        val o = JSONObject(json)
        val text = o.optString("text").trim()
        val words = o.optJSONArray("result")
        var minConf = 1f
        var unknown = false
        if (words != null) {
            for (i in 0 until words.length()) {
                val w = words.getJSONObject(i)
                minConf = minOf(minConf, w.optDouble("conf", 1.0).toFloat())
                if (w.optString("word") == "[unk]") unknown = true
            }
        }
        if (unknown || text.contains("[unk]")) "" to 0f else text to minConf
    } catch (_: Exception) {
        "" to 0f
    }

    /** Wake words need more certainty than commands; "stop" needs the least. */
    private fun minConf(text: String): Float = when {
        Commands.isAlways(text) -> 0.6f
        Commands.isWake(text) -> 0.9f
        else -> 0.8f
    }

    companion object {
        private const val TAG = "Ears"
        const val RATE = 16000
        private const val CHUNK = 1600 // 100 ms
    }
}
