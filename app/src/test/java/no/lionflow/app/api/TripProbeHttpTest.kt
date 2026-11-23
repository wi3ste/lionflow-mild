package no.lionflow.app.api

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripProbeHttpTest {
    @Test fun retriesNeitherRateLimitsNor503RetryAfterZero() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("private-error"))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(200))
            val request = Request.Builder().url(server.url("/trips")).get().build()

            val limited = TripProbeHttp.fetch(request)
            assertEquals(429, limited.code)
            assertEquals("120", limited.retryAfter)
            assertNull(limited.body)
            val unavailable = TripProbeHttp.fetch(request)
            assertEquals(503, unavailable.code)
            assertEquals("0", unavailable.retryAfter)
            assertEquals(2, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun doesNotFollowRedirectsOrReturnAnUnparseableBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/other"))
            server.enqueue(MockResponse().setBody("<html>private page</html>"))
            val request = Request.Builder().url(server.url("/trips")).get().build()

            assertEquals(302, TripProbeHttp.fetch(request).code)
            val bad = TripProbeHttp.fetch(request)
            assertEquals(200, bad.code)
            assertNull(bad.body)
            assertEquals(2, server.requestCount)
            assertEquals("/trips", server.takeRequest().path)
            assertEquals("/trips", server.takeRequest().path)
        }
    }
}
