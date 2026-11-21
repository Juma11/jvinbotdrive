package com.vintechafrica.jvinbuddy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ears: Android SpeechRecognizer through the Google app. Online by default;
 * offline when the offline English pack is downloaded in the Google app.
 * Mouth: Android TextToSpeech, with pitch and speed set per emotion.
 * Create and call this on the main thread.
 */
class Voice(
    context: Context,
    private val onHeard: (String) -> Unit,
    private val onListenEnd: () -> Unit,
    private val onListenError: (Int) -> Unit,
) : TextToSpeech.OnInitListener {

    val listening = MutableStateFlow(false)
    /** True while the app is in the background: nothing is spoken. */
    @Volatile var muted = false
    val speaking = MutableStateFlow(false)
    val available = SpeechRecognizer.isRecognitionAvailable(context)

    private val tts = TextToSpeech(context.applicationContext, this, googleEngine(context))
    private var ttsReady = false
    private val pending = AtomicInteger(0)
    private val recognizer: SpeechRecognizer? =
        if (available) SpeechRecognizer.createSpeechRecognizer(context) else null

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                Log.w(TAG, "Recognizer error $error")
                finishListening()
                onListenError(error)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                finishListening()
                if (!text.isNullOrBlank()) onHeard(text)
            }
        })
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS init failed")
            return
        }
        val uk = tts.setLanguage(Locale.UK)
        if (uk == TextToSpeech.LANG_MISSING_DATA || uk == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }
        // Best installed English voice: highest quality, works offline, UK first, then US.
        tts.voices?.filter { v ->
            v.locale.language == "en" && !v.isNetworkConnectionRequired &&
                v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }?.maxWithOrNull(
            compareBy<android.speech.tts.Voice>({ it.quality }, { it.locale.country == "GB" }, { it.locale.country == "US" })
        )?.let { tts.voice = it }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // "speaking" stays true until every queued piece is done (songs are many pieces).
            override fun onStart(utteranceId: String?) { speaking.value = true }
            override fun onDone(utteranceId: String?) {
                if (pending.decrementAndGet() <= 0) {
                    pending.set(0)
                    speaking.value = false
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onDone(utteranceId)
        })
        ttsReady = true
    }

    /** preferOffline = true uses the downloaded offline English pack when there is no internet. */
    fun listen(preferOffline: Boolean) {
        val r = recognizer ?: return
        if (listening.value) return
        tts.stop()
        pending.set(0)
        speaking.value = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }
        listening.value = true
        r.startListening(intent)
    }

    private fun finishListening() {
        if (!listening.value) return
        listening.value = false
        onListenEnd()
    }

    /** Stop talking now and forget anything queued. */
    fun silence() {
        tts.stop()
        pending.set(0)
        speaking.value = false
    }

    fun say(text: String, emotion: Emotion) {
        if (!ttsReady || muted) return
        pending.set(1)
        speaking.value = true
        // Emotions shift the voice gently; big jumps sound like a chipmunk.
        tts.setPitch(1f + (emotion.pitch - 1f) * 0.4f)
        tts.setSpeechRate(1f + (emotion.rate - 1f) * 0.6f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jvin-${System.currentTimeMillis()}")
    }

    /** Say a line with a custom pitch and speed (songs and raps). */
    fun sayWith(text: String, pitch: Float, rate: Float) {
        if (!ttsReady || muted) return
        pending.set(1)
        speaking.value = true
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "line-${System.currentTimeMillis()}")
    }

    fun shutdown() {
        recognizer?.destroy()
        tts.stop()
        tts.shutdown()
    }

    companion object {
        private const val TAG = "Voice"
        private const val GOOGLE_TTS = "com.google.android.tts"

        /** Google's voices sound far more natural than most phone makers' default engines. */
        private fun googleEngine(context: Context): String? = try {
            context.packageManager.getPackageInfo(GOOGLE_TTS, 0)
            GOOGLE_TTS
        } catch (_: Exception) {
            null
        }
    }
}
