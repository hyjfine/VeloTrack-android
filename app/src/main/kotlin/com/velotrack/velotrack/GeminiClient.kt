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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
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
            MissingModel,
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

    suspend fun generateContent(
        apiKey: String,
        prompt: String,
        requestId: String = "manual",
        proxyUrl: String = "",
        model: String = BuildConfig.GEMINI_MODEL,
    ): String = withTimeout(60_000L) {
        if (proxyUrl.isNotBlank()) {
            return@withTimeout retryNetworkErrors(requestId, "proxy") {
                executeProxy(proxyUrl, prompt, requestId)
            }
        }
        if (apiKey.isBlank()) throw GeminiProxyException(
            GeminiProxyException.Reason.MissingApiKey, "AI proxy or debug key is not configured")
        if (model.isBlank() || !model.matches(Regex("[A-Za-z0-9._-]+"))) throw GeminiProxyException(
            GeminiProxyException.Reason.MissingModel, "Configure GEMINI_MODEL for debug direct access")
        retryNetworkErrors(requestId, model) {
            executeGenerateContent(apiKey, prompt, requestId, model)
        }
    }

    private data class HttpResult(val code: Int, val raw: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    /** The response is consumed and closed before resuming; cancellation also aborts body reads. */
    private suspend fun execute(request: Request): HttpResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val body = response.body
                        val source = body?.source()
                        source?.request(MAX_RESPONSE_BYTES + 1)
                        if ((source?.buffer?.size ?: 0L) > MAX_RESPONSE_BYTES) {
                            throw GeminiProxyException(GeminiProxyException.Reason.ServerRejected, "AI response exceeds size limit")
                        }
                        HttpResult(response.code, body?.string().orEmpty())
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

    private suspend fun executeProxy(proxyUrl: String, prompt: String, requestId: String): String {
        val bodyJson = JSONObject()
            .put("requestId", requestId)
            .put("prompt", prompt)
            .toString()
        val request = Request.Builder()
            .url(proxyUrl)
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        execute(request).let { response ->
            val raw = response.raw
            if (!response.isSuccessful) {
                throw GeminiProxyException(
                    if (response.code == 429) GeminiProxyException.Reason.RateLimited
                    else GeminiProxyException.Reason.ServerRejected,
                    "AI_PROXY_FAILED: HTTP ${response.code}",
                    httpCode = response.code,
                    errorStatus = responseErrorStatus(raw),
                )
            }
            val text = runCatching { JSONObject(raw).optString("text") }.getOrDefault("")
            if (text.isBlank()) {
                throw GeminiProxyException(
                    GeminiProxyException.Reason.EmptyResponse,
                    "AI_PROXY_FAILED: Empty proxy response",
                )
            }
            return text
        }
    }

    private suspend fun executeGenerateContent(apiKey: String, prompt: String, requestId: String, model: String): String {
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
        execute(request).let { response ->
            val raw = response.raw
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
            val text = (0 until (parts?.length() ?: 0)).mapNotNull { index ->
                parts?.optJSONObject(index)?.takeUnless { it.optBoolean("thought") }
                    ?.optString("text")?.takeIf { it.isNotBlank() }
            }.joinToString("\n")
            if (text.isBlank()) {
                Log.w(LOG_TAG, "empty model text requestId=$requestId model=$model")
                throw GeminiProxyException(GeminiProxyException.Reason.EmptyResponse, "AI_PROXY_FAILED: Empty model text")
            }
            Log.d(LOG_TAG, "parse success requestId=$requestId model=$model textChars=${text.length}")
            return text
        }
    }

    private suspend fun retryNetworkErrors(requestId: String, model: String, block: suspend () -> String): String {
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
                delay(350L)
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

    private const val MAX_RESPONSE_BYTES = 1_048_576L
    private const val LOG_TAG = "VeloAI"
}
