package com.velotrack.velotrack

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object GeminiClient {
    class GeminiProxyException(
        val reason: Reason,
        message: String,
    ) : IllegalStateException(message) {
        enum class Reason {
            MissingApiKey,
            RateLimited,
            ServerRejected,
            EmptyResponse,
            Network,
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Uses `gemini-2.0-flash` for broad API availability; h5 referenced `gemini-3-flash-preview`.
     */
    private const val MODEL = "gemini-2.0-flash"

    fun generateContent(apiKey: String, prompt: String): String {
        if (apiKey.isBlank()) {
            throw GeminiProxyException(
                GeminiProxyException.Reason.MissingApiKey,
                "AI_PROXY_FAILED: GEMINI_API_KEY is not configured",
            )
        }
        return retryNetworkErrors {
            executeGenerateContent(apiKey, prompt)
        }
    }

    private fun executeGenerateContent(apiKey: String, prompt: String): String {
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
                throw GeminiProxyException(
                    if (response.code == 429) {
                        GeminiProxyException.Reason.RateLimited
                    } else {
                        GeminiProxyException.Reason.ServerRejected
                    },
                    "AI_PROXY_FAILED: HTTP ${response.code}: $raw",
                )
            }
            val root = JSONObject(raw)
            val candidates = root.optJSONArray("candidates")
                ?: throw GeminiProxyException(
                    GeminiProxyException.Reason.EmptyResponse,
                    "AI_PROXY_FAILED: No candidates in response",
                )
            if (candidates.length() == 0) {
                throw GeminiProxyException(GeminiProxyException.Reason.EmptyResponse, "AI_PROXY_FAILED: Empty candidates")
            }
            val text = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .optString("text")
            if (text.isBlank()) {
                throw GeminiProxyException(GeminiProxyException.Reason.EmptyResponse, "AI_PROXY_FAILED: Empty model text")
            }
            return text
        }
    }

    private fun retryNetworkErrors(block: () -> String): String {
        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                return block()
            } catch (e: IOException) {
                lastError = e
                if (attempt == 1) {
                    throw GeminiProxyException(
                        GeminiProxyException.Reason.Network,
                        "AI_PROXY_FAILED: Network request failed: ${e.message}",
                    )
                }
                Thread.sleep(350L)
            }
        }
        throw GeminiProxyException(
            GeminiProxyException.Reason.Network,
            "AI_PROXY_FAILED: Network request failed: ${lastError?.message}",
        )
    }
}
