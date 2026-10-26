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
    val speaking = MutableStateFlow(false)
    val available = SpeechRecognizer.isRecognitionAvailable(context)

    private val tts = TextToSpeech(context.applicationContext, this)
    private var ttsReady = false
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
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking.value = true }
            override fun onDone(utteranceId: String?) { speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { speaking.value = false }
        })
        ttsReady = true
    }

    /** preferOffline = true uses the downloaded offline English pack when there is no internet. */
    fun listen(preferOffline: Boolean) {
        val r = recognizer ?: return
        if (listening.value) return
        tts.stop()
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

    fun say(text: String, emotion: Emotion) {
        if (!ttsReady) return
        tts.setPitch(emotion.pitch)
        tts.setSpeechRate(emotion.rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jvin-${System.currentTimeMillis()}")
    }

    fun shutdown() {
        recognizer?.destroy()
        tts.stop()
        tts.shutdown()
    }

    companion object { private const val TAG = "Voice" }
}
