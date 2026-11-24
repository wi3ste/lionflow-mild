package no.lionflow.app.fuel

import no.lionflow.app.model.Trip
import no.lionflow.app.plan.RouteData

internal enum class FuelProfile(val label: String) {
    COMBUSTION("Combustion"), MILD("Mild hybrid"), FULL("Full hybrid"), PLUG_IN("Plug-in hybrid (fuel only)");
}

internal enum class ConsumptionSource(val label: String) {
    HISTORY("Driving history"), MANUAL("Manual consumption"), DEFAULT("Typical consumption");
}

internal data class FuelConsumption(
    val per100: Double,
    val source: ConsumptionSource,
    private val profile: FuelProfile,
    private val useSpeed: Boolean,
    private val safety: Double,
) {
    fun forRoute(route: RouteData): DoubleArray {
        val result = DoubleArray(route.size)
        val ferry = BooleanArray(route.size)
        for (crossing in route.ferries) {
            if (crossing.endIdx > crossing.idx) {
                for (i in crossing.idx + 1..crossing.endIdx.coerceAtMost(route.size - 1)) ferry[i] = true
            }
        }
        for (i in 1 until route.size) {
            result[i] = result[i - 1] + if (ferry[i]) 0.0 else amount(route.km[i] - route.km[i - 1], route.speed[i])
        }
        return result
    }

    fun amount(km: Double, speed: Double = 45.0): Double =
        km * per100 / 100 * (if (useSpeed) speedFactor(profile, speed) else 1.0) * (1 + safety / 100)

    companion object {
        fun estimate(grade: FuelGrade, profile: FuelProfile, trips: List<Trip>, manual: Double?, safety: Double): FuelConsumption {
            if (manual != null) return FuelConsumption(manual, ConsumptionSource.MANUAL, profile, false, safety)
            // Bifuel history lacks per-trip grades. Plug-in history may include energy from external charging.
            val samples = if (profile != FuelProfile.PLUG_IN && grade in setOf(FuelGrade.DIESEL, FuelGrade.PETROL)) {
                trips.asSequence().distinctBy { it.id }.filter { it.distanceKm in 3.0..1_500.0 }.take(150).mapNotNull { trip ->
                    val rate = trip.liters?.let { it * 100 / trip.distanceKm } ?: trip.avgLPer100
                    if (rate == null || rate !in 2.0..25.0) return@mapNotNull null
                    val speed = trip.avgSpeedKmh?.takeIf { it in 10.0..145.0 } ?: 70.0
                    rate / speedFactor(profile, speed) to trip.distanceKm.coerceAtMost(150.0)
                }.toList()
            } else emptyList()
            val sorted = samples.map { it.first }.sorted()
            val median = sorted.getOrNull(sorted.size / 2)
            val valid = if (median == null) emptyList() else samples.filter { it.first in median * 0.55..median * 1.7 }
            val distance = valid.sumOf { it.second }
            if (valid.size >= 3 && distance >= 50.0) {
                return FuelConsumption(valid.sumOf { it.first * it.second } / distance, ConsumptionSource.HISTORY, profile, true, safety)
            }
            val baseline = when (grade) {
                FuelGrade.PETROL -> when (profile) {
                    FuelProfile.COMBUSTION, FuelProfile.PLUG_IN -> 7.5
                    FuelProfile.MILD -> 6.8
                    FuelProfile.FULL -> 5.8
                }
                FuelGrade.DIESEL -> 6.1
                FuelGrade.LPG -> 9.0
                FuelGrade.CNG -> 4.8
            }
            return FuelConsumption(baseline, ConsumptionSource.DEFAULT, profile, true, safety)
        }

        private fun speedFactor(profile: FuelProfile, speed: Double): Double = when {
            speed >= 105 -> 1.25
            speed >= 85 -> 1.10
            speed >= 50 -> 1.0
            profile == FuelProfile.FULL -> 0.83
            profile == FuelProfile.MILD -> 0.99
            else -> 1.10
        }
    }
}
