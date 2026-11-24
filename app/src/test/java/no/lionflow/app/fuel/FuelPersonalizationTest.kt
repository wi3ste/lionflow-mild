package no.lionflow.app.fuel

import kotlinx.coroutines.runBlocking
import no.lionflow.app.model.Trip
import no.lionflow.app.plan.RouteData
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FuelPersonalizationTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")
    private val petrol = FuelGrade.PETROL
    private val hybrid = FuelProfile.MILD
    private val urban = listOf(4.4, 4.5, 4.6, 4.5, 4.7, 4.5).mapIndexed { i, rate -> trip("u$i", 19.0 + i, 32.0, rate) }
    private val regional = listOf(4.7, 4.8, 4.9, 4.8).mapIndexed { i, rate -> trip("r$i", 74.0 + i, 67.0, rate) }
    private val highway = listOf(5.3, 5.4, 5.5, 5.4).mapIndexed { i, rate -> trip("h$i", 149.0 + i, 105.0, rate) }

    @Test fun similarlyDrivenShorterTripsExplainA714KmHighwayBetterThanCityHistory() {
        val noisy = trip("outlier", 145.0, 102.0, 17.0)
        val history = FuelConsumption.samples(petrol, hybrid, urban + regional + highway + noisy)
        val fast = FuelConsumption.predict(petrol, hybrid, history, null, 5.0, FuelJourney(714.0, 421.0, 0.9))
        val city = FuelConsumption.predict(petrol, hybrid, history, null, 5.0, FuelJourney(21.0, 40.0, 0.0))
        assertEquals(ConsumptionSource.COMPARABLE, fast.source)
        assertEquals(5.4, fast.per100, 0.11)
        assertEquals(4.5, city.per100, 0.11)
        assertTrue(fast.samples.all { it.trip.id.startsWith("h") })
    }

    @Test fun matchingTraceTakesPriorityAndRequiresTheIntermediateRoute() {
        val road = RouteData(
            doubleArrayOf(45.0, 45.0), doubleArrayOf(9.0, 9.1),
            doubleArrayOf(0.0, 8.0), doubleArrayOf(0.0, 12.0), doubleArrayOf(0.0, 40.0),
        )
        val sample = FuelConsumption.samples(petrol, hybrid, listOf(trip("matched", 8.0, 40.0, 4.8))).single()
        val local = sample.copy(trace = (0..8).map { 45.0 to 9.0 + it * 0.0125 })
        assertTrue(local.matches(road))
        assertFalse(local.copy(trace = local.trace.toMutableList().apply { this[4] = 45.1 to 9.05 }).matches(road))
        val prediction = FuelConsumption.predict(petrol, hybrid, listOf(local), null, 0.0,
            FuelJourney.fromRoute(road), setOf("matched"))
        assertEquals(ConsumptionSource.MATCHED, prediction.source)
        assertEquals(4.8, prediction.per100, 0.01)
        assertEquals(ConsumptionSource.DEFAULT, FuelConsumption.predict(petrol, hybrid, listOf(local), null, 0.0).source)
    }

    @Test fun unitsMustBeExplicitOrCorroboratedAndSpeedMustBeCredible() {
        fun parse(fuel: String, duration: Int = 1200) = Trip.parse(JSONObject(
            """{"id":"t","distance":20,"duration":$duration,"energyConsumptions":[{"type":"Fuel",$fuel}]}"""
        ))
        val explicitlyMeasured = parse(""""consumption":120,"consumptionUnit":"cl","avgConsumption":6,"avgConsumptionUnit":"l/100km"""")
        assertEquals(6.0, explicitlyMeasured.calibrationLPer100!!, 1e-8)
        assertNull(parse(""""consumption":1.2""").calibrationLPer100)
        assertEquals(6.0, parse(""""consumption":1200,"avgConsumption":60""").calibrationLPer100!!, 1e-8)
        assertEquals(6.0, parse(""""consumption":1.2,"avgConsumption":16.7,"avgConsumptionUnit":"km/l"""").calibrationLPer100!!, 1e-8)
        assertNull(parse(""""consumption":1.2,"consumptionUnit":"l","avgConsumption":12,"avgConsumptionUnit":"l/100km"""").calibrationLPer100)
        val implausibleSpeed = parse(""""consumption":1.2,"consumptionUnit":"l"""", 120)
        assertTrue(FuelConsumption.samples(petrol, hybrid, listOf(implausibleSpeed)).isEmpty())
    }

    @Test fun prudentMarginChangesReserveWithoutIncreasingExpectedConsumption() {
        val model = FuelConsumption.estimate(petrol, hybrid, emptyList(), 5.0, 10.0)
        val expected = model.expectedAmount(100.0)
        val prudent = model.amount(100.0)
        assertEquals(5.0, expected, 1e-8)
        assertEquals(5.5, prudent, 1e-8)
        val settings = FuelConstraints(40.0, 7.2, 2.0, FuelStrategy.ECONOMY)
        assertNotNull(FuelOptimizer.plan(expected, 100.0, 60.0, settings, emptyList(), now, 1.8))
        assertNull(FuelOptimizer.plan(prudent, 100.0, 60.0, settings, emptyList(), now, 1.8))
    }

    @Test fun backtestHoldsOutTripsAndReportsAllThreeDistanceBands() {
        val history = FuelConsumption.samples(petrol, hybrid, urban + regional + highway)
        val estimates = FuelConsumption.backtest(petrol, hybrid, history)
        assertEquals(listOf(6, 4, 4), estimates.map { it.count })
        assertEquals(listOf("Short trips", "Medium trips", "Long trips"), estimates.map { it.band })
        assertTrue(estimates.all { it.personalized.mae > 0 && it.previous.mape.isFinite() && it.personalized.bias.isFinite() })
        estimates.forEach { println("bench model ${it.band} n=${it.count} personalized=${it.personalized} previous=${it.previous}") }
    }

    @Test fun progressiveSearchAndMemoReduceColdAndWarmProbeCounts() = runBlocking {
        val memo = FuelMemo<Int, String>(36, 3600)
        val road = FuelMemo<String, Int>(6, 3600)
        fun route() = road.get("same coordinates", now) ?: 1.also { road.put("same coordinates", it, now) }
        var probes = 0
        repeat(3) { run ->
            assertEquals(1, route())
            val oldProbes = probes
            val seen = mutableListOf<Int>()
            searchFuelWaves(if (run == 2) emptyList() else (1..18).toList(), 4) { wave ->
                for (id in wave) {
                    if (memo.get(id, now) == null) { probes++; memo.put(id, "verified", now) }
                    seen.add(id)
                }
                FuelStrategy.entries.associateWith { 5.0 }
            }
            assertEquals(if (run == 0) 8 else 0, probes - oldProbes)
            assertEquals(if (run == 2) emptyList() else (1..8).toList(), seen)
        }
        assertNull(memo.get(1, now.plusSeconds(3600)))
        println("bench routing former candidates=18+4 cold, current=$probes cold, current=0 warm and direct, paced interval=1100ms")
    }

    private fun trip(id: String, km: Double, speed: Double, rate: Double) =
        Trip(id, null, null, km, km * 3600 / speed, null, null, speed, rate * km / 100, rate)
}
