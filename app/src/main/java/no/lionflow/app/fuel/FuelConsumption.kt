package no.lionflow.app.fuel

import no.lionflow.app.model.Trip
import no.lionflow.app.model.TripEnds
import no.lionflow.app.plan.Geo
import no.lionflow.app.plan.RouteData
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

internal enum class FuelProfile(val label: String) {
    COMBUSTION("Combustion"), MILD("Mild hybrid"), FULL("Full hybrid"), PLUG_IN("Plug-in hybrid (fuel only)");
}

internal enum class ConsumptionSource(val label: String) {
    MATCHED("Recorded trips on this route"), COMPARABLE("Comparable trips"), HISTORY("Driving history"),
    MANUAL("Manual consumption"), DEFAULT("Typical consumption");
}

internal data class FuelJourney(
    val km: Double,
    val minutes: Double,
    val motorwayShare: Double? = null,
    val ends: TripEnds? = null,
) {
    val speed get() = if (minutes > 0) km * 60 / minutes else 0.0

    companion object {
        fun fromRoute(route: RouteData): FuelJourney {
            val driving = (1 until route.size).filter { index -> route.ferries.none { index in (it.idx + 1)..it.endIdx } }
            val km = driving.sumOf { route.km[it] - route.km[it - 1] }
            val minutes = driving.sumOf { route.min[it] - route.min[it - 1] }
            val fast = driving.filter { route.speed[it] >= 95 }.sumOf { route.km[it] - route.km[it - 1] }
            val ends = if (route.size < 2) null else TripEnds(route.lat.first(), route.lon.first(), route.lat.last(), route.lon.last())
            return FuelJourney(km, minutes, (fast / km).takeIf { it.isFinite() }, ends)
        }
    }
}

internal data class FuelCalibration(
    val trip: Trip,
    val journey: FuelJourney,
    val rate: Double,
    val trace: List<Pair<Double, Double>> = emptyList(),
) {
    fun matches(sample: FuelCalibration): Boolean =
        trace.size >= 5 && trace.size == sample.trace.size && abs(journey.km - sample.journey.km) <= journey.km * 0.20 &&
            trace.zip(sample.trace).withIndex().all { (index, pair) ->
                Geo.km(pair.first.first, pair.first.second, pair.second.first, pair.second.second) <=
                    if (index == 0 || index == trace.lastIndex) 1.0 else 3.0
            }

    fun matches(route: RouteData): Boolean {
        if (trace.size < 5 || route.size < 2 || abs(journey.km - route.totalKm) > route.totalKm * 0.20) return false
        return trace.withIndex().all { (index, point) ->
            val target = route.totalKm * index / trace.lastIndex
            val found = route.km.binarySearch(target)
            val hi = (if (found >= 0) found else -found - 1).coerceIn(1, route.size - 1)
            val fraction = ((target - route.km[hi - 1]) / (route.km[hi] - route.km[hi - 1])).takeIf { it.isFinite() } ?: 0.0
            val lat = route.lat[hi - 1] + (route.lat[hi] - route.lat[hi - 1]) * fraction
            val lon = route.lon[hi - 1] + (route.lon[hi] - route.lon[hi - 1]) * fraction
            Geo.km(point.first, point.second, lat, lon) <= if (index == 0 || index == trace.lastIndex) 1.0 else 3.0
        }
    }
}

internal data class FuelError(val mae: Double, val mape: Double, val bias: Double)
internal data class FuelBacktest(val band: String, val count: Int, val personalized: FuelError, val previous: FuelError)

internal data class FuelConsumption(
    val per100: Double,
    val source: ConsumptionSource,
    private val profile: FuelProfile,
    private val useSpeed: Boolean,
    val safety: Double,
    val samples: List<FuelCalibration> = emptyList(),
) {
    val safetyFactor get() = 1 + safety / 100
    val sampleKm get() = samples.sumOf { it.journey.km }

    /** Reserve checks use this cumulative curve. Expected consumption uses the same curve without the margin. */
    fun forRoute(route: RouteData): DoubleArray {
        val result = DoubleArray(route.size)
        for (i in 1 until route.size) {
            val ferry = route.ferries.any { i in (it.idx + 1)..it.endIdx }
            result[i] = result[i - 1] + if (ferry) 0.0 else amount(route.km[i] - route.km[i - 1], route.speed[i])
        }
        return result
    }

    fun amount(km: Double, speed: Double = 45.0): Double = expectedAmount(km, speed) * safetyFactor

    fun expectedAmount(km: Double, speed: Double = 45.0): Double =
        km * per100 / 100 * if (useSpeed) legacySpeedFactor(profile, speed) else 1.0

    companion object {
        fun estimate(grade: FuelGrade, profile: FuelProfile, trips: List<Trip>, manual: Double?, safety: Double): FuelConsumption =
            predict(grade, profile, samples(grade, profile, trips), manual, safety)

        fun samples(grade: FuelGrade, profile: FuelProfile, trips: List<Trip>): List<FuelCalibration> {
            if (profile == FuelProfile.PLUG_IN || grade !in setOf(FuelGrade.DIESEL, FuelGrade.PETROL)) return emptyList()
            return trips.asSequence().distinctBy { it.id }.mapNotNull { trip ->
                val km = trip.distanceKm
                val rate = trip.calibrationLPer100 ?: return@mapNotNull null
                if (km !in 3.0..1500.0 || rate !in 2.0..25.0) return@mapNotNull null
                val wall = trip.endMillis?.let { end -> trip.startMillis?.let { (end - it) / 1000.0 } }
                    ?.takeIf { it >= 60 }
                val duration = trip.durationSec?.takeIf { it >= 60 && it.isFinite() }
                if (duration != null && wall != null && duration > wall * 1.25 + 120) return@mapNotNull null
                val minutes = (duration ?: wall)?.div(60) ?: trip.calibrationSpeedKmh?.takeIf { it in 8.0..145.0 }
                    ?.let { km * 60 / it } ?: return@mapNotNull null
                val journey = FuelJourney(km, minutes, ends = trip.calibrationEnds)
                if (journey.speed !in 8.0..145.0) return@mapNotNull null
                FuelCalibration(trip, journey, rate)
            }.take(150).toList()
        }

        fun predict(
            grade: FuelGrade, profile: FuelProfile, history: List<FuelCalibration>, manual: Double?, safety: Double,
            target: FuelJourney? = null, matching: Set<String> = emptySet(),
        ): FuelConsumption {
            if (manual != null) return FuelConsumption(manual, ConsumptionSource.MANUAL, profile, false, safety)
            val valid = removeOutliers(history)
            val sameRoad = valid.filter { it.trip.id in matching }
            val comparable = if (target == null || target.speed <= 0) emptyList() else valid.map { it to similarity(it.journey, target) }
                .filter { (sample, distance) -> distance <= 2.65 && abs(sample.journey.speed - target.speed) <= 28 }
                .sortedBy { it.second }.take(12)
            val representative = comparable.singleOrNull()?.let { (sample, distance) -> sample.journey.km >= 120 && distance <= 1.3 } == true
            val (source, used) = when {
                sameRoad.isNotEmpty() -> ConsumptionSource.MATCHED to sameRoad.map { it to 1.0 }
                (comparable.size >= 2 && comparable.sumOf { it.first.journey.km } >= 45) || representative ->
                    ConsumptionSource.COMPARABLE to comparable.map { (sample, distance) -> sample to 1 / (0.45 + distance) }
                valid.size >= 3 && valid.sumOf { it.journey.km } >= 50 -> ConsumptionSource.HISTORY to valid.map { it to 1.0 }
                else -> return FuelConsumption(typical(grade, profile), ConsumptionSource.DEFAULT, profile, true, safety)
            }
            // Limit leverage of any single very long trip. A weighted median tolerates skew and avoids extrapolation.
            val weighted = used.map { (sample, relevance) -> sample to sqrt(sample.journey.km.coerceAtMost(200.0)) * relevance }
                .sortedBy { it.first.rate }
            var weight = weighted.sumOf { it.second } / 2
            val median = weighted.first { (_, w) -> weight -= w; weight <= 0 }.first.rate
            return FuelConsumption(median, source, profile, false, safety, used.map { it.first })
        }

        fun backtest(grade: FuelGrade, profile: FuelProfile, history: List<FuelCalibration>): List<FuelBacktest> {
            val cases = history
            if (cases.size < 4) return emptyList()
            return cases.groupBy { if (it.journey.km < 25) "Short trips" else if (it.journey.km < 120) "Medium trips" else "Long trips" }
                .map { (band, samples) ->
                    val pairs = samples.map { held ->
                        val others = cases.filter { it.trip.id != held.trip.id }
                        val predicted = predict(grade, profile, others, null, 0.0, held.journey, others.filter { it.matches(held) }.map { it.trip.id }.toSet())
                        val current = predicted.expectedAmount(100.0, held.journey.speed)
                        val legacy = legacyRate(grade, profile, others, held.journey.speed)
                        Triple(held.rate, current, legacy)
                    }
                    fun metrics(prediction: (Triple<Double, Double, Double>) -> Double): FuelError {
                        val errors = pairs.map { prediction(it) - it.first }
                        return FuelError(errors.map(::abs).average(), pairs.zip(errors).map { (value, error) ->
                            abs(error) / value.first * 100
                        }.average(), errors.average())
                    }
                    FuelBacktest(band, pairs.size, metrics { it.second }, metrics { it.third })
                }
        }

        private fun similarity(a: FuelJourney, b: FuelJourney): Double {
            val roadMix = if (a.motorwayShare != null && b.motorwayShare != null) abs(a.motorwayShare - b.motorwayShare) else 0.0
            val first = a.ends
            val second = b.ends
            val endpointBonus = if (first != null && second != null &&
                Geo.km(first.aLat, first.aLon, second.aLat, second.aLon) < 2.5 &&
                Geo.km(first.bLat, first.bLon, second.bLat, second.bLon) < 2.5) 0.30 else 0.0
            return (abs(a.speed - b.speed) / 22 + abs(ln(a.km / b.km)) * 0.43 +
                abs(ln(a.minutes / b.minutes)) * 0.27 + roadMix - endpointBonus).coerceAtLeast(0.0)
        }

        private fun removeOutliers(history: List<FuelCalibration>): List<FuelCalibration> {
            val groups = history.groupBy { speedClass(it.journey.speed) }
            return history.filter { sample ->
                val peers = groups.getValue(speedClass(sample.journey.speed))
                val pool = if (peers.size >= 4) peers else history
                if (pool.size < 4) return@filter true
                val rates = pool.map { it.rate }.sorted()
                val center = rates[rates.size / 2]
                val deviations = rates.map { abs(it - center) }.sorted()
                val limit = maxOf(1.6, center * if (peers.size >= 4) 0.32 else 0.5, deviations[deviations.size / 2] * 3.5)
                abs(sample.rate - center) <= limit
            }
        }

        private fun speedClass(speed: Double) = if (speed < 45) 0 else if (speed < 80) 1 else 2

        private fun legacyRate(grade: FuelGrade, profile: FuelProfile, data: List<FuelCalibration>, speed: Double): Double {
            val normalized = data.map { it.rate / legacySpeedFactor(profile, it.journey.speed) to it.journey.km.coerceAtMost(150.0) }
            val sorted = normalized.map { it.first }.sorted()
            val center = sorted.getOrNull(sorted.size / 2)
            val valid = if (center == null) emptyList() else normalized.filter { it.first in center * 0.55..center * 1.7 }
            val distance = valid.sumOf { it.second }
            val rate = if (valid.size >= 3 && distance >= 50) valid.sumOf { it.first * it.second } / distance else typical(grade, profile)
            return rate * legacySpeedFactor(profile, speed) * 1.10
        }

        private fun typical(grade: FuelGrade, profile: FuelProfile) = when (grade) {
            FuelGrade.PETROL -> when (profile) {
                FuelProfile.COMBUSTION, FuelProfile.PLUG_IN -> 7.5
                FuelProfile.MILD -> 6.8
                FuelProfile.FULL -> 5.8
            }
            FuelGrade.DIESEL -> 6.1
            FuelGrade.LPG -> 9.0
            FuelGrade.CNG -> 4.8
        }

        private fun legacySpeedFactor(profile: FuelProfile, speed: Double): Double = when {
            speed >= 105 -> 1.25
            speed >= 85 -> 1.10
            speed >= 50 -> 1.0
            profile == FuelProfile.FULL -> 0.83
            profile == FuelProfile.MILD -> 0.99
            else -> 1.10
        }
    }
}
