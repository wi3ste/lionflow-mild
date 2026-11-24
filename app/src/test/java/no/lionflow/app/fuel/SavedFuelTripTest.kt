package no.lionflow.app.fuel

import no.lionflow.app.model.Trip
import no.lionflow.app.plan.Place
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SavedFuelTripTest {
    private val departure = Instant.parse("2026-11-24T08:00:00Z")
    private val time = departure.toEpochMilli()
    private val from = Place("Roma", "", 41.9, 12.5, "IT")
    private val via = Place("Orvieto", "", 42.7, 12.1, "IT")
    private val to = Place("Firenze", "", 43.8, 11.2, "IT")
    private val request = FuelTripRequest(from, to, listOf(via), true, departure, FuelGrade.PETROL, FuelServiceFilter.SELF,
        FuelProfile.MILD, 6.4, 5.0, FuelConstraints(44.0, 21.0, 4.0, FuelStrategy.ECONOMY), 8.0)
    private val stop = SavedFuelStop("mimit:1", "Station", 100.0, 42.4, 12.4, 20.4, 1.7, true, 20.4, 102.0)
    private val plan = SavedFuelTrip("saved", request, departure, 520.0, 320.0, 331.0, 32.0, 54.0, 34.68, listOf(stop), 4.5)

    @Test fun persistedTripKeepsInputsAmountsAndLocksWithoutRouteGeometry() {
        val json = plan.json()
        assertEquals(plan, SavedFuelTrip.parse(json))
        assertFalse(json.has("geometry"))
        val active = ActiveFuelTrip(plan, time, 10000.0, time + 2 * 3_600_000L, setOf("leg-a", "leg-b"), 1)
        assertEquals(active, ActiveFuelTrip.parse(active.json()))
        assertEquals(79.0, active.progress(10079.0)!!, 0.0)
        assertNull(active.progress(9999.0))
        assertNull(ActiveFuelTrip(plan, time, null).progress(10079.0))
    }

    @Test fun matchingConfirmedStopIsStableAcrossProjectionOffsetsAndRejectsAmbiguousLoops() {
        val offer = FuelOffer(FuelGrade.PETROL, FuelService.SELF, 1.8, "EUR", departure)
        val station = FuelStation(stop.stationId, stop.name, "", "", "", "", "", "IT",
            stop.lat, stop.lon, "Stradale", "test", listOf(offer))
        fun projection(km: Double) = FuelProjection(station, offer, via, 0, km, 58.0, 7.0, 1.0)
        val outgoing = projection(101.0)
        val returning = projection(420.0)
        val picks = FuelStationSearch(listOf(outgoing, returning), 520.0).restoreStops(listOf(stop))
        assertEquals(listOf(outgoing to FuelStopEdit(20.4)), picks)
        assertTrue(FuelStationSearch(listOf(projection(99.0), projection(101.0)), 520.0).restoreStops(listOf(stop)).isEmpty())
    }

    @Test fun comparisonUsesOnlySelectedLegsWithCompatibleUnits() {
        fun leg(id: String, start: Long, km: Double, rate: Double?) =
            Trip(id, start, start + 1_800_000L, km, 1800.0, null, null, null, calibrationLPer100 = rate)
        val a = leg("a", time + 60_000, 30.0, 6.0)
        val b = leg("b", time + 2_000_000, 32.0, 5.5)
        val outside = leg("outside", time - 900_000, 15.0, 6.5)
        val active = ActiveFuelTrip(plan, time, null, time + 4_000_000)
        assertEquals(listOf(a, b), active.links(listOf(b, a, a, outside)))
        val actual = actualFuelTrip(listOf(a, b), FuelGrade.PETROL)!!
        assertEquals(62.0, actual.distance, 1e-9)
        assertEquals(60.0, actual.durationMinutes!!, 1e-9)
        assertEquals(3.56, actual.liters!!, 1e-9)
        assertNull(actualFuelTrip(listOf(a, b.copy(calibrationLPer100 = null)), FuelGrade.PETROL)?.liters)
        assertNull(actualFuelTrip(listOf(a, b), FuelGrade.CNG)?.liters)
    }
}
