package com.vintechafrica.jvinbuddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The Gemini key wins if both are set.
 */
class AiClient(private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val history = ArrayDeque<Pair<String, String>>() // role, text

    /** Last brain error with its HTTP code, shown on the status line for debugging. */
    val lastError = MutableStateFlow("")

    val mode: String
        get() = when {
            prefs.geminiKey.isNotBlank() -> "gemini"
            prefs.workerUrl.isNotBlank() -> "worker"
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
            lastError.value = ""
            result
        } catch (e: BrainError) {
            Emotion.SAD to e.message.orEmpty()
        } catch (e: Exception) {
            lastError.value = "net: ${e.javaClass.simpleName} ${e.message.orEmpty().take(60)}"
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
            if (!res.isSuccessful) lastError.value = "worker ${res.code}: ${res.body?.string().orEmpty().take(120)}"
            if (res.code == 401) throw BrainError("My brain does not know me. Check the robot token.")
            if (res.code == 404) throw BrainError("I can't find my worker. Clear the worker address in settings.")
            if (!res.isSuccessful) throw BrainError("My brain is not answering. Error ${res.code}.")
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
        // Try the chosen model twice, then a lighter model (it has its own free quota).
        // Busy (503) and per-minute limits (429) are retried after a short wait.
        val models = listOf(prefs.geminiModel.ifBlank { "gemini-2.5-flash" }, "gemini-2.5-flash-lite").distinct()
        var useSearch = true
        var dailyLimitHit = false
        for (model in models) {
            for (attempt in 0..1) {
                val (code, bodyText) = callGemini(model, contents, useSearch)
                if (code in 200..299) {
                    lastError.value = ""
                    return parseReply(extractText(bodyText))
                }
                val msg = try {
                    JSONObject(bodyText).optJSONObject("error")?.optString("message").orEmpty()
                } catch (_: Exception) { bodyText }
                lastError.value = "gemini $model $code: ${msg.take(110)}"

                when {
                    code == 400 && msg.contains("API key", ignoreCase = true) ->
                        throw BrainError("My Gemini key is not working. Check it in settings.")
                    code == 401 || code == 403 ->
                        throw BrainError("My Gemini key is not allowed. Check it in settings.")
                    code == 400 && useSearch -> useSearch = false          // retry without web search
                    code == 429 && msg.contains("day", ignoreCase = true) -> {
                        dailyLimitHit = true
                        break                                              // next model
                    }
                    code == 404 -> break                                   // model missing, next model
                    code == 429 || code >= 500 -> Thread.sleep(1200L * (attempt + 1))
                    else -> break
                }
            }
        }
        if (dailyLimitHit) throw BrainError("I've thought too much today. Try again tomorrow.")
        throw BrainError("Google's brain is busy right now. Ask me again in a minute.")
    }

    private fun callGemini(model: String, contents: JSONArray, useSearch: Boolean): Pair<Int, String> {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", 400)
                    .put("temperature", 0.8)
                    // No hidden "thinking": faster replies, and it cannot eat the 400-token limit.
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            )
        if (useSearch) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", prefs.geminiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { res ->
            return res.code to res.body?.string().orEmpty()
        }
    }

    private fun extractText(bodyText: String): String {
        val json = JSONObject(bodyText.ifBlank { "{}" })
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        return buildString {
            for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
    }

    private fun remember(role: String, text: String) {
        history.addLast(role to text)
        while (history.size > 8) history.removeFirst()
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        private const val SYSTEM_PROMPT = """You are VinBot, a small wheeled robot pet built by Vincent in Nairobi, Kenya.
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
