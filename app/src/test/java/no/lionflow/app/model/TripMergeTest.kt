package no.lionflow.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripMergeTest {
    @Test fun apiReplacesAnOverlappingEstimateAndDoesNotDoubleCountFuelOrDistance() {
        val api = trip("api", 60, 80, 12.0, liters = 0.7)
        val local = trip("local", 59, 83, 13.0, estimated = true)
        val result = TripMerge.of(listOf(api), emptyList(), listOf(local))
        assertEquals(listOf(api), result.trips)
        assertEquals(12.0, result.trips.sumOf { it.distanceKm }, 0.0)
        assertEquals(0.7, result.trips.mapNotNull { it.liters }.sum(), 0.0)
        assertEquals(1, result.suppressedEstimates)
    }

    @Test fun aggregateOdometerSnapshotAndItsImportedCopyAreSuppressedByRealDrive() {
        val api = trip("api", 70, 83, 4.0)
        val accumulated = trip("car-1", 20, 120, 24.0, estimated = true)
        val result = TripMerge.of(listOf(api), listOf(accumulated), listOf(accumulated))
        assertEquals(listOf(api), result.trips)
        assertEquals(2, result.suppressedEstimates)
        assertEquals(0, result.estimated)
    }

    @Test fun retainsAdjacentAndUnmatchedDrivesAndMarksThemAsEstimated() {
        val api = trip("api", 60, 70, 8.0)
        val adjacent = trip("car", 70, 80, 8.0)
        val result = TripMerge.of(listOf(api), emptyList(), listOf(adjacent))
        assertEquals(listOf(adjacent.copy(estimated = true), api), result.trips)
        assertEquals(1, result.estimated)
        assertEquals(0, result.suppressedEstimates)
        assertNull(result.trips.first().liters)
    }

    @Test fun aDelayedEstimateWithMatchingDistanceAndTimestampsIsSuppressed() {
        val api = trip("api", 60, 63, 2.0)
        val delayed = trip("car", 64, 67, 2.2)
        val result = TripMerge.of(listOf(api), emptyList(), listOf(delayed))
        assertEquals(listOf(api), result.trips)
    }

    @Test fun apiWinsExactIdAgainstAnImportAndLocalDuplicateCopiesAppearOnce() {
        val api = trip("api", 20, 30, 5.0)
        val local = trip("car-123", 90, 100, 8.0, estimated = true)
        val result = TripMerge.of(listOf(api), listOf(api.copy(distanceKm = 100.0), local), listOf(local))
        assertEquals(listOf(local, api), result.trips)
        assertFalse(result.trips.last().estimated)
        assertTrue(result.trips.first().estimated)
        assertEquals(1, result.suppressedEstimates)
    }

    @Test fun electricAverageUsesMeasuredTripsAndOnlyDistanceWithEnergyData() {
        val measured = trip("api", 10, 50, 20.0).copy(kwh = 4.0)
        val unknown = trip("unknown", 60, 80, 40.0)
        val local = trip("car", 100, 120, 6.0, estimated = true).copy(kwh = 3.0)
        assertEquals(TripConsumption(20.0, false), TripConsumption.electric(listOf(measured, unknown, local)))
        assertEquals(TripConsumption(50.0, true), TripConsumption.electric(listOf(unknown, local)))
        assertNull(TripConsumption.electric(listOf(unknown)))
    }

    @Test fun estimatedSourceSurvivesCsvRoundTripAndLegacyCarIdIsRecognized() {
        val trip = trip("car-123", 90, 120, 15.0, estimated = true)
        val csv = TripExport.csv(listOf(trip)) { null }
        assertTrue(TripImport.restore(TripImport.parse(csv).single().json).estimated)
        val old = "id,start,end,distance_km\ncar-123,2026-11-22 09:00,2026-11-22 09:30,15\n"
        assertTrue(TripImport.restore(TripImport.parse(old).single().json).estimated)
    }

    private fun trip(id: String, start: Long, end: Long, km: Double, liters: Double? = null, estimated: Boolean = false) =
        Trip(id, start * 60_000L, end * 60_000L, km, null, null, null, null, liters = liters, estimated = estimated)
}
