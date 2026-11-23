package no.lionflow.app.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripFuelTest {
    private fun apiTrip(fuel: String, mildHybrid: Boolean = true): Trip = Trip.parse(
        JSONObject().put("id", "test").put("distance", 20.0)
            .put("energyConsumptions", JSONArray().put(JSONObject(fuel))
                .put(JSONObject("""{"type":"Electric","consumption":420,"avgConsumption":2000}"""))),
        mildHybrid,
    )

    @Test fun explicitFuelUnitsStaySeparate() {
        val trip = apiTrip("""{"type":"Fuel","consumption":1200,"consumptionUnit":"mL",
            "avgConsumption":6.0,"avgConsumptionUnit":"L/100 km"}""")
        assertEquals(1.2, trip.liters)
        assertEquals(6.0, trip.avgLPer100)
        assertNull(trip.kwh)
        assertNull(trip.avgKwhPer100)
        assertFalse(trip.fuelUnitsEstimated)
        val rateOnly = apiTrip("""{"type":"Fuel","avgConsumption":20,"avgConsumptionUnit":"km/L"}""")
        assertNull(rateOnly.liters)
        assertEquals(5.0, rateOnly.avgLPer100)
    }

    @Test fun ambiguousFuelUnitsAreOmittedForMildHybrids() {
        val payload = """{"type":"Fuel","consumption":1200,"avgConsumption":6}"""
        val mild = apiTrip(payload)
        assertNull(mild.liters)
        assertNull(mild.avgLPer100)
        val previous = apiTrip(payload, mildHybrid = false)
        assertEquals(1.2, previous.liters)
        assertTrue(previous.fuelUnitsEstimated)
        assertEquals(0.42, previous.kwh)
        assertNull(apiTrip("""{"type":"Fuel","consumption":2,"unit":"unknown"}""").liters)
    }

    @Test fun csvRetainsFuelValuesZeroAndProvenance() {
        val start = requireNotNull(TripImport.date("2026-10-06 10:30"))
        val base = Trip("trip-a", start, start + 600_000, 12.5, 600.0, 1.25, 10.0, 75.0, 0.641, 5.128, true)
        val trips = listOf(base, base.copy(id = "zero", liters = 0.0, avgLPer100 = 0.0, fuelUnitsEstimated = false),
            base.copy(id = "missing", liters = null, avgLPer100 = null, fuelUnitsEstimated = false))
        val rows = TripImport.parse(TripImport.csv(trips) { if (it == base) "Commute" else null })
        assertEquals("Commute", rows.first().tag)
        assertEquals(trips, rows.map { TripImport.restore(JSONObject(it.json.toString())) })
    }

    @Test fun importsLegacyAndExplicitThirdPartyColumns() {
        val legacy = TripImport.parse("""
            id,start,end,distance_km,duration_min,energy_kwh,avg_kwh_per_100km,avg_speed_kmh,tag
            old,2026-10-06 10:30,2026-10-06 11:00,20,30,2,10,40,Work
        """.trimIndent()).single()
        assertEquals(2.0, TripImport.restore(legacy.json).kwh)
        assertNull(TripImport.restore(legacy.json).liters)
        val thirdParty = TripImport.parse("""
            Start;End;Consumo (l / 100 km);Distance;Carburante (l);Energie (kWh)
            2026-10-06 10:30;2026-10-06 11:00;6,2;20;1,24;2,1
        """.trimIndent()).single()
        val trip = TripImport.restore(thirdParty.json)
        assertEquals(20.0, trip.distanceKm, 0.0)
        assertEquals(1.24, trip.liters)
        assertEquals(6.2, trip.avgLPer100)
        assertEquals(2.1, trip.kwh)
        assertFalse(trip.fuelUnitsEstimated)
    }

    @Test fun csvAndCarReportsDoNotDoubleCountTheSameDrive() {
        val online = Trip("server", 60_000L, 660_000L, 20.04, 600.0, null, null, null)
        val csv = online.copy(id = "csv", distanceKm = 20.0, liters = 1.2, avgLPer100 = 6.0)
        val own = online.copy(id = "car-1")
        val older = own.copy(id = "car-2", startMillis = 3_600_000L, endMillis = 4_200_000L)
        val trips = CarTrips.withFallback(TripImport.merge(listOf(online), listOf(csv)), listOf(own, older))
        assertEquals(listOf("server", "car-2"), trips.map { it.id })
        assertEquals(40.08, trips.sumOf { it.distanceKm }, 0.0001)
        assertEquals(1.2, trips.mapNotNull { it.liters }.sum(), 0.0)
        assertEquals(1.2 / 20.04 * 100, requireNotNull(Trip.fuelAverage(trips)).first, 0.0001)
        val rateOnly = older.copy(avgLPer100 = 4.0, fuelUnitsEstimated = true)
        val (rate, estimated) = requireNotNull(Trip.fuelAverage(listOf(csv, rateOnly)))
        assertEquals((1.2 + 0.8016) / 40.04 * 100, rate, 0.0001)
        assertTrue(estimated)
    }
}
