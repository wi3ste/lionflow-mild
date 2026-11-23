package no.lionflow.app.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TripPeriodTest {
    private val rome = ZoneId.of("Europe/Rome")

    @Test fun weekUsesSevenCalendarDaysAcrossSpringClockChange() {
        val now = time("2026-03-31T10:00:00Z")
        val week = TripPeriod(7, now, rome)
        val start = time("2026-03-24T23:00:00Z") // March 25, local midnight.
        assertEquals(start, week.dayStarts.first())
        assertEquals(time("2026-03-28T23:00:00Z"), week.dayStarts[4])
        assertEquals(time("2026-03-29T22:00:00Z"), week.dayStarts[5])
        val trips = listOf(
            trip("future", now + 1),
            trip("today", now, 2.0),
            trip("late-sunday", time("2026-03-29T21:59:59Z"), 3.0),
            trip("first", start),
            trip("early", start - 1),
            trip("undated", null),
        )
        val listed = week.select(trips)
        assertEquals(listOf("today", "late-sunday", "first"), listed.map { it.id })
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0, 3.0, 0.0, 2.0), week.distanceByBucket(listed))
    }

    @Test fun bothOccurrencesOfTheRepeatedHourBelongToSunday() {
        val week = TripPeriod(7, time("2026-10-27T10:00:00Z"), rome)
        val trips = listOf(
            trip("summer-offset", time("2026-10-25T00:30:00Z")),
            trip("winter-offset", time("2026-10-25T01:30:00Z")),
        )
        assertEquals(trips, week.select(trips))
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0), week.distanceByBucket(trips))
    }

    @Test fun periodIncludesAllRowsAndBucketsCoverTheWholeMonth() {
        val now = time("2026-10-27T10:00:00Z")
        val week = TripPeriod(7, now, rome)
        val trips = List(70) { trip("trip-$it", now - it * 60_000L) }
        assertEquals(trips, week.select(trips))
        val month = TripPeriod(30, now, rome)
        val throughoutMonth = month.dayStarts.mapIndexed { i, start -> trip("day-$i", start) }
        assertEquals(throughoutMonth, month.select(throughoutMonth))
        assertEquals(listOf(2.0, 7.0, 7.0, 7.0, 7.0), month.distanceByBucket(throughoutMonth, 7))
    }

    @Test fun zeroDistanceServerTripDoesNotHideAnotherCarTrip() {
        val now = time("2026-10-27T10:00:00Z")
        val stationary = trip("server", now, 0.0)
        val drive = trip("car", now - 5 * 60_000L)
        val sameId = drive.copy(id = stationary.id)
        assertEquals(listOf(stationary, drive), CarTrips.withFallback(listOf(stationary), listOf(drive, drive, sameId)))
    }

    private fun trip(id: String, at: Long?, km: Double = 1.0) =
        Trip(id, at, at, km, null, null, null, null)

    private fun time(text: String) = Instant.parse(text).toEpochMilli()
}
