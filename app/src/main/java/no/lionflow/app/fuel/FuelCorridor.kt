package no.lionflow.app.fuel

import no.lionflow.app.plan.Geo
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.RouteData
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt

internal data class FuelProjection(
    val station: FuelStation,
    val offer: FuelOffer,
    val anchor: Place,
    val bearing: Int,
    val roadKm: Double,
    val roadMin: Double,
    val roadFuel: Double,
    val straightKm: Double,
) {
    val key = "${station.id}@${(roadKm * 1000).roundToInt()}"
}

internal object FuelCorridor {
    fun find(
        route: RouteData,
        cumulativeFuel: DoubleArray,
        index: StationIndex,
        grade: FuelGrade,
        service: FuelServiceFilter,
        corridorKm: Double,
        now: Instant,
    ): List<FuelProjection> {
        val projections = HashMap<String, MutableList<FuelProjection>>()
        val lastSeen = HashMap<String, FuelProjection>()
        val ferries = BooleanArray(route.size)
        for (ferry in route.ferries) {
            if (ferry.endIdx > ferry.idx) {
                for (i in ferry.idx + 1..ferry.endIdx.coerceAtMost(route.size - 1)) ferries[i] = true
            }
        }
        // Query the index once per ~3 km of road, then project against its individual segments.
        var start = 0
        while (start < route.size - 1) {
            var end = start + 1
            var south = minOf(route.lat[start], route.lat[end])
            var north = maxOf(route.lat[start], route.lat[end])
            var west = minOf(route.lon[start], route.lon[end])
            var east = maxOf(route.lon[start], route.lon[end])
            while (end < route.size - 1 && route.km[end + 1] - route.km[start] <= 3.0) {
                end++
                south = minOf(south, route.lat[end])
                north = maxOf(north, route.lat[end])
                west = minOf(west, route.lon[end])
                east = maxOf(east, route.lon[end])
            }
            val dy = corridorKm / 110.57
            val dx = corridorKm / (111.32 * cos(Math.toRadians(maxOf(kotlin.math.abs(south), kotlin.math.abs(north)))).coerceAtLeast(0.1))
            if (east - west < 180) {
                for (station in index.within(south - dy, west - dx, north + dy, east + dx)) {
                    val offer = station.offer(grade, service, now) ?: continue
                    for (i in start + 1..end) {
                        if (ferries[i]) continue
                        val hit = project(route, cumulativeFuel, station, offer, i, corridorKm) ?: continue
                        val hits = projections.getOrPut(station.id) { mutableListOf() }
                        val previous = lastSeen.put(station.id, hit)
                        val closest = hits.lastOrNull()
                        // Coalesce one pass, keeping another occurrence when the route returns later.
                        if (previous == null || hit.roadKm - previous.roadKm > 2.0 ||
                            (closest != null && hit.roadKm - closest.roadKm > 0.15 && kotlin.math.abs(((hit.bearing - closest.bearing + 540) % 360) - 180) >= 130)) {
                            hits.add(hit)
                        } else if (closest != null && hit.straightKm < closest.straightKm) {
                            hits[hits.lastIndex] = hit
                        }
                    }
                }
            }
            start = end
        }
        return projections.values.flatten().sortedWith(compareBy<FuelProjection> { it.roadKm }.thenBy { it.station.id })
    }

    private fun project(
        route: RouteData, fuel: DoubleArray, station: FuelStation, offer: FuelOffer, i: Int, limit: Double,
    ): FuelProjection? {
        val lonScale = cos(Math.toRadians(station.lat)) * 111.32
        val x = (route.lon[i] - route.lon[i - 1]) * lonScale
        val y = (route.lat[i] - route.lat[i - 1]) * 110.57
        val length2 = x * x + y * y
        if (length2 < 1e-10) return null
        val fraction = (((station.lon - route.lon[i - 1]) * lonScale * x +
            (station.lat - route.lat[i - 1]) * 110.57 * y) / length2).coerceIn(0.0, 1.0)
        val lat = interpolate(route.lat, i, fraction)
        val lon = interpolate(route.lon, i, fraction)
        val off = Geo.km(lat, lon, station.lat, station.lon)
        if (off > limit) return null
        val bearing = (Math.toDegrees(atan2(x, y)).roundToInt() + 360) % 360
        return FuelProjection(station, offer, Place("", "", lat, lon), bearing, interpolate(route.km, i, fraction),
            interpolate(route.min, i, fraction), interpolate(fuel, i, fraction), off)
    }

    private fun interpolate(values: DoubleArray, i: Int, t: Double) = values[i - 1] + (values[i] - values[i - 1]) * t

    fun shortlist(
        candidates: List<FuelProjection>, rangeFuel: Double, now: Instant, budget: Int, strategy: FuelStrategy,
    ): List<FuelProjection> {
        if (candidates.isEmpty()) return emptyList()
        val bucket = maxOf(0.2, rangeFuel / 3, (candidates.last().roadFuel - candidates.first().roadFuel) / (budget / 2).coerceAtLeast(1))
        val groups = candidates.groupBy { ((it.roadFuel - candidates.first().roadFuel) / bucket).toInt() }.values
        val near = compareBy<FuelProjection> { it.straightKm }.thenBy { it.roadKm }.thenBy { it.station.id }
        val cheap = compareByDescending<FuelProjection> { it.offer.price != null && it.offer.fresh(now) }
            .thenBy { it.offer.price ?: Double.MAX_VALUE }.then(near)
        val ordered = groups.map { group ->
            val closest = group.sortedWith(near)
            val cheapest = group.sortedWith(cheap)
            val first = if (strategy == FuelStrategy.FAST) closest else cheapest
            val second = if (strategy == FuelStrategy.FAST) cheapest else closest
            first.zip(second).flatMap { listOf(it.first, it.second) }.distinctBy { it.key }
        }
        return (0 until (ordered.maxOfOrNull { it.size } ?: 0)).asSequence()
            .flatMap { rank -> ordered.asSequence().mapNotNull { it.getOrNull(rank) } }.take(budget).toList()
    }
}
