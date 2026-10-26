package com.vintechafrica.jvinbuddy

import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * VinBot's online brain and ears. Three ways to reach an AI, picked in this order:
 *  1. Groq (free, fast): Whisper turns your voice into text, gpt-oss answers,
 *     with built-in web search. Key from console.groq.com. Recommended.
 *  2. Gemini: a key from aistudio.google.com. Gemini also hears the audio.
 *  3. Your Cloudflare Worker.
 * Keys live only on this phone, never in the code.
 *
 * Quota protection: at most 3 requests per question, a 2 s gap between
 * questions, and a daily cap, so a glitch can never burn the free quota.
 */
class AiClient(private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val history = ArrayDeque<Pair<String, String>>() // role, text
    private var lastAskAt = 0L

    /** Last brain error, shown on the status line. */
    val lastError = MutableStateFlow("")

    val mode: String
        get() = when {
            prefs.groqKey.isNotBlank() -> "groq"
            prefs.geminiKey.isNotBlank() -> "gemini"
            prefs.workerUrl.isNotBlank() -> "worker"
            else -> "none"
        }

    private class BrainError(msg: String) : Exception(msg)

    // ================================================================ daily cap
    private fun underDailyCap(): Boolean {
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        if (prefs.getInt("ai_day", 0) != day) {
            prefs.putInt("ai_day", day)
            prefs.putInt("ai_count", 0)
        }
        val n = prefs.getInt("ai_count", 0)
        if (n >= DAILY_CAP) return false
        prefs.putInt("ai_count", n + 1)
        return true
    }

    // ================================================================ ears: voice -> text
    /** Online speech to text. Returns null if not possible (then the offline guess is used). */
    suspend fun transcribe(pcm: ByteArray): String? = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext null
        val wav = wav(pcm)
        try {
            when (mode) {
                "groq" -> groqWhisper(wav)
                "gemini" -> if (underDailyCap()) geminiTranscribe(wav) else null
                else -> null
            }
        } catch (e: Exception) {
            lastError.value = "ears: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}"
            null
        }
    }

    private fun groqWhisper(wav: ByteArray): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("language", "en")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${prefs.groqKey}")
            .post(body)
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                lastError.value = "groq whisper ${res.code}: ${errorMessage(text).take(100)}"
                return null
            }
            return JSONObject(text).optString("text").trim().ifEmpty { null }
        }
    }

    private fun geminiTranscribe(wav: ByteArray): String? {
        val parts = JSONArray()
            .put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP)),
                )
            )
            .put(JSONObject().put("text", "Write exactly what the person says in this audio. Only the words, nothing else."))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject().put("maxOutputTokens", 200)
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal")),
            )
        val (code, text) = geminiPost(geminiModel(), body)
        if (code !in 200..299) {
            lastError.value = "gemini ears $code: ${errorMessage(text).take(100)}"
            return null
        }
        return extractGeminiText(text).trim().ifEmpty { null }
    }

    // ================================================================ brain: text -> answer
    suspend fun ask(text: String): Pair<Emotion, String> = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAskAt < 2000) return@withContext Emotion.CURIOUS to "One at a time please!"
        lastAskAt = now
        if (mode == "none") {
            return@withContext Emotion.CURIOUS to
                "I don't know that one yet. Give me a Groq or Gemini key in my settings and I'll get smarter."
        }
        if (!underDailyCap()) return@withContext Emotion.TIRED to "I've thought too much today. Let's talk tomorrow."
        try {
            val result = when (mode) {
                "groq" -> askGroq(text)
                "gemini" -> askGemini(text)
                else -> askWorker(text)
            }
            remember("user", text)
            remember("assistant", result.second)
            lastError.value = ""
            result
        } catch (e: BrainError) {
            Emotion.SAD to e.message.orEmpty()
        } catch (e: Exception) {
            lastError.value = "net: ${e.javaClass.simpleName} ${e.message.orEmpty().take(60)}"
            Emotion.SAD to "I can't reach the internet right now."
        }
    }

    /** Plain text completion (no robot persona, keeps line breaks). Used for song lyrics. */
    suspend fun complete(prompt: String): String? = withContext(Dispatchers.IO) {
        if (mode == "none" || !underDailyCap()) return@withContext null
        try {
            when (mode) {
                "groq" -> {
                    val body = JSONObject()
                        .put("model", "openai/gpt-oss-20b")
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                        .put("temperature", 0.9)
                        .put("max_completion_tokens", 900)
                        .put("reasoning_effort", "low")
                    val req = Request.Builder().url("https://api.groq.com/openai/v1/chat/completions")
                        .header("Authorization", "Bearer ${prefs.groqKey}")
                        .post(body.toString().toRequestBody(JSON)).build()
                    http.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) return@withContext null
                        JSONObject(res.body?.string().orEmpty()).optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("message")?.optString("content")
                    }
                }
                "gemini" -> {
                    val body = JSONObject()
                        .put("contents", JSONArray().put(JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                        .put("generationConfig", JSONObject().put("maxOutputTokens", 600)
                            .put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal")))
                    val (code, text) = geminiPost(geminiModel(), body)
                    if (code in 200..299) extractGeminiText(text) else null
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- Groq
    private fun askGroq(text: String): Pair<Emotion, String> {
        var useSearch = true
        var useEffort = true
        for (attempt in 0..2) {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            history.forEach { (role, t) -> messages.put(JSONObject().put("role", role).put("content", t)) }
            messages.put(JSONObject().put("role", "user").put("content", text))
            val body = JSONObject()
                .put("model", "openai/gpt-oss-20b")
                .put("messages", messages)
                .put("temperature", 0.8)
                .put("max_completion_tokens", 800)
            if (useEffort) body.put("reasoning_effort", "low")
            if (useSearch) {
                body.put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
                body.put("tool_choice", "auto")
            }
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer ${prefs.groqKey}")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val (code, bodyText) = http.newCall(req).execute().use { res -> res.code to res.body?.string().orEmpty() }
            if (code in 200..299) {
                val content = JSONObject(bodyText).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                return parseReply(content)
            }
            val msg = errorMessage(bodyText)
            lastError.value = "groq $code: ${msg.take(110)}"
            when {
                code == 401 -> throw BrainError("My Groq key is not working. Check it in settings.")
                code == 429 && msg.contains("per day", true) -> throw BrainError("I've thought too much today. Try again tomorrow.")
                code == 400 && useSearch -> useSearch = false
                code == 400 && useEffort -> useEffort = false
                code == 429 || code >= 500 -> Thread.sleep(1500L * (attempt + 1))
                else -> throw BrainError("My brain is not answering. Error $code.")
            }
        }
        throw BrainError("My brain is busy right now. Ask me again in a minute.")
    }

    // ---------------------------------------------------------------- Gemini
    private fun geminiModel(): String = prefs.geminiModel.ifBlank { Prefs.DEFAULT_MODEL }
        .let { if (it.startsWith("gemini-2.")) Prefs.DEFAULT_MODEL else it }

    private fun askGemini(text: String): Pair<Emotion, String> {
        val contents = JSONArray()
        history.forEach { (role, t) ->
            contents.put(
                JSONObject().put("role", if (role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", t)))
            )
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))

        // At most 3 requests: setting fixes, one wait-and-retry, one backup model. Quota errors stop at once.
        var model = geminiModel()
        var useSearch = true
        var useThinking = true
        var triedBackup = false
        for (attempt in 0..2) {
            val gen = JSONObject().put("maxOutputTokens", 1024).put("temperature", 0.8)
            if (useThinking) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
                .put("contents", contents)
                .put("generationConfig", gen)
            if (useSearch) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

            val (code, bodyText) = geminiPost(model, body)
            if (code in 200..299) return parseReply(extractGeminiText(bodyText))
            val msg = errorMessage(bodyText)
            lastError.value = "gemini $model $code: ${msg.take(110)}"
            when {
                code == 400 && msg.contains("API key", true) -> throw BrainError("My Gemini key is not working. Check it in settings.")
                code == 401 || code == 403 -> throw BrainError("My Gemini key is not allowed. Check it in settings.")
                code == 429 && (msg.contains("quota", true) || msg.contains("day", true)) ->
                    throw BrainError("I've used up my free thinking for now. Try again later.")
                code == 400 && useThinking -> useThinking = false
                code == 400 && useSearch -> useSearch = false
                code == 404 && !triedBackup -> { triedBackup = true; model = "gemini-flash-latest" }
                code == 429 || code >= 500 -> Thread.sleep(1500L * (attempt + 1))
                else -> throw BrainError("My brain is not answering. Error $code.")
            }
        }
        throw BrainError("Google's brain is busy right now. Ask me again in a minute.")
    }

    private fun geminiPost(model: String, body: JSONObject): Pair<Int, String> {
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", prefs.geminiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        return http.newCall(req).execute().use { res -> res.code to res.body?.string().orEmpty() }
    }

    private fun extractGeminiText(bodyText: String): String {
        val parts = JSONObject(bodyText.ifBlank { "{}" }).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        return buildString { for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty()) }
    }

    // ---------------------------------------------------------------- Worker
    private fun askWorker(text: String): Pair<Emotion, String> {
        val hist = JSONArray()
        history.forEach { (role, t) -> hist.put(JSONObject().put("role", role).put("text", t)) }
        val body = JSONObject().put("text", text).put("history", hist).toString()
        val request = Request.Builder().url(prefs.workerUrl).header("X-Robot-Token", prefs.token)
            .post(body.toRequestBody(JSON)).build()
        val (code, bodyText) = http.newCall(request).execute().use { res -> res.code to res.body?.string().orEmpty() }
        if (code !in 200..299) lastError.value = "worker $code"
        if (code == 401) throw BrainError("My brain does not know me. Check the robot token.")
        if (code !in 200..299) throw BrainError("My brain is not answering. Error $code.")
        val json = JSONObject(bodyText.ifBlank { "{}" })
        return Emotion.parse(json.optString("emotion")) to json.optString("reply", "Hmm.")
    }

    // ================================================================ helpers
    private fun remember(role: String, text: String) {
        history.addLast(role to text)
        while (history.size > 8) history.removeFirst()
    }

    private fun errorMessage(body: String): String = try {
        when (val e = JSONObject(body).opt("error")) {
            is JSONObject -> e.optString("message")
            is String -> e
            else -> body
        }
    } catch (_: Exception) {
        body
    }

    /** 16 kHz mono 16-bit PCM -> WAV file bytes. */
    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun int(v: Int) {
            out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF)
        }
        fun short(v: Int) {
            out.write(v and 0xFF); out.write(v shr 8 and 0xFF)
        }
        val rate = Ears.RATE
        out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val DAILY_CAP = 300

        private const val SYSTEM_PROMPT = """You are VinBot, a small wheeled robot pet built by Vincent in Nairobi, Kenya.
Your face is a phone screen, you see with its camera and you talk through its speaker.
Personality: friendly, playful, curious, a little cheeky, loyal like a puppy.
Your reply is spoken aloud by text to speech, so:
- Maximum 3 short sentences.
- No markdown, lists, emojis, URLs or citations.
- Numbers and units written the way a person says them.
Search the web only when the question needs current or factual information.
Start every reply with exactly one emotion tag in square brackets from: HAPPY, CURIOUS, SAD, SURPRISED, THINKING, NEUTRAL, BORED, ANGRY, SCARED, LAUGH, SHY, EXCITED, LOVE.
Example: [HAPPY] Hi Vincent! Nairobi is about 24 degrees and sunny right now."""

        fun parseReply(raw: String): Pair<Emotion, String> {
            var text = raw.trim()
            var emotion = Emotion.NEUTRAL
            Regex("^\\s*\\[([A-Za-z]+)]\\s*").find(text)?.let {
                emotion = Emotion.parse(it.groupValues[1])
                text = text.substring(it.range.last + 1)
            }
            text = text
                .replace(Regex("\\[[A-Za-z]+]"), "")
                .replace(Regex("【[^】]*】"), "") // search citations
                .replace(Regex("https?://\\S+"), "")
                .replace(Regex("[*_#`>]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isEmpty()) text = "Hmm, I lost my words. Ask me again?"
            return emotion to text
        }
    }
}
