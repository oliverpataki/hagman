package com.wisprflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ClaudeClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun cleanUpText(rawText: String): String = withContext(Dispatchers.IO) {
        val prompt = """Jsi asistent pro čištění přepsaného mluveného textu. \
Oprav gramatiku, interpunkci a formátování. \
Neměň smysl textu. Vrať POUZE opravený text, nic jiného.

Text k opravě: $rawText"""

        val body = JSONObject().apply {
            put("model", "claude-haiku-4-5-20251001")
            put("max_tokens", 1024)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext rawText

        val responseBody = response.body?.string() ?: return@withContext rawText
        val json = JSONObject(responseBody)
        val content = json.getJSONArray("content")
        if (content.length() == 0) return@withContext rawText

        content.getJSONObject(0).getString("text").trim()
    }
}
