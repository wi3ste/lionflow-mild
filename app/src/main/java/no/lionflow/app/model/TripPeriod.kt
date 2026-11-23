package no.lionflow.app.model

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Local calendar days, ending with the elapsed part of today. */
internal class TripPeriod(
    days: Int,
    private val nowMillis: Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    private val firstDay = today.minusDays(days - 1L)
    val dayStarts = List(days) { firstDay.plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }

    fun contains(startMillis: Long?): Boolean = startMillis != null && startMillis in dayStarts.first()..nowMillis

    fun select(trips: List<Trip>): List<Trip> = trips.filter { contains(it.startMillis) }

    fun distanceByBucket(trips: List<Trip>, daysPerBucket: Int = 1): List<Double> {
        val count = (dayStarts.size + daysPerBucket - 1) / daysPerBucket
        val leadingDays = count * daysPerBucket - dayStarts.size
        val sums = MutableList(count) { 0.0 }
        for (trip in trips) {
            val start = trip.startMillis ?: continue
            if (!contains(start)) continue
            val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val offset = ChronoUnit.DAYS.between(firstDay, date).toInt()
            val bucket = (offset + leadingDays) / daysPerBucket
            sums[bucket] += trip.distanceKm
        }
        return sums
    }
}
