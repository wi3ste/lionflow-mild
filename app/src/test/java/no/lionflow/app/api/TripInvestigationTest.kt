package no.lionflow.app.api

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class TripInvestigationTest {
    private val endpoint = "https://example.invalid/user/vehicles/synthetic/trips?locale=it_IT".toHttpUrl()
    private val now = Instant.parse("2026-11-23T10:00:00Z")

    @Test fun controlMatchesTheNormalTripRequestAndEncodesTheOpaqueCursor() {
        val cursor = "a+/=&%"
        val a = TripProbeCase.A.query
        val url = a.url(endpoint, cursor)
        assertEquals(endpoint.withTripFilters("2026-10-24T00:00:00Z", cursor), url)
        assertEquals("2026-10-24T00:00:00Z/", url.queryParameter("timestamps"))
        assertEquals(1, url.queryParameterValues("pageToken").size)
        assertEquals(cursor, url.queryParameter("pageToken"))
    }

    @Test fun closedIntervalsCoverAllOfNovember23AndDistanceComparisonChangesOneParameter() {
        val b = TripProbeCase.B.query
        assertEquals("2026-11-06T00:00:00Z/2026-11-23T23:59:59.999Z", b.url(endpoint).queryParameter("timestamps"))
        val c = TripProbeCase.C.query
        assertEquals("2026-11-20T00:00:00Z/2026-11-23T23:59:59.999Z", c.url(endpoint).queryParameter("timestamps"))
        assertFalse(b.outside(Instant.parse("2026-11-06T00:00:00Z")))
        assertFalse(b.outside(Instant.parse("2026-11-23T23:59:59.999Z")))
        assertTrue(b.outside(Instant.parse("2026-11-24T00:00:00Z")))
        assertEquals(
            b.url(endpoint).newBuilder().removeAllQueryParameters("distance").build(),
            TripProbeCase.B_NO_DISTANCE.query.url(endpoint),
        )
        val d = TripProbeCase.D.query.url(endpoint)
        assertNull(d.queryParameter("timestamps"))
        assertEquals("0.1-", d.queryParameter("distance"))
        assertEquals("60", d.queryParameter("pageSize"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReversedIntervals() {
        TripProbeQuery(now, now.minusSeconds(1))
    }

    @Test fun aggregatesAcrossPagesAndUsesUtcRatherThanTheOriginalOffset() = runBlocking {
        val a = TripProbeCase.A.query
        val hits = mutableListOf<String?>()
        val report = investigator { query, cursor ->
            when {
                query == a && cursor == null -> {
                    hits += cursor
                    response(
                        trips = listOf(trip("one", "2026-10-22T10:00:00Z"), trip("two", "2026-11-05T23:30:00-02:00")),
                        next = "/trips?pageToken=opaque%2B%26&other=value",
                    )
                }
                query == a -> {
                    hits += cursor
                    response(listOf(trip("two", "2026-11-06T01:30:00Z"), trip("three", "2026-11-20T01:00:00Z")))
                }
                query == TripProbeCase.B.query -> response(
                    listOf(trip("before", "2026-11-06T00:30:00+01:00"), trip("after", "2026-11-24T00:00:00Z")),
                )
                else -> response()
            }
        }.run()
        assertEquals(listOf(null, "opaque+&"), hits)
        val result = report.results.first()
        assertEquals(TripProbeStop.LAST_PAGE, result.stop)
        assertEquals(listOf(200, 200), result.httpCodes)
        assertEquals(listOf(2, 4, 3, 1, 2, 1), listOf(result.pages, result.raw, result.unique, result.duplicates, result.afterNovember5, result.outsideInterval))
        assertEquals(LocalDate.parse("2026-10-22"), result.firstUtc)
        assertEquals(LocalDate.parse("2026-11-20"), result.lastUtc)
        assertEquals(2, report.results[1].outsideInterval)
    }

    @Test fun capsRequestsEvenWhenTheServerAlwaysOffersAnotherPage() = runBlocking {
        var calls = 0
        val report = investigator { _, _ -> response(next = "?pageToken=next-${++calls}") }.run()
        assertEquals(22, calls)
        assertEquals(listOf(5, 5, 5, 2, 5), report.results.map { it.pages })
        assertTrue(report.results.all { it.stop == TripProbeStop.PAGE_LIMIT })
    }

    @Test fun stopsOnRepeatedOrMalformedCursorsForTheAffectedTest() = runBlocking {
        val report = investigator { query, _ ->
            when (query) {
                TripProbeCase.A.query -> response(next = "?pageToken=repeat")
                TripProbeCase.B.query -> response(next = "https://example.invalid/?wrong=1")
                else -> response()
            }
        }.run()
        assertEquals(TripProbeStop.REPEATED_CURSOR, report.results[0].stop)
        assertEquals(2, report.results[0].pages)
        assertEquals(TripProbeStop.INVALID_CURSOR, report.results[1].stop)
        assertEquals(1, report.results[1].pages)
        assertEquals(TripProbeStop.LAST_PAGE, report.results[2].stop)
    }

    @Test fun rejectsBadParametersAndDistinguishesEmptyUnsupportedAndMalformedResponses() = runBlocking {
        val report = investigator { query, _ ->
            when (query) {
                TripProbeCase.A.query -> TripProbeResponse(400)
                TripProbeCase.B.query -> TripProbeResponse(422)
                TripProbeCase.C.query -> TripProbeResponse(404)
                TripProbeCase.D.query -> TripProbeResponse(405)
                else -> TripProbeResponse(200, JSONObject("""{"unexpected":"payload"}"""))
            }
        }.run()
        assertEquals(listOf(
            TripProbeStop.PARAMETERS_REJECTED, TripProbeStop.PARAMETERS_REJECTED, TripProbeStop.NOT_FOUND,
            TripProbeStop.UNSUPPORTED, TripProbeStop.INVALID_RESPONSE,
        ), report.results.map { it.stop })
        assertTrue(report.toText().contains("Parameter validation: rejected by the server"))
        val empty = investigator { _, _ -> TripProbeResponse(200, JSONObject("""{"total":0}""")) }.run()
        assertTrue(empty.results.all { it.stop == TripProbeStop.LAST_PAGE && it.pages == 1 && it.raw == 0 })
        val noContent = investigator { _, _ -> TripProbeResponse(204) }.run()
        assertTrue(noContent.results.all { it.stop == TripProbeStop.NO_CONTENT })
    }

    @Test fun preservesPartialPageCountsOnAnHttpError() = runBlocking {
        val report = investigator { query, cursor ->
            if (query != TripProbeCase.A.query) response()
            else if (cursor == null) response(listOf(trip("one", "2026-10-26T00:00:00Z")), "?pageToken=next")
            else TripProbeResponse(404)
        }.run()
        assertEquals(listOf(200, 404), report.results.first().httpCodes)
        assertEquals(1, report.results.first().pages)
        assertEquals(1, report.results.first().unique)
    }

    @Test fun stopsSuiteOnPermissionOrServerErrorsAndDoesNotRetry() = runBlocking {
        val errors = mapOf(
            401 to TripProbeStop.UNAUTHORIZED,
            403 to TripProbeStop.FORBIDDEN,
            408 to TripProbeStop.TIMEOUT,
            500 to TripProbeStop.SERVER_ERROR,
            503 to TripProbeStop.SERVER_ERROR,
        )
        for ((code, reason) in errors) {
            var calls = 0
            val report = investigator { _, _ -> calls++; TripProbeResponse(code) }.run()
            assertEquals(code.toString(), 1, calls)
            assertEquals(reason, report.results.first().stop)
            assertTrue(report.results.drop(1).all { it.stop == TripProbeStop.SKIPPED })
            if (code == 503) assertEquals(now.plusSeconds(60), report.retryAt)
        }
    }

    @Test fun honorsRetryAfterAndPreservesOnlyTheAggregate() = runBlocking {
        var calls = 0
        val report = investigator { _, _ ->
            calls++
            TripProbeResponse(429, retryAfter = "120")
        }.run()
        assertEquals(1, calls)
        assertEquals(now.plusSeconds(120), report.retryAt)
        assertEquals(TripProbeStop.RATE_LIMITED, report.results.first().stop)
        assertTrue(report.results.drop(1).all { it.stop == TripProbeStop.SKIPPED })
        assertEquals(now.plusSeconds(60), TripInvestigation.retryAt(null, now))
        assertEquals(now.plusSeconds(180), TripInvestigation.retryAt("Mon, 23 Nov 2026 10:03:00 GMT", now))
        assertEquals(now.plusSeconds(60), TripInvestigation.retryAt("invalid-secret", now))
        assertFalse(report.toText().contains("120"))
    }

    @Test fun handlesTimeoutAndUnavailableSessionWithoutExportingExceptionDetails() = runBlocking {
        val timeouts = investigator { _, _ -> throw SocketTimeoutException("private-url") }.run()
        assertEquals(TripProbeStop.TIMEOUT, timeouts.results.first().stop)
        assertTrue(timeouts.results.first().httpCodes.isEmpty())
        assertFalse(timeouts.toText().contains("private-url"))
        val unavailable = investigator { _, _ -> throw TripProbeSessionUnavailable() }.run()
        assertEquals(TripProbeStop.SESSION_UNAVAILABLE, unavailable.results.first().stop)
        assertTrue(unavailable.results.drop(1).all { it.stop == TripProbeStop.SKIPPED })
    }

    @Test fun reportContainsOnlyAllowedAggregates() = runBlocking {
        val privateRow = trip("private-trip-id", "2026-11-08T08:07:06Z").apply {
            put("vin", "private-vin")
            put("coordinate", "46.134902,11.121245")
            put("credential", "private-credential")
        }
        val report = investigator { query, page ->
            when {
                query == TripProbeCase.A.query && page == null ->
                    response(listOf(privateRow), "https://example.invalid/private-path?pageToken=private-cursor")
                query == TripProbeCase.A.query -> TripProbeResponse(404, JSONObject("""{"error":"private-error-body"}"""))
                else -> response()
            }
        }.run().toText()
        assertTrue(report.contains("HTTP: 200, 404 · Pages: 1"))
        assertTrue(report.contains("Last UTC: 2026-11-08"))
        assertTrue(report.contains("After 5 November: 1"))
        for (secret in listOf("private-", "46.134902", "11.121245", "08:07:06", "example.invalid")) {
            assertFalse(secret, report.contains(secret))
        }
    }

    private fun investigator(fetch: suspend (TripProbeQuery, String?) -> TripProbeResponse): TripInvestigation =
        TripInvestigation(fetch, Clock.fixed(now, ZoneOffset.UTC), pause = {})

    private fun trip(id: String, start: String): JSONObject = JSONObject().put("id", id).put("startedAt", start)

    private fun response(trips: List<JSONObject> = emptyList(), next: String? = null): TripProbeResponse {
        val body = JSONObject().put("_embedded", JSONObject().put("trips", JSONArray(trips)))
        next?.let { body.put("_links", JSONObject().put("next", JSONObject().put("href", it))) }
        return TripProbeResponse(200, body)
    }
}
