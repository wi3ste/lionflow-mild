package no.lionflow.app.model

import kotlin.math.abs

/** Usable trip readings in liters and L/100 km after checking their agreement. */
internal data class FuelConsumption(val liters: Double?, val average: Double?, val unverified: Boolean = false) {
    companion object {
        fun resolve(
            km: Double,
            liters: Double?,
            rate: Double?,
            explicitVolume: Boolean,
            explicitRate: Boolean,
            distanceRoundingKm: Double = 0.0,
        ): FuelConsumption {
            val distance = km.takeIf { it.isFinite() && it > 0 }
            if (liters == null) return FuelConsumption(null, rate)
            if (rate != null && distance != null) {
                val expectedLiters = rate * (distance / 100)
                // Allow half a cL/100 km for rate rounding and the CSV's distance rounding if applicable.
                val rounding = 0.005 * (distance / 100) + rate * (distanceRoundingKm / 100)
                if (expectedLiters.isFinite() && abs(liters - expectedLiters) <= rounding + 0.000001) {
                    return FuelConsumption(liters, rate)
                }
                if (explicitVolume && explicitRate) return FuelConsumption(null, null, unverified = true)
                if (!explicitVolume) return FuelConsumption(null, rate, unverified = true)
                return FuelConsumption(liters, rateFromVolume(liters, distance), unverified = true)
            }
            // A nonzero unitless total alone cannot distinguish liters from the mismatched feed.
            if (!explicitVolume && liters > 0) return FuelConsumption(null, rate, unverified = true)
            return FuelConsumption(liters, rate ?: distance?.let { rateFromVolume(liters, it) })
        }

        private fun rateFromVolume(liters: Double, km: Double): Double? =
            (liters / km * 100).takeIf(Double::isFinite)
    }
}
