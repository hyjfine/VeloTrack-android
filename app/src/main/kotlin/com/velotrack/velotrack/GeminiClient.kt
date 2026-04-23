package com.velotrack.velotrack

import com.velotrack.pigeon.FlutterError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Uses `gemini-2.0-flash` for broad API availability; h5 referenced `gemini-3-flash-preview`.
     */
    private const val MODEL = "gemini-2.0-flash"

    fun generateContent(apiKey: String, prompt: String): String {
        if (apiKey.isBlank()) {
            throw FlutterError("AI_PROXY_FAILED", "GEMINI_API_KEY is not configured", "")
        }
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey"
        val bodyJson = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt)),
                    ),
                ),
            )
        }.toString()
        val body = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw FlutterError("AI_PROXY_FAILED", "HTTP ${response.code}: $raw", "")
            }
            val root = JSONObject(raw)
            val candidates = root.optJSONArray("candidates") ?: throw FlutterError(
                "AI_PROXY_FAILED",
                "No candidates in response",
                "",
            )
            if (candidates.length() == 0) {
                throw FlutterError("AI_PROXY_FAILED", "Empty candidates", "")
            }
            val text = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .optString("text")
            if (text.isBlank()) {
                throw FlutterError("AI_PROXY_FAILED", "Empty model text", "")
            }
            return text
        }
    }
}
