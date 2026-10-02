package com.velotrack.velotrack

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class GeminiClientTest {
    @Test fun cancellationAbortsRequestWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) {
                GeminiClient.generateContent("", "test", proxyUrl = server.url("/analyze").toString())
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            delay(450)
            assertEquals(1, server.requestCount)
            assertTrue(job.isCancelled)
        }
    }

    @Test fun proxyWorksWithoutAnyClientModelOrKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"分析完成"}"""))
            assertEquals("分析完成", GeminiClient.generateContent("", "test", "same-id",
                server.url("/analyze").toString(), model = ""))
            assertTrue(server.takeRequest().body.readUtf8().contains("same-id"))
        }
    }
}
