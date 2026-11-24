package no.lionflow.app.fuel

import no.lionflow.app.model.Trip
import no.lionflow.app.plan.Ferry
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.PlanApi
import kotlinx.coroutines.runBlocking
import no.lionflow.app.plan.RouteData
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FuelPlannerTest {
    private val now = Instant.parse("2026-07-29T10:00:00Z")

    @Test fun historyUsesValidDistanceWeightedTripsAndManualConsumptionOverrides() {
        val trips = listOf(trip("a", 40.0, 2.4, null), trip("b", 20.0, null, 6.0),
            trip("c", 70.0, 4.2, null), trip("bad", 100.0, 24.0, null), trip("tiny", 1.0, 10.0, null))
        val history = FuelConsumption.estimate(FuelGrade.PETROL, FuelProfile.FULL, trips, null, 10.0)
        assertEquals(ConsumptionSource.HISTORY, history.source)
        assertEquals(6.0, history.per100, 1e-8)
        assertEquals(6.6, history.amount(100.0, 70.0), 1e-8)
        assertEquals(history.amount(100.0, 30.0), history.amount(100.0, 115.0), 1e-8)
        val manual = FuelConsumption.estimate(FuelGrade.PETROL, FuelProfile.FULL, trips, 9.0, 20.0)
        assertEquals(ConsumptionSource.MANUAL, manual.source)
        assertEquals(10.8, manual.amount(100.0, 30.0), 1e-8)
        assertEquals(10.8, manual.amount(100.0, 120.0), 1e-8)
        val cng = FuelConsumption.estimate(FuelGrade.CNG, FuelProfile.FULL, trips, null, 0.0)
        assertEquals(ConsumptionSource.DEFAULT, cng.source)
        assertEquals(4.8, cng.amount(100.0, 70.0), 1e-8)
        val plugIn = FuelConsumption.estimate(FuelGrade.PETROL, FuelProfile.PLUG_IN, trips, null, 0.0)
        assertEquals(ConsumptionSource.DEFAULT, plugIn.source)
        assertEquals(7.5, plugIn.amount(100.0, 70.0), 1e-8)
    }

    @Test fun ferryKeepsTimeWithoutBurningRoadFuel() {
        val road = route()
        road.ferries = listOf(Ferry("Sea", 42.0, 12.1, 42.0, 12.2, 12.0, 1, 2))
        val model = FuelConsumption.estimate(FuelGrade.PETROL, FuelProfile.COMBUSTION, emptyList(), 5.0, 0.0)
        assertArrayEquals(doubleArrayOf(0.0, 0.5, 0.5, 1.0), model.forRoute(road), 1e-8)
        assertEquals(30.0, road.totalMin, 0.0)
    }

    @Test fun corridorUsesSegmentsAndRetainsBothDirectionsOnARoundTrip() {
        val route = route().roundTrip()
        val a = station("A", 42.003, 12.145, 1.8)
        val b = station("B", 42.10, 12.150, 1.1)
        val c = station("C", 42.003, 12.145, 1.5).copy(offers = emptyList())
        val index = StationIndex(listOf(a, b, c))
        val hits = FuelCorridor.find(route, DoubleArray(route.size) { route.km[it] / 10 }, index,
            FuelGrade.PETROL, FuelServiceFilter.ANY, 0.8, now)
        assertEquals(listOf("A", "A"), hits.map { it.station.id })
        assertEquals(14.5, hits[0].roadKm, 0.01)
        assertEquals(45.5, hits[1].roadKm, 0.01)
        assertEquals(listOf(90, 270), hits.map { it.bearing })
        assertEquals(listOf(a, c), index.within(42.0, 12.1, 42.02, 12.2).toList())
    }

    @Test fun enoughStartingFuelDoesNotCauseANeedlessStop() {
        val result = plan(roadFuel = 9.0, initial = 12.0, candidates = listOf(stop("cheap", 1.0, 0.5)))!!
        assertTrue(result.stops.isEmpty())
        assertEquals(3.0, result.remaining, 1e-8)
        assertEquals(0.0, result.purchaseCost!!, 1e-8)
        assertEquals(16.2, result.usedCost!!, 1e-8)
    }

    @Test fun multipleRefuelsPreserveReserveCapacityAndCosts() {
        val a = stop("A", 7.0, 1.2, detourFuel = 0.5, detourMin = 4.0)
        val b = stop("B", 19.0, 1.8, detourFuel = 0.5, detourMin = 6.0)
        val result = plan(28.0, 10.0, listOf(a, b))!!
        assertEquals(listOf("A", "B"), result.stops.map { it.candidate.station.id })
        assertTrue(result.stops.all { it.arrival >= 2 - 1e-7 && it.departure <= 15 + 1e-7 })
        assertEquals(2.0, result.remaining, 1e-8)
        assertEquals(30.0, result.used, 1e-8)
        assertEquals(result.used + result.remaining - 10.0, result.purchased, 1e-8)
        assertEquals(result.stops.sumOf { it.purchased * it.candidate.offer.price!! }, result.purchaseCost!!, 1e-8)
        assertTrue(result.usedCost!! > result.purchaseCost!!)
        assertTrue(result.totalMin > result.driveMin)
        assertEquals(40.0, result.stops[0].atMinute, 1e-8)
    }

    @Test fun canBuyOnlyEnoughToReachCheaperFuel() {
        val expensive = stop("A", 5.0, 2.50)
        val cheap = stop("B", 12.0, 1.00)
        val result = plan(20.0, 8.0, listOf(expensive, cheap))!!
        assertEquals(listOf("A", "B"), result.stops.map { it.candidate.station.id })
        assertEquals(6.0, result.stops[0].purchased, 1e-8)
        assertEquals(8.0, result.stops[1].purchased, 1e-8)
    }

    @Test fun cheapEarlyFullTankCanAvoidAnExpensiveExtraStop() {
        val cheap = stop("A", 1.0, 0.70)
        val expensive = stop("B", 11.0, 2.50)
        val result = plan(14.0, 4.0, listOf(cheap, expensive))!!
        assertEquals(1, result.stops.size)
        assertEquals("A", result.stops[0].candidate.station.id)
        assertEquals(15.0, result.stops[0].departure, 1e-8)
    }

    @Test fun threeStrategiesCanSelectThreeDifferentStops() {
        val slow = stop("cheap", 5.0, 1.0, detourFuel = 0.1, detourMin = 18.0)
        val middle = stop("middle", 6.0, 1.4, detourFuel = 0.1, detourMin = 5.0)
        val fast = stop("fast", 7.0, 2.2, detourFuel = 0.1, detourMin = 1.0)
        val nodes = listOf(slow, middle, fast)
        val results = FuelStrategy.entries.map { plan(15.0, 10.0, nodes, it)!!.stops.single().candidate.station.id }
        assertEquals(listOf("cheap", "middle", "fast"), results)
    }

    @Test fun detourFuelCanMakeACloseStationUnreachable() {
        assertNull(plan(19.0, 8.0, listOf(stop("A", 6.0, 1.5, detourFuel = 1.0))))
        assertNull(plan(28.0, 10.0, listOf(stop("A", 7.0, 1.5), stop("B", 23.0, 1.5))))
    }

    @Test fun unknownPriceCanKeepRouteFeasibleWithoutInventingAmountSpent() {
        val result = plan(15.0, 10.0, listOf(stop("unpriced", 6.0, null)))!!
        assertNull(result.purchaseCost)
        assertNull(result.usedCost)
        assertNull(result.stops.single().cost)
        val priced = stop("priced", 7.0, 2.0)
        assertEquals("priced", plan(15.0, 10.0, listOf(result.stops.single().candidate, priced))!!.stops.single().candidate.station.id)
    }

    @Test fun staleLowPriceReceivesAnEconomicPenalty() {
        val good = stop("fresh", 6.0, 1.60)
        val old = stop("old", 6.0, 1.45)
        val stale = old.copy(projection = old.projection.copy(offer = old.offer.copy(reportedAt = now.minusSeconds(86400L * 9))))
        assertEquals("fresh", plan(15.0, 10.0, listOf(good, stale))!!.stops.single().candidate.station.id)
    }

    @Test fun locksAndFixedQuantitiesAreRespectedAndInvalidEditsAreRejected() {
        val a = stop("A", 4.0, 1.5)
        val b = stop("B", 10.0, 1.5)
        val result = plan(14.0, 8.0, listOf(a, b), locked = mapOf(a.key to FuelStopEdit(6.0)))!!
        assertEquals(listOf("A", "B"), result.stops.map { it.candidate.station.id })
        assertEquals(6.0, result.stops.first().purchased, 1e-8)
        assertNull(plan(14.0, 8.0, listOf(a, b), locked = mapOf(a.key to FuelStopEdit(15.0))))
        assertNull(plan(14.0, 8.0, listOf(a, b), excluded = setOf(a.key)))
    }

    @Test fun canFuelAtDepartureWhenAlreadyAtTheStation() {
        val atStart = stop("at start", 0.0, 1.5)
        val result = plan(8.0, 1.0, listOf(atStart))!!
        assertEquals(0.0, result.stops.single().atMinute, 1e-8)
        assertEquals(2.0, result.remaining, 1e-8)
        assertNull(plan(8.0, 1.0, listOf(stop("away", 0.01, 1.50))))
    }

    @Test fun laterVerificationImprovesEconomyWhileFastKeepsMotorwayStation() = runBlocking {
        val motorway = stop("motorway", 7.0, 2.0)
        val cheaper = stop("local", 6.0, 1.70)
        val tooFar = stop("too far", 6.0, 0.80)
        val model = FuelConsumption.estimate(FuelGrade.PETROL, FuelProfile.COMBUSTION, emptyList(), 10.0, 0.0)
        val projections = listOf(motorway) + List(7) { tooFar } + cheaper
        var batches = 0
        val verified = mutableListOf<FuelCandidate>()
        searchFuelWaves(projections, 8) { batch ->
            batches++
            if (batches == 2) assertEquals("motorway", plan(15.0, 10.0, listOf(motorway))!!.stops.single().candidate.station.id)
            verified += batch.mapNotNull { candidate ->
                val road = when (candidate.station.id) {
                    "motorway" -> PlanApi.FuelAccess(0.0, 0.0, 0.0, 0.0, "")
                    "local" -> PlanApi.FuelAccess(5.0, 5.0, 4.0, 4.0, "")
                    else -> PlanApi.FuelAccess(8.0, 8.0, 6.0, 6.0, "")
                }
                FuelCandidate.verified(candidate.projection, road, 10.0, model)
            }
            FuelStrategy.entries.associateWith { plan(15.0, 10.0, verified, it)!!.score }
        }
        assertEquals(2, batches)
        assertEquals(listOf("motorway", "local"), verified.map { it.station.id })
        val economic = plan(15.0, 10.0, verified)!!
        val fast = plan(15.0, 10.0, verified, FuelStrategy.FAST)!!
        assertEquals("local", economic.stops.single().candidate.station.id)
        assertEquals("motorway", fast.stops.single().candidate.station.id)
        assertEquals(13.60, economic.purchaseCost!!, 1e-8)
        assertEquals(economic.purchaseCost!!, economic.score, 1e-8)
        assertTrue(economic.totalMin > fast.totalMin)
    }

    @Test fun strategiesAgreeWhenOneStopWinsBothPriceAndTime() {
        val motorway = stop("motorway", 7.0, 1.2)
        val slower = stop("slower", 6.0, 2.2, detourFuel = 0.25, detourMin = 5.0)
        val plans = FuelStrategy.entries.map { plan(15.0, 10.0, listOf(slower, motorway), it)!! }
        assertTrue(plans.all { it.stops == plans.first().stops })
    }

    @Test fun downstreamRejoinCreditsTheReplacedRoadAndCannotSkipALockedStop() {
        val early = stop("early", 5.0, 1.2, detourFuel = 0.1, detourMin = 1.0)
            .copy(rejoin = FuelRejoin(km = 56.0, minutes = 28.0, fuel = 5.6))
        val inside = stop("inside", 5.4, 1.3)
        val trip = plan(15.0, 10.0, listOf(early))!!
        assertEquals(14.6, trip.used, 1e-8)
        assertEquals(146.0, trip.totalKm, 1e-8)
        assertEquals(74.0, trip.driveMin, 1e-8)
        assertTrue(trip.remaining >= 2.0)
        assertNull(plan(15.0, 10.0, listOf(early, inside), locked = mapOf(early.key to FuelStopEdit(), inside.key to FuelStopEdit())))
    }

    @Test fun shortlistIncludesCheapAndNearbyStationsAcrossTheRoute() {
        val candidates = (0..8).flatMap { band ->
            val near = stop("near-$band", band * 4.0, 2.0).projection
            val cheap = stop("cheap-$band", band * 4.0 + 0.1, 1.2).projection.copy(straightKm = 0.5)
            listOf(near, cheap)
        }
        val selected = FuelCorridor.shortlist(candidates, 12.0, now, 36).map { it.station.id }
        for (band in 0..8) {
            assertTrue("near-$band" in selected)
            assertTrue("cheap-$band" in selected)
        }
    }

    private fun plan(roadFuel: Double, initial: Double, candidates: List<FuelCandidate>, strategy: FuelStrategy = FuelStrategy.ECONOMY,
                     locked: Map<String, FuelStopEdit> = emptyMap(), excluded: Set<String> = emptySet()) =
        FuelOptimizer.plan(roadFuel, roadFuel * 10, roadFuel * 5, FuelConstraints(15.0, initial, 2.0, strategy),
            candidates, now, 1.8, locked, excluded)

    private fun stop(id: String, at: Double, price: Double?, detourFuel: Double = 0.0, detourMin: Double = 0.0): FuelCandidate {
        val station = station(id, 42.0, 12.0, price)
        val projection = FuelProjection(station, station.offers.single(), Place("", "", 42.0, 12.0), 90, at * 10, at * 5, at, 0.0)
        return FuelCandidate(projection, detourFuel * 10, detourFuel * 10, detourMin * 1.25, detourMin * 0.75, detourFuel, detourFuel)
    }

    private fun station(id: String, lat: Double, lon: Double, price: Double?) =
        FuelStation(id, id, "", "", "", "", "", "IT", lat, lon, "Stradale", "test",
            listOf(FuelOffer(FuelGrade.PETROL, FuelService.SELF, price, "EUR", now)))

    private fun trip(id: String, km: Double, liters: Double?, rate: Double?) =
        Trip(id, null, null, km, null, null, null, 70.0, liters, rate)

    private fun route() = RouteData(doubleArrayOf(42.0, 42.0, 42.0, 42.0), doubleArrayOf(12.0, 12.1, 12.2, 12.3),
        doubleArrayOf(0.0, 10.0, 20.0, 30.0), doubleArrayOf(0.0, 10.0, 20.0, 30.0), doubleArrayOf(60.0, 60.0, 60.0, 60.0))
}
