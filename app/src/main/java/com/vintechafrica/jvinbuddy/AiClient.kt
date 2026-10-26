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

/** Calls your Cloudflare Worker. The AI key stays on the Worker, never in the app. */
class AiClient(private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val history = ArrayDeque<Pair<String, String>>() // role, text

    suspend fun ask(text: String): Pair<Emotion, String> = withContext(Dispatchers.IO) {
        val url = prefs.workerUrl
        if (url.isBlank()) {
            return@withContext Emotion.SAD to "I have no brain link yet. Long press my face and add the worker address."
        }
        val hist = JSONArray()
        history.forEach { (role, t) -> hist.put(JSONObject().put("role", role).put("text", t)) }
        val body = JSONObject().put("text", text).put("history", hist).toString()

        try {
            val request = Request.Builder()
                .url(url)
                .header("X-Robot-Token", prefs.token)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { res ->
                if (res.code == 401) return@withContext Emotion.SAD to "My brain does not know me. Check the robot token."
                if (!res.isSuccessful) return@withContext Emotion.SAD to "My brain is not answering."
                val json = JSONObject(res.body?.string() ?: "{}")
                val reply = json.optString("reply", "Hmm.")
                val emotion = Emotion.parse(json.optString("emotion"))
                remember("user", text)
                remember("assistant", reply)
                emotion to reply
            }
        } catch (e: Exception) {
            Emotion.SAD to "I can't reach the internet right now."
        }
    }

    private fun remember(role: String, text: String) {
        history.addLast(role to text)
        while (history.size > 8) history.removeFirst()
    }
}
