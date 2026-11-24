package no.lionflow.app.fuel

import no.lionflow.app.model.Trip
import no.lionflow.app.plan.Place
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FuelManagementTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")
    private val time = now.toEpochMilli()

    @Test fun nearbyShowsFreshServiceMatchedPricesAndRanksByRoundTripCost() {
        val center = Place("Center", "", 43.0, 12.0)
        val near = station("near", 43.01, 1.8)
        val cheap = station("cheap", 43.05, 1.65)
        val old = station("old", 43.02, 1.0, now.minusSeconds(8 * 86400))
        val missing = station("missing", 43.03, null)
        val far = station("far", 43.2, 1.5)
        val index = StationIndex(listOf(near, cheap, old, missing, far))
        val filters = NearbyFilters(FuelGrade.PETROL, FuelServiceFilter.SELF, consumptionPer100 = 6.0)
        val found = nearbyStations(index, center, filters, emptySet(), now)
        assertEquals(listOf("cheap", "near"), found.rows.map { it.station.id })
        assertEquals(1.725, found.median!!, 1e-9)
        assertEquals(49.5, found.rows.first().fillCost!!, 1e-9)
        val checked = nearbyStations(index, center, filters, emptySet(), now, mapOf(cheap.id to 80.0))
        assertEquals(near.id, checked.rows.first().station.id)
        val filtered = nearbyStations(index, center, filters.copy(freshOnly = false, favoritesOnly = true), setOf(old.id, missing.id), now)
        assertEquals(setOf(old.id, missing.id), filtered.rows.map { it.station.id }.toSet())
        assertTrue(filtered.rows.all { it.saving == null })
        assertTrue(nearbyStations(index, center, filters.copy(network = StationNetwork.MOTORWAY), emptySet(), now).rows.isEmpty())
        assertTrue(nearbyStations(index, center, filters.copy(service = FuelServiceFilter.SERVED), emptySet(), now).rows.isEmpty())
    }

    @Test fun refuelNeedsAnIndependentStableReadingAndRejectsJitterAndStaleData() {
        val base = FuelGauge(time, 30.0, 5000.0, false)
        val first = observeFuel(null, base, FuelGrade.PETROL, 44.0)
        val noise = observeFuel(first.state, base.copy(at = time + 60_000, pct = 34.0), FuelGrade.PETROL, 44.0)
        assertNull(noise.state.rise)
        val jump = base.copy(at = time + 600_000, pct = 64.0, odometer = 5001.0)
        val candidate = observeFuel(first.state, jump, FuelGrade.PETROL, 44.0)
        assertNull(candidate.event)
        assertEquals(candidate.state, observeFuel(candidate.state, jump, FuelGrade.PETROL, 44.0).state)
        val settled = jump.copy(at = time + 720_000, pct = 63.0)
        val event = observeFuel(candidate.state, settled, FuelGrade.PETROL, 44.0)
        assertEquals(14.96, event.event!!.amount!!, 1e-9)
        assertTrue(event.event.quantityEstimated)
        assertFalse(event.event.confirmed)
        assertNull(event.event.total)
        assertNull(observeFuel(event.state, settled.copy(at = time + 900_000), FuelGrade.PETROL, 44.0).event)
        assertNull(observeFuel(first.state, jump.copy(at = time + 48 * 3_600_000L), FuelGrade.PETROL, 44.0).state.rise)
        assertNull(observeFuel(first.state, jump.copy(odometer = 5010.0), FuelGrade.PETROL, 44.0).state.rise)
    }

    @Test fun onlyUnambiguousNearbyStationCanSupplyAnEstimatedPrice() {
        val event = Refuel(at = time, grade = FuelGrade.PETROL, amount = 10.0, lat = 43.0, lon = 12.0,
            source = RefuelSource.DETECTED, quantityEstimated = true)
        val a = station("a", 43.0002, 1.72)
        val b = station("b", 43.0004, 1.75)
        val matched = event.associate(StationIndex(listOf(a)), now)
        assertEquals(a.id, matched.stationId)
        assertEquals(1.72, matched.estimatedPrice!!, 1e-9)
        assertNull(matched.total)
        assertNull(event.associate(StationIndex(listOf(a, b)), now).stationId)
    }

    @Test fun confirmedSpendAndValueOfConsumptionHaveIndependentInputs() {
        val rows = listOf(
            Refuel(at = time, grade = FuelGrade.PETROL, amount = 20.0, total = 40.0),
            Refuel(at = time, grade = FuelGrade.PETROL, amount = 10.0, total = 15.0),
            Refuel(at = time, grade = FuelGrade.PETROL, amount = 10.0, total = null, quantityEstimated = true,
                source = RefuelSource.DETECTED, estimatedPrice = 1.5),
            Refuel(at = time, grade = FuelGrade.CNG, amount = 20.0, total = 22.0),
            Refuel(at = time - 5000, grade = FuelGrade.PETROL, amount = 20.0, total = 70.0),
        )
        val trip = Trip("t", time, time + 60_000, 100.0, 3600.0, null, null, null, calibrationLPer100 = 6.0)
        val report = fuelEconomics(rows, listOf(trip), time - 1, time + 1, FuelGrade.PETROL)
        assertEquals(55.0, report.spend!!, 1e-9)
        assertEquals(30.0, report.quantity!!, 1e-9)
        assertEquals(55.0 / 30, report.averagePrice!!, 1e-9)
        assertEquals(11.0, report.consumptionCost(report.averagePrice)!!, 1e-9)
        assertEquals(.11, report.costPerKm(report.averagePrice)!!, 1e-9)
        assertEquals(1, report.estimated)
        val cng = fuelEconomics(rows, listOf(trip), time - 1, time + 1, FuelGrade.CNG)
        assertEquals(22.0, cng.spend!!, 1e-9)
        assertNull(cng.consumptionCost(1.1))
    }

    @Test fun costBasisNeedsACompletePriceAndQuantityChainStartingNearEmpty() {
        val first = Refuel(at = time, grade = FuelGrade.PETROL, amount = 44.0, total = 88.0, before = 0.0, after = 100.0)
        val next = Refuel(at = time + 1, grade = FuelGrade.PETROL, amount = 22.0, total = 33.0, before = 50.0, after = 100.0)
        assertEquals(1.75, tankUnitCost(listOf(first, next), FuelGrade.PETROL, 44.0, time + 1)!!, 1e-9)
        assertNull(tankUnitCost(listOf(next), FuelGrade.PETROL, 44.0, time + 1))
        assertNull(tankUnitCost(listOf(first, next.copy(quantityEstimated = true)), FuelGrade.PETROL, 44.0, time + 1))
        assertEquals(next, Refuel.parse(next.json()))
    }

    private fun station(id: String, lat: Double, price: Double?, stamp: Instant = now) =
        FuelStation(id, id, "Brand", "", "", "", "", "IT", lat, 12.0, "Stradale", "test",
            listOf(FuelOffer(FuelGrade.PETROL, FuelService.SELF, price, "EUR", stamp)))
}
