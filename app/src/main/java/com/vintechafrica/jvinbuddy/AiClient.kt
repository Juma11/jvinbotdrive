package com.vintechafrica.jvinbuddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Two ways to reach the AI:
 *  1. Direct: paste a Gemini API key in settings. Simplest. The key lives only
 *     on this phone, never in the code or the GitHub repo.
 *  2. Worker: your Cloudflare Worker URL + robot token. Use this if you later
 *     share the app with other people, so they never see your key.
 * The Worker wins if both are set.
 */
class AiClient(private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val history = ArrayDeque<Pair<String, String>>() // role, text

    val mode: String
        get() = when {
            prefs.workerUrl.isNotBlank() -> "worker"
            prefs.geminiKey.isNotBlank() -> "gemini"
            else -> "none"
        }

    suspend fun ask(text: String): Pair<Emotion, String> = withContext(Dispatchers.IO) {
        try {
            val result = when (mode) {
                "worker" -> askWorker(text)
                "gemini" -> askGemini(text)
                else -> return@withContext Emotion.CURIOUS to
                    "I don't know that one yet. Give me a Gemini key in my settings and I'll get smarter."
            }
            remember("user", text)
            remember("assistant", result.second)
            result
        } catch (e: BrainError) {
            Emotion.SAD to e.message.orEmpty()
        } catch (e: Exception) {
            Emotion.SAD to "I can't reach the internet right now."
        }
    }

    private class BrainError(msg: String) : Exception(msg)

    // ---------------------------------------------------------------- worker
    private fun askWorker(text: String): Pair<Emotion, String> {
        val hist = JSONArray()
        history.forEach { (role, t) -> hist.put(JSONObject().put("role", role).put("text", t)) }
        val body = JSONObject().put("text", text).put("history", hist).toString()
        val request = Request.Builder()
            .url(prefs.workerUrl)
            .header("X-Robot-Token", prefs.token)
            .post(body.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { res ->
            if (res.code == 401) throw BrainError("My brain does not know me. Check the robot token.")
            if (!res.isSuccessful) throw BrainError("My brain is not answering.")
            val json = JSONObject(res.body?.string() ?: "{}")
            return Emotion.parse(json.optString("emotion")) to json.optString("reply", "Hmm.")
        }
    }

    // ---------------------------------------------------------------- direct Gemini
    private fun askGemini(text: String): Pair<Emotion, String> {
        val contents = JSONArray()
        history.forEach { (role, t) ->
            contents.put(
                JSONObject()
                    .put("role", if (role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", t)))
            )
        }
        contents.put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text)))
        )
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 400).put("temperature", 0.8))

        val model = prefs.geminiModel.ifBlank { "gemini-2.5-flash" }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", prefs.geminiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { res ->
            if (res.code == 400 || res.code == 403) throw BrainError("My Gemini key is not working. Check it in settings.")
            if (res.code == 429) throw BrainError("I've thought too much today. Try again later.")
            if (!res.isSuccessful) throw BrainError("My brain is not answering.")
            val json = JSONObject(res.body?.string() ?: "{}")
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val raw = buildString {
                for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
            return parseReply(raw)
        }
    }

    private fun remember(role: String, text: String) {
        history.addLast(role to text)
        while (history.size > 8) history.removeFirst()
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        private const val SYSTEM_PROMPT = """You are JVin, a small wheeled robot pet built by Vincent in Nairobi, Kenya.
Your face is a phone screen, you see with its camera and you talk through its speaker.
Personality: friendly, playful, curious, a little cheeky, loyal like a puppy.
Your reply is spoken aloud by text to speech, so:
- Maximum 3 short sentences.
- No markdown, lists, emojis, URLs or citations.
- Numbers and units written the way a person says them.
Use web search only when the question needs current or factual information.
Start every reply with exactly one emotion tag in square brackets from: HAPPY, CURIOUS, SAD, SURPRISED, THINKING, NEUTRAL, BORED.
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
                .replace(Regex("https?://\\S+"), "")
                .replace(Regex("[*_#`>]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isEmpty()) text = "Hmm, I lost my words. Ask me again?"
            return emotion to text
        }
    }
}
