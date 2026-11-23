package no.lionflow.app.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import no.lionflow.app.model.Trip
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal const val TRIP_DAY_MS = 86_400_000L

/** Half-open UTC interval, at the millisecond precision of Trip.startMillis. */
internal data class TripWindow(val from: Long, val until: Long) {
    init { require(from < until) }

    val fromIso: String get() = Instant.ofEpochMilli(from).toString()
    val throughIso: String get() = Instant.ofEpochMilli(until - 1).toString()
    val duration: Long get() = until - from
    operator fun contains(start: Long): Boolean = start in from until until

    fun newestFirst(): List<TripWindow> = buildList {
        var end = until
        while (end > from) {
            val start = maxOf(from, end - 4 * TRIP_DAY_MS)
            add(TripWindow(start, end))
            end = start
        }
    }

    fun split(): List<TripWindow> {
        if (duration < 2 * TRIP_DAY_MS) return emptyList()
        val midpoint = from + duration / 2
        return listOf(TripWindow(midpoint, until), TripWindow(from, midpoint))
    }
}

internal enum class TripWindowStop(val retrySmaller: Boolean = false, val stopBatch: Boolean = false) {
    COMPLETE,
    OUTSIDE_WINDOW(retrySmaller = true),
    UNDATED_ROWS(retrySmaller = true),
    NO_TRIPS,
    INVALID_RESPONSE,
    INVALID_CURSOR(retrySmaller = true),
    REPEATED_CURSOR(retrySmaller = true),
    PAGE_LIMIT(retrySmaller = true),
    HTTP_ERROR,
    RATE_LIMITED(stopBatch = true),
    UNAVAILABLE(stopBatch = true),
    NETWORK_ERROR(stopBatch = true),
    REQUEST_LIMIT(stopBatch = true),
}

internal data class TripWindowOutcome(
    val window: TripWindow,
    val pages: Int,
    val raw: Int,
    val outsideWindow: Int,
    val stop: TripWindowStop,
    val httpCode: Int? = null,
)

internal data class TripHistory(
    val trips: List<Trip>,
    val range: TripWindow,
    val attempts: List<TripWindowOutcome>,
    val requests: Int,
    val duplicateRows: Int,
    val outsideRange: Int,
    val undated: Int,
    val retryAfter: String? = null,
    val retainedFromCache: Int = 0,
) {
    val noTrips: Boolean get() = attempts.isNotEmpty() && attempts.all { it.stop == TripWindowStop.NO_TRIPS }

    fun coveredMillis(target: TripWindow = range): Long = attempts
        .filter { it.stop == TripWindowStop.COMPLETE }
        .sumOf { (minOf(it.window.until, target.until) - maxOf(it.window.from, target.from)).coerceAtLeast(0) }

    val complete: Boolean get() = coveredMillis() == range.duration

    fun aggregateReport(): String = buildString {
        fun day(time: Long) = Instant.ofEpochMilli(time).atOffset(ZoneOffset.UTC).toLocalDate()
        appendLine("LionFlow · Trip sync diagnostics")
        appendLine("Trip dates are UTC. Coverage means a completed page sequence with no out-of-window rows.")
        appendLine("Requested: ${day(range.from)} – ${day(range.until - 1)}")
        appendLine("Completed hours: ${coveredMillis() / 3_600_000} / ${range.duration / 3_600_000}")
        appendLine("Requests: $requests · Raw rows: ${attempts.sumOf { it.raw }} · Unique API trips: ${trips.size}")
        appendLine("Duplicate rows: $duplicateRows · Outside requested range: $outsideRange · Undated: $undated")
        appendLine("Retained from earlier API results: $retainedFromCache")
        appendLine("First UTC: ${trips.lastOrNull()?.startMillis?.let(::day) ?: "—"} · Last UTC: ${trips.firstOrNull()?.startMillis?.let(::day) ?: "—"}")
        for (attempt in attempts) {
            appendLine("${day(attempt.window.from)} – ${day(attempt.window.until - 1)}: ${attempt.pages} pages, " +
                "${attempt.raw} rows, ${attempt.outsideWindow} outside window, ${attempt.stop}" +
                (attempt.httpCode?.let { ", HTTP $it" } ?: ""))
        }
    }

    companion object {
        private const val MAX_PAGES_PER_WINDOW = 12
        internal const val MAX_REQUESTS = 64

        fun cutoff(nowMillis: Long, days: Int): Long = nowMillis - days * TRIP_DAY_MS

        fun inPeriod(trips: List<Trip>, nowMillis: Long, days: Int): List<Trip> {
            val since = cutoff(nowMillis, days)
            return trips.filter { it.startMillis?.let { start -> start in since..nowMillis } == true }
        }

        suspend fun load(
            range: TripWindow,
            maxRequests: Int = MAX_REQUESTS,
            maxPages: Int = MAX_PAGES_PER_WINDOW,
            pause: suspend () -> Unit = { delay(250) },
            fetch: suspend (window: TripWindow, pageToken: String?) -> JSONObject,
        ): TripHistory {
            require(maxRequests > 0 && maxPages > 0)
            val accumulator = TripRows(range)
            val recentFrom = range.until - 30 * TRIP_DAY_MS
            val (recent, older) = range.newestFirst().partition { it.until > recentFrom }
            val pending = ArrayDeque(recent)
            val pendingOlder = ArrayDeque(older)
            val attempts = mutableListOf<TripWindowOutcome>()
            var requests = 0
            var retryAfter: String? = null
            while ((pending.isNotEmpty() || pendingOlder.isNotEmpty()) && requests < maxRequests) {
                val queue = if (pending.isNotEmpty()) pending else pendingOlder
                val window = queue.removeFirst()
                val outcome = readWindow(window, maxPages, accumulator, fetch = { token ->
                    if (requests >= maxRequests) throw RequestLimitReached()
                    if (requests > 0) pause()
                    requests++
                    fetch(window, token)
                }, onRateLimit = { retryAfter = it })
                attempts += outcome
                if (outcome.stop.stopBatch) break
                if (outcome.stop.retrySmaller) queue.addAll(window.split())
            }
            val unstarted = pending.firstOrNull() ?: pendingOlder.firstOrNull()
            if (requests >= maxRequests && unstarted != null && attempts.lastOrNull()?.stop?.stopBatch != true) {
                attempts += TripWindowOutcome(unstarted, 0, 0, 0, TripWindowStop.REQUEST_LIMIT)
            }
            return TripHistory(
                accumulator.unique.values.sortedByDescending { it.startMillis },
                range, attempts, requests, accumulator.duplicateRows, accumulator.outsideRange,
                accumulator.undated, retryAfter,
            )
        }

        private suspend fun readWindow(
            window: TripWindow,
            maxPages: Int,
            accumulator: TripRows,
            fetch: suspend (String?) -> JSONObject,
            onRateLimit: (String?) -> Unit,
        ): TripWindowOutcome {
            var pages = 0
            var raw = 0
            var outside = 0
            val priorUndated = accumulator.undated
            fun finish(stop: TripWindowStop, code: Int? = null) = TripWindowOutcome(window, pages, raw, outside, stop, code)

            val seenTokens = mutableSetOf<String>()
            var token: String? = null
            repeat(maxPages) {
                val body = try {
                    fetch(token)
                } catch (e: ApiException) {
                    val outcome = when (e.code) {
                        404 -> if (pages == 0) TripWindowStop.NO_TRIPS else TripWindowStop.HTTP_ERROR
                        429 -> {
                            onRateLimit(e.retryAfter)
                            TripWindowStop.RATE_LIMITED
                        }
                        401, 403, in 500..599 -> TripWindowStop.UNAVAILABLE
                        0 -> TripWindowStop.INVALID_RESPONSE
                        else -> TripWindowStop.HTTP_ERROR
                    }
                    return finish(outcome, e.code.takeIf { it > 0 })
                } catch (_: AuthExpiredException) {
                    return finish(TripWindowStop.UNAVAILABLE)
                } catch (_: IOException) {
                    return finish(TripWindowStop.NETWORK_ERROR)
                } catch (_: RequestLimitReached) {
                    return finish(TripWindowStop.REQUEST_LIMIT)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: org.json.JSONException) {
                    return finish(TripWindowStop.INVALID_RESPONSE)
                }

                val links = body.optJSONObject("_links")
                val hasNext = links?.has("next") == true && !links.isNull("next")
                val rows = body.obj("_embedded")?.optJSONArray("trips")
                if (rows == null && (body.optInt("total", -1) != 0 || hasNext)) {
                    return finish(TripWindowStop.INVALID_RESPONSE)
                }
                pages++
                raw += rows?.length() ?: 0
                val objects = rows?.objects().orEmpty()
                accumulator.countUndated((rows?.length() ?: 0) - objects.size)
                for (row in objects) {
                    if (accumulator.add(Trip.parse(row), window)) outside++
                }
                if (links == null && body.has("_links") && !body.isNull("_links")) {
                    return finish(TripWindowStop.INVALID_CURSOR)
                }
                if (!hasNext) {
                    return finish(when {
                        outside > 0 -> TripWindowStop.OUTSIDE_WINDOW
                        accumulator.undated > priorUndated -> TripWindowStop.UNDATED_ROWS
                        else -> TripWindowStop.COMPLETE
                    })
                }
                val next = pageToken(links?.optJSONObject("next")?.str("href"))
                    ?: return finish(TripWindowStop.INVALID_CURSOR)
                if (!seenTokens.add(next)) return finish(TripWindowStop.REPEATED_CURSOR)
                token = next
            }
            return finish(TripWindowStop.PAGE_LIMIT)
        }

        private fun pageToken(href: String?): String? {
            if (href.isNullOrBlank()) return null
            return runCatching {
                URI(href).rawQuery?.split('&')?.firstNotNullOfOrNull { parameter ->
                    parameter.substringAfter('=', "").takeIf { parameter.startsWith("pageToken=") && it.isNotBlank() }
                }?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        private class TripRows(private val range: TripWindow) {
            val unique = linkedMapOf<String, Trip>()
            var duplicateRows = 0
                private set
            var outsideRange = 0
                private set
            var undated = 0
                private set

            fun countUndated(count: Int) { undated += count }

            /** Keep off-window rows if they belong to the requested history. Prefer the response owning their interval. */
            fun add(row: Trip, window: TripWindow): Boolean {
                val start = row.startMillis
                if (start == null) { undated++; return false }
                if (start !in range) { outsideRange++; return true }

                val trip = if (row.id.isNotBlank()) row else {
                    val key = listOf(start, row.endMillis, row.distanceKm, row.durationSec).joinToString("|")
                    row.copy(id = "anonymous-" + UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)))
                }
                val owned = start in window
                val first = unique.putIfAbsent(trip.id, trip) == null
                if (!first) duplicateRows++
                if (owned && ownedIds.add(trip.id)) unique[trip.id] = trip
                return !owned
            }

            private val ownedIds = hashSetOf<String>()
        }

        private class RequestLimitReached : Exception()
    }
}
