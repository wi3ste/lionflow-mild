package no.lionflow.app.api

import kotlinx.coroutines.runBlocking
import no.lionflow.app.model.Trip
import no.lionflow.app.model.TripImport
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
import java.util.concurrent.ConcurrentLinkedQueue

class TripHistoryTest {
    private val from = Instant.parse("2026-10-12T12:00:00Z")
    private val boundary = Instant.parse("2026-10-13T12:00:00Z")
    private val now = Instant.parse("2026-10-20T12:00:00Z")

    @Test fun parsesOffsetsAndFractionalTimestampsPrecisely() {
        val expected = Instant.parse("2026-10-06T08:30:00.123Z").toEpochMilli()
        for (text in listOf("2026-10-06T08:30:00.123Z", "2026-10-06T10:30:00.123456+02:00",
            "2026-10-06T03:00:00.123-0530", "2026-10-06T10:30:00.123+0200")) {
            assertEquals(text, expected, VehicleStatus.parseIso(text))
        }
        assertNull(VehicleStatus.parseIso("2026-02-30T08:30:00Z"))
        assertNull(VehicleStatus.parseIso("2026-10-06T08:30:00Zjunk"))
    }

    @Test fun requestsZeroDistanceAndPreservesTheOpaqueCursorAndUtcFilters() {
        val endpoint = "https://example.invalid/trips?client_id=synthetic&locale=en".toHttpUrl()
        val first = tripPageUrl(endpoint, from.toString(), boundary.toString(), null)
        val next = tripPageUrl(endpoint, from.toString(), boundary.toString(), "?pageToken=a%2B%2F%3D%26%25")
        for (url in listOf(first, next)) {
            assertNull(url.queryParameter("distance")) // PSA defaults to 0-.
            assertEquals("60", url.queryParameter("pageSize"))
            assertEquals("$from/$boundary", url.queryParameter("timestamps"))
            assertEquals("synthetic", url.queryParameter("client_id"))
        }
        assertEquals("a+/=&%", next.queryParameter("pageToken"))
    }

    @Test fun followsPagesPastFiftyAndAnOverlappingPage() = runBlocking {
        val rows = List(65) { i -> row("trip-$i", boundary.plusSeconds(i.toLong()), if (i == 0) 0 else 1) }
        val calls = ConcurrentLinkedQueue<Triple<String, String?, String?>>()
        val result = loadWeeklyTrips(from.toEpochMilli(), now.toEpochMilli()) { start, end, next ->
            calls.add(Triple(start, end, next))
            when {
                start == from.toString() -> page(listOf(rows.first(), row("oldest", from)))
                next == "?pageToken=last" -> page(rows.drop(60))
                next == "?pageToken=overlap" -> page(rows.take(60), "?pageToken=last")
                else -> page(rows.take(60), "?pageToken=overlap")
            }
        }
        val trips = result.trips.map { Trip.parse(it) }
        assertEquals(66, trips.size)
        assertEquals("trip-64", trips.first().id)
        assertEquals("oldest", trips.last().id)
        assertEquals(0.0, trips.single { it.id == "trip-0" }.distanceKm, 0.0)
        assertEquals(4, calls.size)
        assertTrue(calls.contains(Triple(from.toString(), boundary.toString(), null)))
        assertTrue(calls.contains(Triple(boundary.toString(), null, "?pageToken=last")))
        assertFalse(result.noneFound)
    }

    @Test fun filtersTheOverallIntervalBeforeDeduplicatingAndKeepsAnonymousTrips() = runBlocking {
        val anonymous = row(null, boundary)
        val result = loadWeeklyTrips(from.toEpochMilli(), now.toEpochMilli()) { start, _, next ->
            when {
                start == from.toString() -> page(listOf(row("reuse", from), anonymous))
                next != null -> page(listOf(anonymous, row("", boundary.plusSeconds(1))), "?pageToken=repeat")
                else -> page(listOf(row("reuse", from.minusSeconds(1)), row("now", now),
                    row("future", now.plusSeconds(1)), JSONObject().put("id", "undated")), "?pageToken=repeat")
            }
        }
        val trips = result.trips.map { Trip.parse(it) }
        assertEquals(4, trips.size)
        assertEquals("now", trips.first().id)
        assertEquals("reuse", trips.last().id)
        assertEquals(Trip.idOf(anonymous), trips[2].id)
        assertNotEquals(trips[1].id, trips[2].id)
    }

    @Test fun exportsTheSecondPageOfAnOlderWeekWhenTheServerLinksIt() = runBlocking {
        val exportAt = Instant.parse("2026-11-23T14:34:00Z")
        val requestedFrom = exportAt.minusSeconds(30 * 86_400L)
        val weekStart = exportAt.minusSeconds(21 * 86_400L)
        val weekEnd = exportAt.minusSeconds(14 * 86_400L)
        val firstPage = List(60) { i ->
            row("first-$i", weekEnd.minusSeconds(60L + i), km = if (i < 3) 0 else 1)
        }
        val overflow = listOf("2026-11-08T12:00:00Z", "2026-11-08T16:00:00Z",
            "2026-11-08T19:00:00Z", "2026-11-09T10:00:00Z")
            .mapIndexed { i, start -> row("next-$i", Instant.parse(start)) }
        val result = loadWeeklyTrips(requestedFrom.toEpochMilli(), exportAt.toEpochMilli()) { start, end, next ->
            if (start != weekStart.toString()) page(emptyList())
            else {
                assertEquals(weekEnd.toString(), end)
                if (next == null) page(firstPage, "?pageToken=overflow") else page(overflow)
            }
        }
        val trips = result.trips.map { Trip.parse(it) }
        val restored = TripImport.parse(TripImport.csv(trips) { null }).map { TripImport.restore(it.json) }
        assertEquals(64, restored.size)
        assertEquals(3, restored.count { it.distanceKm == 0.0 })
        assertEquals(listOf("next-3", "next-2", "next-1", "next-0"), restored.takeLast(4).map { it.id })
    }

    private fun row(id: String?, start: Instant, km: Int = 1): JSONObject = JSONObject()
        .put("id", id ?: JSONObject.NULL).put("startedAt", start.toString()).put("distance", km)

    private fun page(rows: List<JSONObject>, next: String? = null): JSONObject =
        JSONObject().put("_embedded", JSONObject().put("trips", JSONArray(rows))).apply {
            if (next != null) put("_links", JSONObject().put("next", JSONObject().put("href", next)))
        }
}
