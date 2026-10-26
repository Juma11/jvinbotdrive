package com.vintechafrica.jvinbuddy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Always-on ears. Fully offline (Vosk small English model, bundled in the APK).
 * Listens only for the phrases in Commands, so random talk becomes "[unk]" and is ignored.
 * Paused while the robot speaks so it does not hear itself.
 */
class Ears(
    private val context: Context,
    private val onPhrase: (String) -> Unit,
    private val onUrgentStop: () -> Unit,
) : RecognitionListener {

    val status = MutableStateFlow("ears loading")

    private var model: Model? = null
    private var service: SpeechService? = null
    private var wantRunning = false
    private var paused = false

    fun load() {
        if (model != null) return
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        StorageService.unpack(
            context, "model-en-us", "model",
            { m ->
                model = m
                status.value = "ears ready"
                if (wantRunning) startInternal()
            },
            { e ->
                Log.e(TAG, "Model load failed", e)
                status.value = "ears failed"
            },
        )
    }

    fun start() {
        wantRunning = true
        if (model != null && service == null) startInternal()
    }

    private fun startInternal() {
        val m = model ?: return
        try {
            val rec = Recognizer(m, 16000.0f, Commands.grammarJson())
            service = SpeechService(rec, 16000.0f).also {
                it.startListening(this)
                it.setPause(paused)
            }
            status.value = "ears on"
        } catch (e: Exception) {
            Log.e(TAG, "Ears start failed", e)
            status.value = "ears error"
        }
    }

    fun stop() {
        wantRunning = false
        service?.let {
            it.stop()
            it.shutdown()
        }
        service = null
        if (model != null) status.value = "ears off"
    }

    fun setPause(p: Boolean) {
        paused = p
        service?.setPause(p)
    }

    fun shutdown() {
        stop()
        model?.close()
        model = null
    }

    // ---------------------------------------------------------------- Vosk callbacks (main thread)
    override fun onPartialResult(hypothesis: String?) {
        val t = read(hypothesis, "partial")
        // React to "stop" mid-word instead of waiting for silence.
        if (t == "stop" || t == "freeze") onUrgentStop()
    }

    override fun onResult(hypothesis: String?) {
        val t = read(hypothesis, "text")
        if (t.isNotEmpty()) onPhrase(t)
    }

    override fun onFinalResult(hypothesis: String?) {
        // Fires once when stop() is called. Ignored to avoid double commands.
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Ears error", exception)
        status.value = "ears error"
    }

    override fun onTimeout() {}

    private fun read(json: String?, key: String): String = try {
        JSONObject(json ?: "{}").optString(key).replace("[unk]", "").trim()
    } catch (_: Exception) {
        ""
    }

    companion object { private const val TAG = "Ears" }
}
