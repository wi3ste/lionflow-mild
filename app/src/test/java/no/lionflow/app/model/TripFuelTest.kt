package no.lionflow.app.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripFuelTest {
    private val fuel = """{"type":"Fuel","consumption":1.2,"avgConsumption":600}"""
    private val electric = """{"type":"Electric","consumption":3.8,"avgConsumption":19000}"""

    private fun apiTrip(vararg energy: String, mildHybrid: Boolean = false, distance: Double = 20.0): Trip =
        Trip.parse(JSONObject().put("id", "test").put("distance", distance)
            .put("energyConsumptions", JSONArray(energy.map(::JSONObject))), mildHybrid)

    @Test fun usesDocumentedFuelUnits() {
        val trip = apiTrip(fuel)
        assertEquals(1.2, trip.liters)
        assertEquals(6.0, trip.avgLPer100)
        assertNull(trip.kwh)
        assertFalse(trip.fuelUnitsEstimated)
        val low = apiTrip("""{"type":"Fuel","consumption":0.03,"avgConsumption":15}""")
        assertEquals(0.03, low.liters)
        assertEquals(0.15, low.avgLPer100)
        assertFalse(low.fuelUnitsEstimated)
    }

    @Test fun batteryElectricUsesKwhForTotalAndWhForAverage() {
        val trip = apiTrip(electric)
        assertEquals(3.8, trip.kwh)
        assertEquals(19.0, trip.avgKwhPer100)
        assertNull(trip.liters)
        assertNull(trip.avgLPer100)
    }

    @Test fun plugInHybridKeepsBothEnergyTypes() {
        val trip = apiTrip(fuel, electric)
        assertEquals(1.2, trip.liters)
        assertEquals(6.0, trip.avgLPer100)
        assertEquals(3.8, trip.kwh)
        assertEquals(19.0, trip.avgKwhPer100)
        assertFalse(trip.fuelUnitsEstimated)
    }

    @Test fun mildHybridKeepsFuelAndOmitsElectric() {
        val trip = apiTrip(fuel, electric, mildHybrid = true)
        assertEquals(1.2, trip.liters)
        assertEquals(6.0, trip.avgLPer100)
        assertNull(trip.kwh)
        assertNull(trip.avgKwhPer100)
        assertFalse(trip.fuelUnitsEstimated)
    }

    @Test fun supportedExplicitUnitsOverrideSchemaDefaults() {
        val trip = apiTrip("""{"type":"Fuel","consumption":1200,"consumptionUnit":"mL",
            "avgConsumption":6.4,"avgConsumptionUnit":"L/100 km"}""",
            """{"type":"Electric","consumption":3800,"unit":"Wh","avgConsumption":19,"avgConsumptionUnit":"kWh/100 km"}""",
            distance = 18.75)
        assertEquals(1.2, trip.liters)
        assertEquals(6.4, trip.avgLPer100)
        assertEquals(3.8, trip.kwh)
        assertEquals(19.0, trip.avgKwhPer100)
        assertFalse(trip.fuelUnitsEstimated)
        val rateOnly = apiTrip("""{"type":"Fuel","avgConsumption":20,"avgConsumptionUnit":"km/L"}""")
        assertNull(rateOnly.liters)
        assertEquals(5.0, rateOnly.avgLPer100)
    }

    @Test fun opposingExplicitReadingsAreWithheldAndAnExplicitVolumeCanOverrideAnImplicitRate() {
        val conflict = apiTrip("""{"type":"Fuel","consumption":1200,"consumptionUnit":"mL",
            "avgConsumption":12,"avgConsumptionUnit":"L/100 km"}""")
        assertNull(conflict.liters)
        assertNull(conflict.avgLPer100)
        assertTrue(conflict.fuelDataUnverified)
        assertNull(Trip.fuelAverage(listOf(conflict)))
        val explicitVolume = apiTrip("""{"type":"Fuel","consumption":120,"consumptionUnit":"cL",
            "avgConsumption":1200}""")
        assertEquals(1.2, explicitVolume.liters)
        assertEquals(6.0, explicitVolume.avgLPer100)
        assertFalse(explicitVolume.fuelUnitsEstimated)
    }

    @Test fun inconsistentImplicitVolumeNeverReachesTheSummaryOrTheCsv() {
        val short = apiTrip("""{"type":"Fuel","consumption":60,"avgConsumption":600}""",
            electric, mildHybrid = true, distance = 10.0)
        val long = apiTrip("""{"type":"Fuel","consumption":160,"avgConsumption":400}""", distance = 40.0)
        val noFuel = apiTrip(electric, distance = 30.0)
        val stationary = apiTrip("""{"type":"Fuel","consumption":4,"avgConsumption":0}""", distance = 0.0)
        val trips = listOf(short, long, noFuel, stationary).mapIndexed { i, trip -> trip.copy(id = "trip-$i") }
        assertTrue(short.fuelDataUnverified)
        assertNull(short.liters)
        assertNull(short.kwh)
        assertEquals(6.0, short.avgLPer100)
        assertNull(long.liters)
        assertNull(stationary.liters)
        assertEquals(0.0, stationary.avgLPer100)
        val (average, estimated) = requireNotNull(Trip.fuelAverage(trips))
        assertEquals(4.4, average, 0.0001)
        assertFalse(estimated)
        val csv = TripImport.csv(trips) { null }
        val header = csv.lineSequence().first().split(',')
        val first = csv.lineSequence().drop(1).first().split(',')
        assertEquals("", first[header.indexOf("fuel_liters")])
        assertEquals("6.0", first[header.indexOf("avg_l_per_100km")])
        assertEquals("false", first[header.indexOf("fuel_units_estimated")])
        val restored = TripImport.parse(csv).map { TripImport.restore(it.json) }
        assertEquals(trips.map { it.avgLPer100 }, restored.map { it.avgLPer100 })
        assertEquals(trips.map { it.liters }, restored.map { it.liters })
        assertEquals(4.4, requireNotNull(Trip.fuelAverage(restored)).first, 0.0001)
    }

    @Test fun verifiedPhevRateStillCountsAsEngineUseWithAnInconsistentVolume() {
        val trip = apiTrip("""{"type":"Fuel","consumption":45,"avgConsumption":450}""", electric, distance = 10.0)
        assertNull(trip.liters)
        assertEquals(4.5, trip.avgLPer100)
        assertEquals(3.8, trip.kwh)
        assertEquals(19.0, trip.avgKwhPer100)
        val fuelOnly = trip.copy(kwh = 0.0)
        assertEquals(10.0, HybridSplit.of(listOf(fuelOnly)).engineKm, 0.0)
    }

    @Test fun rejectsInvalidValuesAndKeepsZero() {
        val missing = apiTrip("""{"type":"Fuel","consumption":null,"avgConsumption":-1}""",
            """{"type":"Electric","consumption":"Infinity","avgConsumption":"bogus"}""")
        assertNull(missing.liters)
        assertNull(missing.avgLPer100)
        assertNull(missing.kwh)
        assertNull(missing.avgKwhPer100)
        val zero = apiTrip("""{"type":"Fuel","consumption":0,"avgConsumption":0}""",
            """{"type":"Electric","consumption":0,"avgConsumption":0}""", distance = 0.0)
        assertEquals(0.0, zero.liters)
        assertEquals(0.0, zero.avgLPer100)
        assertEquals(0.0, zero.kwh)
        assertEquals(0.0, zero.avgKwhPer100)
        assertNull(apiTrip("""{"type":"Fuel","consumption":2,"unit":"unknown"}""").liters)
        assertNull(apiTrip("""{"type":"Fuel","avgConsumption":0,"avgConsumptionUnit":"km/L"}""").avgLPer100)
        val unverified = apiTrip("""{"type":"Fuel","consumption":1.2}""")
        assertNull(unverified.liters)
        assertNull(unverified.avgLPer100)
        assertTrue(unverified.fuelDataUnverified)
        assertNull(Trip.fuelAverage(listOf(unverified, missing)))
        val explicitVolume = """{"type":"Fuel","consumption":1.2,"consumptionUnit":"L"}"""
        assertEquals(6.0, apiTrip(explicitVolume).avgLPer100)
        assertNull(apiTrip(explicitVolume, distance = 0.0).avgLPer100)
        assertEquals(0.0, Trip.fuelAverage(listOf(apiTrip("""{"type":"Fuel","consumption":0}""")))?.first)
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

    @Test fun csvKeepsExplicitElectricZeroSeparateFromMissingReadings() {
        val zero = apiTrip("""{"type":"Electric","consumption":0,"avgConsumption":0}""")
        val trips = listOf(zero.copy(id = "zero-total", avgKwhPer100 = null), zero.copy(id = "zero-rate", kwh = null))
        val restored = TripImport.parse(TripImport.csv(trips) { null }).map { TripImport.restore(it.json) }
        assertEquals(0.0, restored[0].kwh)
        assertNull(restored[0].avgKwhPer100)
        assertNull(restored[1].kwh)
        assertEquals(0.0, restored[1].avgKwhPer100)
        val legacy = TripImport.restore(JSONObject("""{"id":"legacy","kwh":0,"avg":0}"""))
        assertNull(legacy.kwh)
        assertNull(legacy.avgKwhPer100)
    }

    @Test fun importedLegacyTotalsAreCheckedInMemoryAndNewVerifiedTotalsRoundTrip() {
        val csv = """
            id,start,end,distance_km,duration_min,energy_kwh,avg_kwh_per_100km,avg_speed_kmh,tag,fuel_liters,avg_l_per_100km,fuel_units_estimated
            inconsistent,2026-10-06 10:30,2026-10-06 10:40,20,10,,,,,120,6,false
            coherent,2026-10-06 10:30,2026-10-06 10:40,20,10,,,,,1.2,6,false
            unknown,2026-10-06 10:30,2026-10-06 10:40,20,10,,,,,120,,false
        """.trimIndent()
        val rows = TripImport.parse(csv)
        val (inconsistent, coherent, unknown) = rows.map { TripImport.restore(it.json) }
        assertEquals(120.0, rows[0].json.getDouble("liters"), 0.0)
        assertNull(inconsistent.liters)
        assertEquals(6.0, inconsistent.avgLPer100)
        assertTrue(inconsistent.fuelDataUnverified)
        assertEquals(1.2, coherent.liters)
        assertTrue(coherent.fuelUnitsEstimated)
        assertNull(unknown.liters)
        assertNull(unknown.avgLPer100)
        assertTrue(unknown.fuelDataUnverified)
        val (average, estimated) = requireNotNull(Trip.fuelAverage(listOf(inconsistent, coherent, unknown)))
        assertEquals(6.0, average, 0.0001)
        assertFalse(estimated)
        val verified = apiTrip("""{"type":"Fuel","consumption":0.15,"unit":"L"}""", distance = 0.0)
        val restored = TripImport.restore(TripImport.parse(TripImport.csv(listOf(verified)) { null }).single().json)
        assertEquals(0.15, restored.liters)
        assertNull(restored.avgLPer100)
        assertFalse(restored.fuelDataUnverified)
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
        assertEquals(6.0, requireNotNull(Trip.fuelAverage(trips)).first, 0.0001)
        val rateOnly = older.copy(avgLPer100 = 4.0, fuelUnitsEstimated = true)
        val (rate, estimated) = requireNotNull(Trip.fuelAverage(listOf(csv, rateOnly)))
        assertEquals(6.0, rate, 0.0001)
        assertFalse(estimated)
        val (estimatedRate, estimateOnly) = requireNotNull(Trip.fuelAverage(listOf(rateOnly)))
        assertEquals(4.0, estimatedRate, 0.0001)
        assertTrue(estimateOnly)
    }
}
