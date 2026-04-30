package com.velotrack.velotrack

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object GeminiClient {
    class GeminiProxyException(
        val reason: Reason,
        message: String,
        val httpCode: Int? = null,
        val errorStatus: String? = null,
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

    /** Ordered by preference. Some API keys/projects may not have access to newer models. */
    private val MODELS = listOf(
        "gemini-3-flash-preview",
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-1.5-flash-8b",
    )

    fun generateContent(apiKey: String, prompt: String, requestId: String = "manual"): String {
        Log.d(
            LOG_TAG,
            "generateContent enter requestId=$requestId models=${MODELS.joinToString()} " +
                "apiKeyConfigured=${apiKey.isNotBlank()} promptChars=${prompt.length}",
        )
        if (apiKey.isBlank()) {
            Log.w(LOG_TAG, "generateContent missing api key requestId=$requestId")
            throw GeminiProxyException(
                GeminiProxyException.Reason.MissingApiKey,
                "AI_PROXY_FAILED: GEMINI_API_KEY is not configured",
            )
        }
        var lastModelError: GeminiProxyException? = null
        MODELS.forEachIndexed { index, model ->
            try {
                return retryNetworkErrors(requestId, model) {
                    executeGenerateContent(apiKey, prompt, requestId, model)
                }
            } catch (error: GeminiProxyException) {
                val canFallback = error.httpCode == 404 && error.errorStatus == "NOT_FOUND" && index < MODELS.lastIndex
                Log.w(
                    LOG_TAG,
                    "model attempt failed requestId=$requestId model=$model reason=${error.reason} " +
                        "httpCode=${error.httpCode ?: "-"} status=${error.errorStatus ?: "-"} willFallback=$canFallback",
                )
                if (!canFallback) throw error
                lastModelError = error
            }
        }
        throw lastModelError ?: GeminiProxyException(
            GeminiProxyException.Reason.ServerRejected,
            "AI_PROXY_FAILED: no Gemini model attempts were made",
        )
    }

    private fun executeGenerateContent(apiKey: String, prompt: String, requestId: String, model: String): String {
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
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
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .post(body)
            .build()
        val startedAt = System.currentTimeMillis()
        Log.d(LOG_TAG, "http start requestId=$requestId model=$model requestChars=${bodyJson.length}")
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val elapsedMs = System.currentTimeMillis() - startedAt
            Log.d(
                LOG_TAG,
                "http end requestId=$requestId model=$model code=${response.code} success=${response.isSuccessful} " +
                    "elapsedMs=$elapsedMs responseChars=${raw.length}",
            )
            if (!response.isSuccessful) {
                val errorStatus = responseErrorStatus(raw)
                Log.w(
                    LOG_TAG,
                    "http rejected requestId=$requestId model=$model code=${response.code} errorStatus=${errorStatus ?: "unknown"} " +
                        "responseChars=${raw.length}",
                )
                throw GeminiProxyException(
                    if (response.code == 429) {
                        GeminiProxyException.Reason.RateLimited
                    } else {
                        GeminiProxyException.Reason.ServerRejected
                    },
                    "AI_PROXY_FAILED: HTTP ${response.code}, status=${errorStatus ?: "unknown"}",
                    httpCode = response.code,
                    errorStatus = errorStatus,
                )
            }
            val root = try {
                JSONObject(raw)
            } catch (error: JSONException) {
                Log.w(LOG_TAG, "parse root failed requestId=$requestId model=$model responseChars=${raw.length} type=${error::class.java.simpleName}")
                throw GeminiProxyException(
                    GeminiProxyException.Reason.EmptyResponse,
                    "AI_PROXY_FAILED: Invalid JSON response",
                )
            }
            val candidates = root.optJSONArray("candidates")
                ?: throw GeminiProxyException(
                    GeminiProxyException.Reason.EmptyResponse,
                    "AI_PROXY_FAILED: No candidates in response",
                )
            Log.d(LOG_TAG, "parse candidates requestId=$requestId model=$model candidateCount=${candidates.length()}")
            if (candidates.length() == 0) {
                Log.w(LOG_TAG, "empty candidates requestId=$requestId model=$model")
                throw GeminiProxyException(GeminiProxyException.Reason.EmptyResponse, "AI_PROXY_FAILED: Empty candidates")
            }
            val first = candidates.getJSONObject(0)
            val parts = first.optJSONObject("content")?.optJSONArray("parts")
            Log.d(LOG_TAG, "parse parts requestId=$requestId model=$model hasContent=${first.has("content")} partCount=${parts?.length() ?: 0}")
            val text = parts?.optJSONObject(0)?.optString("text").orEmpty()
            if (text.isBlank()) {
                Log.w(LOG_TAG, "empty model text requestId=$requestId model=$model")
                throw GeminiProxyException(GeminiProxyException.Reason.EmptyResponse, "AI_PROXY_FAILED: Empty model text")
            }
            Log.d(LOG_TAG, "parse success requestId=$requestId model=$model textChars=${text.length}")
            return text
        }
    }

    private fun retryNetworkErrors(requestId: String, model: String, block: () -> String): String {
        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                Log.d(LOG_TAG, "attempt start requestId=$requestId model=$model attempt=${attempt + 1}")
                return block()
            } catch (e: IOException) {
                lastError = e
                Log.w(
                    LOG_TAG,
                    "network error requestId=$requestId model=$model attempt=${attempt + 1} willRetry=${attempt == 0} type=${e::class.java.simpleName}",
                )
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

    private fun responseErrorStatus(raw: String): String? = runCatching {
        JSONObject(raw).optJSONObject("error")?.optString("status")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private const val LOG_TAG = "VeloAI"
}
