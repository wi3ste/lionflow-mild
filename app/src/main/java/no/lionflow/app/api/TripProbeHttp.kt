package no.lionflow.app.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object TripProbeHttp {
    private const val SAVED_RETRY_AFTER = "X-LionFlow-Probe-Retry-After"
    private const val MAX_BODY_BYTES = 4L * 1024 * 1024

    private val client by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code != 503) response
                else response.newBuilder().apply {
                    // OkHttp can automatically retry a 503 with Retry-After: 0.
                    val retryAfter = response.header("Retry-After")
                    removeHeader("Retry-After")
                    removeHeader(SAVED_RETRY_AFTER)
                    if (retryAfter != null) header(SAVED_RETRY_AFTER, retryAfter)
                }.build()
            }
            .build()
    }

    suspend fun fetch(request: Request): TripProbeResponse = withContext(Dispatchers.IO) {
        check(request.method == "GET")
        client.newCall(request).execute().use { response ->
            val retryHeader = if (response.code == 503) SAVED_RETRY_AFTER else "Retry-After"
            TripProbeResponse(
                code = response.code,
                body = if (response.isSuccessful && response.code != 204) response.tripBody() else null,
                retryAfter = response.header(retryHeader),
            )
        }
    }

    private fun Response.tripBody(): JSONObject? {
        val source = body?.source() ?: return null
        if (source.request(MAX_BODY_BYTES + 1)) return null
        return try {
            JSONObject(source.readUtf8())
        } catch (_: JSONException) {
            null
        }
    }
}
