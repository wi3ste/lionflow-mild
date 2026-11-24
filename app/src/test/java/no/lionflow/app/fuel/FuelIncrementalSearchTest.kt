package no.lionflow.app.fuel

import kotlinx.coroutines.runBlocking
import no.lionflow.app.plan.PlanApi
import no.lionflow.app.plan.RouteData
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FuelIncrementalSearchTest {
    private val at = Instant.parse("2026-11-24T08:00:00Z")
    private val road = RouteData(
        DoubleArray(715) { latitude(it.toDouble()) }, DoubleArray(715) { 12.0 },
        DoubleArray(715) { it.toDouble() }, DoubleArray(715) { it * 0.6 }, DoubleArray(715) { 100.0 },
    )
    private val prediction = FuelConsumption.estimate(FuelGrade.PETROL, FuelProfile.MILD, emptyList(), 7.0, 0.0)
    private val fuel = prediction.forRoute(road)
    private val constraints = FuelConstraints(35.0, 24.0, 3.0, FuelStrategy.BALANCED)
    private val windows = FuelAccessWindows(road, fuel, emptyList(), 8.0)

    @Test fun corridorOf917StationsActuallyFindsNewAlternativesAfterAnUnproductiveExtension() = runBlocking {
        val motorway = station("motorway", 280.0, 0.0, 1.949)
        val slipRoad = station("exit-station", 281.0, 0.010, 1.65)
        val cheaperButClosed = List(11) {
            station("closed-$it", 280.5 + it * 0.04, 0.004 + it * 0.0001, 1.52 + it * 0.003)
        }
        val otherSites = List(904) {
            station("site-$it", 30.0 + (it % 650), 0.017 + it * 0.000011, 1.99)
        }
        val ledger = FuelStationSearch(project(listOf(motorway, slipRoad) + cheaperButClosed + otherSites), road.totalKm)
        assertEquals(917, ledger.corridorSize)
        val requestsByStation = mutableMapOf<String, Int>()
        val verifier = simulated { window, site ->
            requestsByStation[site.id] = requestsByStation.getOrDefault(site.id, 0) + 1
            when (site.id) {
                motorway.id -> access(window, 0.0, 0.0)
                slipRoad.id -> if (window.replacedKm > 0) access(window, 1.7, 12.0) else PlanApi.FuelAccessResult.NoRoute
                else -> PlanApi.FuelAccessResult.NoRoute
            }
        }
        val automaticBudget = FuelProbeBudget(16)
        suspend fun check(batch: List<FuelSearchChoice>, budget: FuelProbeBudget) =
            ledger.check(batch, budget, { at }, { verifier.verify(it, windows.forStation(it), prediction, 8.0, budget) })

        var plans = emptyMap<FuelStrategy, FuelPlan>()
        var firstCalls: Int? = null
        searchFuelWaves(ledger.automatic(constraints, fuel.last(), at), initialWave = 1, stop = { automaticBudget.exhausted }) {
            check(it, automaticBudget)
            plans = betterFuelPlans(plans, solve(ledger, at))
            if (plans.isNotEmpty() && firstCalls == null) firstCalls = automaticBudget.requests
            plans.mapValues { (_, plan) -> plan.score }
        }
        val opening = plans
        assertEquals(1, firstCalls)
        assertEquals(FuelStrategy.entries.size, opening.size)
        assertTrue(opening.values.all { it.onlyStop() == motorway.id })
        assertTrue(automaticBudget.requests <= 16)
        val firstVerified = ledger.candidates.single()
        val initialRows = ledger.summary(at).rows.map { it.choice.projection.key }.toSet()
        assertEquals(1.52, ledger.summary(at).corridorQuotes!!.low, 1e-9)

        val newlyExamined = mutableSetOf<String>()
        var improvingPress = 0
        var firstImprovementRequests = 0
        repeat(12) { iteration ->
            val current = ledger.summary(at)
            val budget = FuelProbeBudget(14)
            val progress = check(ledger.extension(constraints, fuel.last(), plans.getValue(FuelStrategy.ECONOMY), at), budget)
            val newRows = ledger.summary(at).rows.drop(current.rows.size)
            assertEquals(progress.examined, newRows.size)
            assertTrue(progress.examined > 0)
            assertTrue(newRows.all { it.choice.projection.key !in initialRows && newlyExamined.add(it.choice.projection.key) })
            assertTrue(budget.requests in 1..14)
            assertTrue(ledger.candidates.contains(firstVerified))
            plans = betterFuelPlans(plans, solve(ledger, at))
            if (iteration == 0) assertEquals(opening, plans)
            if (improvingPress == 0 && plans.getValue(FuelStrategy.ECONOMY).onlyStop() == slipRoad.id) {
                improvingPress = iteration + 1
                firstImprovementRequests = ledger.summary(at).requests
            }
        }
        val economy = plans.getValue(FuelStrategy.ECONOMY)
        val fast = plans.getValue(FuelStrategy.FAST)
        assertTrue("Improvement should appear after the first extension", improvingPress > 1)
        assertEquals(slipRoad.id, economy.onlyStop())
        assertEquals(motorway.id, fast.onlyStop())
        assertTrue(economy.purchaseCost!! < fast.purchaseCost!!)
        assertTrue(economy.totalMin > fast.totalMin)
        assertTrue(economy.remaining >= constraints.reserve - 1e-7)
        assertTrue(economy.stops.single().departure <= constraints.capacity)
        assertEquals(1, requestsByStation[motorway.id])
        val summary = ledger.summary(at)
        assertTrue(summary.examined > 36)
        assertTrue(summary.unexamined > 800)
        assertEquals(requestsByStation.values.sum(), summary.requests)
        assertEquals(1.65, summary.verifiedQuotes!!.low, 1e-9)
        assertEquals(1.949, summary.verifiedQuotes.high, 1e-9)
        println("Synthetic corridor=${summary.corridor} firstFeasibleRequests=$firstCalls autoRequests=${automaticBudget.requests} " +
            "winningPress=$improvingPress requestsAtImprovement=$firstImprovementRequests economy=${economy.purchaseCost} " +
            "fast=${fast.purchaseCost} extraMinutes=${economy.totalMin - fast.totalMin} finalExamined=${summary.examined} " +
            "finalRequests=${summary.requests} cacheHits=${summary.cacheHits}")
    }

    @Test fun timeoutIsRetriedWithAFeasiblePlanAndFinishedChecksAreNotRerouted() = runBlocking {
        var now = at
        val motorway = station("motorway", 280.0, 0.0, 1.949)
        val temporary = station("temporary", 281.0, 0.01, 1.64)
        val projections = project(listOf(motorway, temporary))
        val ledger = FuelStationSearch(projections, road.totalKm)
        var attempts = 0
        val verifier = simulated(clock = { now }) { window, site ->
            if (site == motorway) access(window, 0.0, 0.0)
            else if (++attempts == 1) PlanApi.FuelAccessResult.Unavailable(timeout = true)
            else access(window, 2.0, 6.0)
        }
        val budget = FuelProbeBudget(14)
        suspend fun check(choices: List<FuelSearchChoice>) = ledger.check(choices, budget, { now },
            { verifier.verify(it, windows.forStation(it), prediction, 8.0, budget) })
        check(ledger.automatic(constraints, fuel.last(), now))
        val opening = solve(ledger, now)
        assertEquals(motorway.id, opening.getValue(FuelStrategy.ECONOMY).onlyStop())
        val timeout = ledger.summary(now).rows.single { it.choice.projection.station.id == temporary.id }.check
        assertEquals(FuelCheckReason.TIMEOUT, (timeout as FuelCheck.Deferred).reason)
        assertTrue(ledger.extension(constraints, fuel.last(), opening.getValue(FuelStrategy.ECONOMY), now).isEmpty())

        now = at.plusSeconds(21)
        val retry = ledger.extension(constraints, fuel.last(), opening.getValue(FuelStrategy.ECONOMY), now)
        val progress = check(retry)
        assertEquals(FuelExtensionProgress(1, 1, 0, 1, 1), progress)
        assertEquals(3, budget.requests)
        assertEquals(temporary.id, solve(ledger, now).getValue(FuelStrategy.ECONOMY).onlyStop())
        assertFalse(ledger.hasMore)
        check(retry)
        assertEquals(3, budget.requests)

        val cachedBudget = FuelProbeBudget(14)
        val cached = verifier.verify(projections.last(), windows.forStation(projections.last()), prediction, 8.0, cachedBudget)
        assertTrue(cached is FuelCheck.Verified)
        assertEquals(0, cachedBudget.requests)
        assertEquals(1, cachedBudget.hits)
    }

    @Test fun directionalRejoinSubtractsTheOriginalSectionAndHonoursTheExtraDistanceLimit() = runBlocking {
        val site = station("off-motorway", 281.0, 0.056, 1.65)
        val projection = project(listOf(site)).single()
        assertTrue(projection.straightKm > 4.0)
        val alternatives = windows.forStation(projection)
        assertTrue(alternatives.first().entry.progress.km < projection.roadKm)
        assertTrue(alternatives.first().exit.progress.km > projection.roadKm)
        assertEquals(0, alternatives.first().entry.bearing)
        assertEquals(0, alternatives.first().exit.bearing)
        val good = simulated { window, _ ->
            if (window.replacedKm == 0.0) PlanApi.FuelAccessResult.NoRoute else access(window, 7.5, 10.0)
        }
        val accepted = good.verify(projection, alternatives, prediction, 8.0, FuelProbeBudget(16)) as FuelCheck.Verified
        assertTrue(accepted.candidate.outKm + accepted.candidate.backKm > 8.0)
        assertEquals(7.5, accepted.candidate.detourKm, 1e-8)
        val far = simulated { window, _ ->
            if (window.replacedKm == 0.0) PlanApi.FuelAccessResult.NoRoute else access(window, 8.01, 10.0)
        }
        val rejected = far.verify(projection, alternatives, prediction, 8.0, FuelProbeBudget(16)) as FuelCheck.Rejected
        assertEquals(FuelCheckReason.LIMIT, rejected.reason)
        assertEquals(8.01, rejected.netKm!!, 1e-8)

        val protected = FuelAccessWindows(road, fuel, listOf(projection.roadKm + 0.5), 8.0)
        assertTrue(protected.forStation(projection).all { it.exit.progress.km <= projection.roadKm + 0.5 })
    }

    @Test fun rateLimitKeepsNewStationsUnexaminedUntilTheServerAllowsRequests() = runBlocking {
        var now = at
        var throttled = false
        val stations = listOf(station("a", 280.0, 0.0, 1.91), station("b", 282.0, 0.0, 1.92))
        val ledger = FuelStationSearch(project(stations), road.totalKm)
        val verifier = simulated(clock = { now }) { window, _ ->
            if (!throttled) {
                throttled = true
                throw PlanApi.FuelRoutingRateLimited(60)
            }
            access(window, 0.0, 0.0)
        }
        suspend fun extend(budget: FuelProbeBudget) = ledger.check(ledger.extension(constraints, fuel.last(), null, now), budget,
            { now }, { verifier.verify(it, windows.forStation(it), prediction, 8.0, budget) })
        val first = FuelProbeBudget(14)
        assertEquals(1, extend(first).examined)
        assertEquals(1, first.requests)
        val blocked = FuelProbeBudget(14)
        assertEquals(0, extend(blocked).examined)
        assertEquals(0, blocked.requests)
        assertEquals(1, ledger.summary(now).unexamined)
        now = now.plusSeconds(60)
        val retry = FuelProbeBudget(14)
        val after = extend(retry)
        assertEquals(1, after.examined)
        assertEquals(1, after.retried)
        assertEquals(2, after.accepted)
        assertEquals(2, retry.requests)
    }

    @Test fun osrmErrorsAreNotConfusedWithNoRoute() {
        assertEquals(PlanApi.FuelAccessResult.NoRoute, PlanApi.parseFuelAccess(JSONObject("""{"code":"NoSegment"}""")))
        assertTrue(PlanApi.parseFuelAccess(JSONObject("""{"code":"InvalidQuery"}""")) is PlanApi.FuelAccessResult.Unavailable)
        val route = JSONObject(
            """{"code":"Ok","waypoints":[{"distance":0},{"distance":80},{"distance":8}],"routes":[
                {"geometry":"abc","legs":[{"distance":3000,"duration":180},{"distance":8000,"duration":420}]}]}""",
        )
        val parsed = PlanApi.parseFuelAccess(route) as PlanApi.FuelAccessResult.Routed
        assertEquals(3.0, parsed.access.outKm, 1e-8)
        assertEquals(8.0, parsed.access.backKm, 1e-8)
        route.getJSONArray("waypoints").getJSONObject(1).put("distance", 119)
        assertEquals(PlanApi.FuelAccessResult.NoRoute, PlanApi.parseFuelAccess(route))
    }

    @Test fun staleQuotesStayInDiagnosticsWithoutSettingTheStartingFuelValue() {
        val fresh = station("fresh", 280.0, 0.0, 1.95)
        val old = station("stale", 281.0, 0.01, 1.2).let { site ->
            site.copy(offers = listOf(site.offers.single().copy(reportedAt = at.minusSeconds(8 * 86400L))))
        }
        val ledger = FuelStationSearch(project(listOf(fresh, old)), road.totalKm)
        assertEquals(1.2, ledger.summary(at).corridorQuotes!!.low, 1e-9)
        assertEquals(1.95, ledger.referencePrice(at)!!, 1e-9)
        assertNull(ledger.referencePrice(at.plusSeconds(8 * 86400L)))
    }

    private fun FuelPlan.onlyStop() = stops.single().candidate.station.id

    private fun project(stations: List<FuelStation>) =
        FuelCorridor.find(road, fuel, StationIndex(stations), FuelGrade.PETROL, FuelServiceFilter.ANY, 8.0, at)

    private fun solve(ledger: FuelStationSearch, now: Instant) = FuelStrategy.entries.mapNotNull { strategy ->
        FuelOptimizer.plan(fuel.last(), road.totalKm, road.totalMin, constraints.copy(strategy = strategy),
            ledger.candidates, now, 1.9)?.let { strategy to it }
    }.toMap()

    private fun simulated(
        clock: () -> Instant = { at },
        access: (FuelAccessWindow, FuelStation) -> PlanApi.FuelAccessResult,
    ) = FuelRoadVerifier(clock) { window, station, begin ->
        if (begin()) access(window, station) else PlanApi.FuelAccessResult.Budget
    }

    private fun access(window: FuelAccessWindow, extraKm: Double, extraMin: Double): PlanApi.FuelAccessResult {
        val km = window.replacedKm + extraKm
        val minutes = window.exit.progress.minutes - window.entry.progress.minutes + extraMin
        return PlanApi.FuelAccessResult.Routed(PlanApi.FuelAccess(km / 2, km / 2, minutes / 2, minutes / 2, "fixture"))
    }

    private fun station(id: String, km: Double, longitudeOffset: Double, price: Double) =
        FuelStation(id, id, "Brand", "", "", "", "", "IT", latitude(km), 12.0 + longitudeOffset, "", "test",
            listOf(FuelOffer(FuelGrade.PETROL, FuelService.SELF, price, "EUR", at)))

    private fun latitude(km: Double) = 40.0 + km / 110.57
}
