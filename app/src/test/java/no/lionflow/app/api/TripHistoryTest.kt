package no.lionflow.app.api

import kotlinx.coroutines.runBlocking
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TripHistoryTest {
    private val start = time("2026-11-16T00:00:00Z")
    private val end = time("2026-11-24T00:00:00Z")
    private val requested = TripWindow(start, end)

    @Test fun parsesStrictIsoInstantsWithFractionsAndOffsets() {
        val expected = time("2026-10-06T08:30:00.123Z")
        for (text in listOf(
            "2026-10-06T08:30:00.123Z", "2026-10-06T10:30:00.123456+02:00",
            "2026-10-06T03:00:00.123-0530", "2026-10-06T10:30:00.123+0200",
        )) assertEquals(text, expected, VehicleStatus.parseIso(text))
        assertEquals(expected - 123, VehicleStatus.parseIso("2026-10-06T10:30:00+02:00"))
        for (text in listOf("2026-02-30T08:30:00Z", "2026-10-06T08:30:00Zjunk", "2026-10-06T08:30:00", "")) {
            assertNull(text, VehicleStatus.parseIso(text))
        }
    }

    @Test fun thirtyDaysSplitIntoConsecutiveUtcWindowsIncludingRemainder() {
        val windows = TripWindow(end - 30 * TRIP_DAY_MS, end).newestFirst()
        assertEquals(8, windows.size)
        assertEquals(end, windows.first().until)
        assertEquals(end - 30 * TRIP_DAY_MS, windows.last().from)
        assertEquals(List(7) { 4 * TRIP_DAY_MS } + 2 * TRIP_DAY_MS, windows.map { it.duration })
        windows.zipWithNext().forEach { (newer, older) -> assertEquals(newer.from, older.until) }
        val newer = requested.newestFirst().first()
        assertEquals("2026-11-20T00:00:00Z", newer.fromIso)
        assertEquals("2026-11-23T23:59:59.999Z", newer.throughIso)
        assertTrue(newer.from in newer)
        assertFalse(newer.until in newer)
    }

    @Test fun urlsEncodeBothBoundsAndTheWholeOpaqueCursor() {
        val cursor = "a+/=&%"
        val newest = requested.newestFirst().first()
        val request = "https://example.invalid/trips?client_id=synthetic".toHttpUrl()
            .withTripFilters(newest.fromIso, cursor, newest.throughIso)
        assertEquals("synthetic", request.queryParameter("client_id"))
        assertNull(request.queryParameter("distance"))
        assertEquals("60", request.queryParameter("pageSize"))
        assertEquals("2026-11-20T00:00:00Z/2026-11-23T23:59:59.999Z", request.queryParameter("timestamps"))
        assertEquals(cursor, request.queryParameter("pageToken"))
        assertTrue(request.toString().contains("pageToken=a%2B%2F%3D%26%25"))
    }

    @Test fun acceptsBoundaryRecordsExactlyOnceAndUsesAllPages() = runBlocking {
        val newer = requested.newestFirst().first()
        val calls = mutableListOf<Pair<TripWindow, String?>>()
        val result = load { window, cursor ->
            calls += window to cursor
            when {
                window == newer && cursor == null -> page(listOf(row("boundary", newer.from)), "/trips?pageToken=a%2B%2F%3D%26%25")
                window == newer -> page(listOf(row("latest", end - 1)))
                else -> page(listOf(row("first", start), row("previous", newer.from - 1)))
            }
        }
        assertEquals(listOf(null, "a+/=&%", null), calls.map { it.second })
        assertEquals(listOf("latest", "boundary", "previous", "first"), result.trips.map { it.id })
        assertEquals(requested.duration, result.coveredMillis())
        assertTrue(result.complete)
    }

    @Test fun deduplicatesOverlappingPagesAndWindowsAndPreservesAnonymousTrips() = runBlocking {
        val windows = requested.newestFirst()
        val newer = windows[0]
        val older = windows[1]
        val repeated = row("old", older.until - 1, km = 99.0)
        val result = load { window, cursor ->
            when {
                window == newer && cursor == null -> page(listOf(
                    repeated, row(null, newer.from + 100), row("", newer.from + 101),
                    row("too-new", end), row("too-old", start - 1), JSONObject().put("id", "undated"),
                ), "?pageToken=p2")
                window == newer -> page(listOf(repeated, row(" ", newer.from + 100)))
                window == older -> page(listOf(row("old", older.until - 1)))
                else -> page()
            }
        }
        assertEquals(3, result.trips.size)
        assertEquals(3, result.duplicateRows)
        assertEquals(2, result.outsideRange)
        assertEquals(1, result.undated)
        assertEquals(1.0, result.trips.single { it.id == "old" }.distanceKm, 0.0)
        val anonymous = result.trips.filter { it.id.startsWith("anonymous-") }
        assertEquals(2, anonymous.size)
        assertNotEquals(anonymous[0].id, anonymous[1].id)
        assertEquals(TripWindowStop.OUTSIDE_WINDOW, result.attempts.first().stop)
        assertTrue(result.complete)
    }

    @Test fun samplesInitialWindowsBeforeSplittingRepeatedOrIncompletePagination() = runBlocking {
        val windows = requested.newestFirst()
        val newer = windows[0]
        val result = load { window, _ ->
            if (window == newer) page(listOf(row("partial", end - 1)), "?pageToken=overlap")
            else if (window == windows[1]) page(emptyList(), "/trips?unusable-cursor=1")
            else page()
        }
        assertEquals(TripWindowStop.REPEATED_CURSOR, result.attempts[0].stop)
        assertEquals(2, result.attempts[0].pages)
        assertEquals(TripWindowStop.INVALID_CURSOR, result.attempts[1].stop)
        assertEquals(windows, result.attempts.take(2).map { it.window })
        assertEquals(6, result.attempts.size)
        assertEquals("partial", result.trips.single().id)
        assertTrue(result.complete)
    }

    @Test fun requestAndPageCapsReportPartialCoverageWhileKeepingTrips() = runBlocking {
        var queries = 0
        val result = TripHistory.load(requested, maxRequests = 3, maxPages = 2, pause = {}) { window, _ ->
            ++queries
            page(listOf(row("trip-$queries", window.from + queries)), "?pageToken=page-$queries")
        }
        assertEquals(3, queries)
        assertEquals(3, result.trips.size)
        assertFalse(result.complete)
        assertEquals(0L, result.coveredMillis())
        assertEquals(listOf(TripWindowStop.PAGE_LIMIT, TripWindowStop.REQUEST_LIMIT), result.attempts.map { it.stop })
    }

    @Test fun recognizesEmptyAnd404WindowsAndStopsAfterRateLimitWithoutLosingRows() = runBlocking {
        val empty = load { _, _ -> JSONObject("""{"total":0}""") }
        assertTrue(empty.complete)
        assertFalse(empty.noTrips)
        val absent = load { _, _ -> throw ApiException(404, "untrusted message") }
        assertTrue(absent.noTrips)
        assertFalse(absent.complete)
        val limited = load { window, cursor ->
            if (cursor == null) page(listOf(row("private-trip-id", window.from)), "?pageToken=private-cursor")
            else throw ApiException(429, "private-exception", "25")
        }
        assertEquals(2, limited.requests)
        assertEquals(1, limited.trips.size)
        assertEquals("25", limited.retryAfter)
        assertEquals(TripWindowStop.RATE_LIMITED, limited.attempts.single().stop)
        val report = limited.aggregateReport()
        assertTrue(report.contains("HTTP 429"))
        assertFalse(report.contains("private-"))
    }

    @Test fun givesRecentAdaptiveRetriesPriorityOverOlderHistory() = runBlocking {
        val longRange = TripWindow(end - 120 * TRIP_DAY_MS, end)
        val base = longRange.newestFirst()
        val result = TripHistory.load(longRange, maxRequests = 10, pause = {}) { window, _ ->
            if (window == base.first()) page(listOf(row("out-of-window", start)))
            else page()
        }
        assertEquals(base.take(8), result.attempts.take(8).map { it.window })
        assertEquals(base.first().split(), result.attempts.drop(8).take(2).map { it.window })
        val recent = TripWindow(end - 30 * TRIP_DAY_MS, end)
        assertEquals(recent.duration, result.coveredMillis(recent))
        assertFalse(result.complete)
    }

    @Test fun anUndatedRowDoesNotCountAsFullCoverageAndLater404IsPartial() = runBlocking {
        val shortRange = TripWindow(end - TRIP_DAY_MS, end)
        val malformed = TripHistory.load(shortRange, pause = {}) { _, _ ->
            page(listOf(JSONObject().put("id", "missing-start")))
        }
        assertFalse(malformed.complete)
        assertEquals(1, malformed.undated)
        assertEquals(TripWindowStop.UNDATED_ROWS, malformed.attempts.single().stop)
        val partial = TripHistory.load(shortRange, pause = {}) { window, cursor ->
            if (cursor == null) page(listOf(row("valid", window.from)), "?pageToken=missing")
            else throw ApiException(404, "missing page")
        }
        assertEquals(1, partial.trips.size)
        assertFalse(partial.noTrips)
        assertFalse(partial.complete)
        assertEquals(TripWindowStop.HTTP_ERROR, partial.attempts.single().stop)
    }

    @Test fun rollingWindowIncludesBothBoundsAndExcludesMissingAndFutureDates() {
        fun trip(id: String, at: Long?) = Trip(id, at, null, 1.0, null, null, null, null)
        val lower = time("2026-10-25T00:00:00Z")
        val input = listOf(
            trip("now", end), trip("boundary", lower),
            trip("early", lower - 1), trip("future", end + 1), trip("unknown", null),
        )
        assertEquals(lower, TripHistory.cutoff(end, 30))
        assertEquals(listOf("now", "boundary"), TripHistory.inPeriod(input, end, 30).map { it.id })
        assertEquals(listOf("now"), TripHistory.inPeriod(input, end, 7).map { it.id })
    }

    private suspend fun load(fetch: suspend (TripWindow, String?) -> JSONObject): TripHistory =
        TripHistory.load(requested, pause = {}, fetch = fetch)

    private fun time(value: String) = Instant.parse(value).toEpochMilli()

    private fun row(id: String?, epoch: Long, km: Double = 1.0): JSONObject = JSONObject()
        .put("id", id ?: JSONObject.NULL).put("startedAt", Instant.ofEpochMilli(epoch).toString()).put("distance", km)

    private fun page(trips: List<JSONObject> = emptyList(), next: String? = null): JSONObject {
        val result = JSONObject().put("_embedded", JSONObject().put("trips", JSONArray(trips)))
        next?.let { result.put("_links", JSONObject().put("next", JSONObject().put("href", it))) }
        return result
    }
}
