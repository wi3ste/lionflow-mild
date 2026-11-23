package no.lionflow.app.model

import kotlin.math.abs

internal data class TripMerge(
    val trips: List<Trip>,
    val api: Int,
    val imported: Int,
    val estimated: Int,
    val suppressedEstimates: Int,
) {
    fun aggregateReport(): String =
        "App history sources · API: $api · Imported: $imported · Estimated: $estimated\n" +
            "Overlapping or duplicate estimates suppressed: $suppressedEstimates\n"

    companion object {
        fun of(api: List<Trip>, imported: List<Trip>, recorded: List<Trip>): TripMerge {
            val real = api.distinctBy { it.id }
            val apiIds = real.mapTo(hashSetOf()) { it.id }
            val imports = imported.filterNot { it.estimated || it.id in apiIds }.distinctBy { it.id }
            val authoritative = real + imports
            val authoritativeIds = authoritative.mapTo(hashSetOf()) { it.id }
            val candidates = recorded + imported.filter { it.estimated }
            val accepted = mutableListOf<Trip>()
            val seen = hashSetOf<String>()
            for (trip in candidates) {
                if (trip.startMillis == null || trip.id in authoritativeIds || !seen.add(trip.id)) continue
                if (authoritative.any { overlaps(trip, it) } || accepted.any { overlaps(trip, it) }) continue
                accepted += trip.copy(estimated = true)
            }
            return TripMerge(
                (authoritative + accepted).sortedByDescending { it.startMillis ?: Long.MIN_VALUE },
                real.size, imports.size, accepted.size, candidates.size - accepted.size,
            )
        }

        /**
         * Snapshot-derived trips can cover several real drives. A substantial overlap
         * takes precedence over the estimate, even if its distance spans more than one drive.
         */
        private fun overlaps(estimated: Trip, reference: Trip): Boolean {
            val a = estimated.startMillis ?: return false
            val b = reference.startMillis ?: return false
            val endA = end(estimated, a)
            val endB = end(reference, b)
            val overlap = minOf(endA, endB) - maxOf(a, b)
            val shorter = minOf(endA - a, endB - b)
            if (overlap > 0 && overlap >= shorter / 2) return true

            val similarDistance = estimated.distanceKm > 0 && reference.distanceKm > 0 &&
                abs(estimated.distanceKm - reference.distanceKm) <= maxOf(0.5, reference.distanceKm * 0.15)
            if (!similarDistance) return false

            // A delayed status snapshot can shift both timestamps. Require close starts
            // and ends as well as similar distance. A shared end/start is two consecutive drives.
            if (shorter > 0 && (endA == b || endB == a)) return false
            val tolerance = 5 * 60_000L
            return abs(a - b) <= tolerance && abs(endA - endB) <= tolerance
        }

        private fun end(trip: Trip, start: Long): Long =
            trip.endMillis?.takeIf { it > start }
                ?: trip.durationSec?.takeIf { it.isFinite() && it > 0 }?.let { start + (it * 1000).toLong() }
                ?: start
    }
}

internal data class TripConsumption(val per100: Double, val estimated: Boolean) {
    companion object {
        fun electric(trips: List<Trip>): TripConsumption? {
            val withEnergy = trips.filter { (it.kwh ?: -1.0) >= 0 && it.distanceKm > 0 && it.distanceKm.isFinite() }
            val measured = withEnergy.filterNot { it.estimated }
            val sample = measured.ifEmpty { withEnergy }
            if (sample.isEmpty()) return null
            return TripConsumption(
                sample.sumOf { it.kwh ?: 0.0 } / sample.sumOf { it.distanceKm } * 100,
                estimated = measured.isEmpty(),
            )
        }
    }
}
