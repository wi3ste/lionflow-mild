package no.lionflow.app.api

import kotlinx.coroutines.delay
import no.lionflow.app.model.Trip
import okhttp3.HttpUrl
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal data class TripProbeQuery(val from: Instant?, val through: Instant?, val minimumDistance: Boolean = true) {
    init {
        require(through == null || (from != null && through >= from))
    }

    val timestamps: String? get() = from?.let { "$it/${through ?: ""}" }

    fun url(endpoint: HttpUrl, cursor: String? = null): HttpUrl = endpoint.newBuilder()
        .apply {
            if (minimumDistance) addQueryParameter("distance", "0.1-")
            addQueryParameter("pageSize", "60")
            timestamps?.let { addQueryParameter("timestamps", it) }
            cursor?.let { addQueryParameter("pageToken", it) }
        }
        .build()

    fun outside(start: Instant): Boolean = (from != null && start < from) || (through != null && start > through)
}

private val firstMissingDay = Instant.parse("2026-11-06T00:00:00Z")
private val lastRequestedDay = Instant.parse("2026-11-23T23:59:59.999Z")
private val missingInterval = TripProbeQuery(firstMissingDay, lastRequestedDay)

internal enum class TripProbeCase(val title: String, val query: TripProbeQuery, val pageLimit: Int = 5) {
    A("A · Open end", TripProbeQuery(Instant.parse("2026-10-24T00:00:00Z"), null)),
    B("B · Explicit interval", missingInterval),
    C("C · Short interval", TripProbeQuery(Instant.parse("2026-11-20T00:00:00Z"), lastRequestedDay)),
    D("D · No time filter", TripProbeQuery(null, null), pageLimit = 2),
    B_NO_DISTANCE("B2 · Explicit interval, no distance filter", missingInterval.copy(minimumDistance = false)),
}

internal enum class TripProbeStop(val title: String, val stopSuite: Boolean = false) {
    LAST_PAGE("Last page"),
    NO_CONTENT("No content"),
    PAGE_LIMIT("Page limit reached"),
    INVALID_CURSOR("Missing or invalid cursor"),
    REPEATED_CURSOR("Repeated cursor"),
    INVALID_RESPONSE("Unexpected response format"),
    PARAMETERS_REJECTED("Request parameters rejected"),
    UNSUPPORTED("Request unsupported"),
    SESSION_UNAVAILABLE("Valid Peugeot session required", true),
    UNAUTHORIZED("Unauthorized", true),
    FORBIDDEN("Access denied", true),
    NOT_FOUND("Not found or no trips"),
    RATE_LIMITED("Rate limited", true),
    SERVER_ERROR("Server error", true),
    HTTP_ERROR("Unexpected HTTP response"),
    TIMEOUT("Timed out", true),
    NETWORK_ERROR("Network error", true),
    SKIPPED("Skipped after a previous test"),
}

internal class TripProbeSessionUnavailable : Exception()

internal data class TripProbeResponse(val code: Int, val body: JSONObject? = null, val retryAfter: String? = null)

internal data class TripProbeResult(
    val test: TripProbeCase,
    val stop: TripProbeStop,
    val httpCodes: List<Int> = emptyList(),
    val pages: Int = 0,
    val raw: Int = 0,
    val unique: Int = 0,
    val duplicates: Int = 0,
    val firstUtc: LocalDate? = null,
    val lastUtc: LocalDate? = null,
    val afterNovember5: Int = 0,
    val outsideInterval: Int = 0,
) {
    fun summary(): String = buildString {
        appendLine("HTTP: ${httpCodes.joinToString().ifEmpty { "—" }} · Pages: $pages")
        appendLine("Raw: $raw · Unique: $unique · Duplicates: $duplicates")
        appendLine("First UTC: ${firstUtc ?: "—"} · Last UTC: ${lastUtc ?: "—"}")
        appendLine("After 5 November: $afterNovember5 · Outside interval: $outsideInterval")
        append("Pagination: ${stop.title}")
    }
}

internal data class TripInvestigationReport(val results: List<TripProbeResult>, val retryAt: Instant?) {
    fun toText(): String = buildString {
        appendLine("LionFlow · Trip API Diagnostics")
        appendLine("Dates use UTC. Counts by date use unique trips with a readable start.")
        appendLine("Outside interval is zero when no time filter is sent.")
        for (result in results) {
            val query = result.test.query
            appendLine()
            appendLine(result.test.title)
            appendLine("Interval UTC: ${query.timestamps ?: "omitted"}")
            appendLine("Distance filter: ${if (query.minimumDistance) "0.1- km" else "omitted"}")
            appendLine(result.summary())
            if (result.stop == TripProbeStop.PARAMETERS_REJECTED) {
                appendLine("Parameter validation: rejected by the server. The rejected field is unknown.")
            }
        }
    }
}

internal class TripInvestigation(
    private val fetch: suspend (query: TripProbeQuery, cursor: String?) -> TripProbeResponse,
    private val clock: Clock = Clock.systemUTC(),
    private val pause: suspend () -> Unit = { delay(500) },
) {
    suspend fun run(onPage: (TripProbeCase, Int) -> Unit = { _, _ -> }): TripInvestigationReport {
        var retryAt: Instant? = null
        var stopSuite = false
        val results = TripProbeCase.entries.map { test ->
            if (stopSuite) TripProbeResult(test, TripProbeStop.SKIPPED)
            else runTest(test, onPage) { retryAt = it }.also { stopSuite = it.stop.stopSuite }
        }
        return TripInvestigationReport(results, retryAt)
    }

    private suspend fun runTest(
        test: TripProbeCase,
        onPage: (TripProbeCase, Int) -> Unit,
        cooldown: (Instant) -> Unit,
    ): TripProbeResult {
        val counts = ProbeCounts(test)
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(test.pageLimit) { index ->
            val page = index + 1
            if (test != TripProbeCase.A || page > 1) pause()
            onPage(test, page)
            val response = try {
                fetch(test.query, cursor)
            } catch (_: TripProbeSessionUnavailable) {
                return counts.finish(TripProbeStop.SESSION_UNAVAILABLE)
            } catch (_: InterruptedIOException) {
                return counts.finish(TripProbeStop.TIMEOUT)
            } catch (_: IOException) {
                return counts.finish(TripProbeStop.NETWORK_ERROR)
            }
            counts.httpCodes += response.code
            response.stop()?.let { stop ->
                if (stop == TripProbeStop.RATE_LIMITED || response.code == 503) {
                    cooldown(retryAt(response.retryAfter, clock.instant()))
                }
                return counts.finish(stop)
            }

            val body = response.body ?: return counts.finish(TripProbeStop.INVALID_RESPONSE)
            val links = body.optJSONObject("_links")
            val hasNext = links?.has("next") == true && !links.isNull("next")
            val rows = body.optJSONObject("_embedded")?.optJSONArray("trips")
            if (rows == null && (body.optInt("total", -1) != 0 || hasNext)) {
                return counts.finish(TripProbeStop.INVALID_RESPONSE)
            }
            counts.pages++
            counts.raw += rows?.length() ?: 0
            rows?.objects()?.forEach { counts.add(Trip.parse(it)) }

            if (links == null && body.has("_links") && !body.isNull("_links")) {
                return counts.finish(TripProbeStop.INVALID_CURSOR)
            }
            if (!hasNext) return counts.finish(TripProbeStop.LAST_PAGE)
            val next = TripHistory.pageToken(links?.optJSONObject("next")?.str("href"))
                ?: return counts.finish(TripProbeStop.INVALID_CURSOR)
            if (!seenCursors.add(next)) return counts.finish(TripProbeStop.REPEATED_CURSOR)
            cursor = next
        }
        return counts.finish(TripProbeStop.PAGE_LIMIT)
    }

    private fun TripProbeResponse.stop(): TripProbeStop? = when (code) {
        204 -> TripProbeStop.NO_CONTENT
        in 200..299 -> null
        400, 422 -> TripProbeStop.PARAMETERS_REJECTED
        401 -> TripProbeStop.UNAUTHORIZED
        403 -> TripProbeStop.FORBIDDEN
        404 -> TripProbeStop.NOT_FOUND
        405, 501 -> TripProbeStop.UNSUPPORTED
        408 -> TripProbeStop.TIMEOUT
        429 -> TripProbeStop.RATE_LIMITED
        in 500..599 -> TripProbeStop.SERVER_ERROR
        else -> TripProbeStop.HTTP_ERROR
    }

    private class ProbeCounts(val test: TripProbeCase) {
        val httpCodes = mutableListOf<Int>()
        private val uniqueTrips = linkedMapOf<String, Long?>()
        var pages = 0
        var raw = 0
        private var duplicates = 0

        fun add(parsed: Trip) {
            val trip = TripHistory.identify(parsed) ?: return
            if (uniqueTrips.containsKey(trip.id)) duplicates++
            if (uniqueTrips[trip.id] == null) uniqueTrips[trip.id] = trip.startMillis
        }

        fun finish(stop: TripProbeStop): TripProbeResult {
            val starts = uniqueTrips.values.filterNotNull().map(Instant::ofEpochMilli)
            return TripProbeResult(
                test, stop, httpCodes.toList(), pages, raw, uniqueTrips.size, duplicates,
                firstUtc = starts.minOrNull()?.atOffset(ZoneOffset.UTC)?.toLocalDate(),
                lastUtc = starts.maxOrNull()?.atOffset(ZoneOffset.UTC)?.toLocalDate(),
                afterNovember5 = starts.count { it >= firstMissingDay },
                outsideInterval = starts.count(test.query::outside),
            )
        }
    }

    companion object {
        fun retryAt(header: String?, now: Instant): Instant {
            val value = header?.trim()
            val deadline = try {
                val seconds = value?.toLongOrNull()
                when {
                    seconds != null && seconds >= 0 -> now.plusSeconds(seconds)
                    value != null -> ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                    else -> null
                }
            } catch (_: DateTimeException) {
                null
            } catch (_: ArithmeticException) {
                null
            }
            return maxOf(deadline ?: now.plusSeconds(60), now.plusSeconds(1))
        }
    }
}
