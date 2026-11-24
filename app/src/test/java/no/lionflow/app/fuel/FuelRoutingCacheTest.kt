package no.lionflow.app.fuel

import kotlinx.coroutines.runBlocking
import no.lionflow.app.plan.Ferry
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.PlanApi
import no.lionflow.app.plan.RouteData
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class FuelRoutingCacheTest {
    @get:Rule val files = TemporaryFolder()
    private val time = Instant.parse("2026-11-24T00:00:00Z")
    private val a = Place("A", "", 43.0, 12.0)
    private val b = Place("B", "", 43.1, 12.0)

    @Test fun routeSurvivesRestartAndInvalidatesExpiredAndCorruptEntries() {
        val dir = files.newFolder()
        val key = FuelRoutingCache.routeKey(listOf(a, b))
        val route = RouteData(doubleArrayOf(a.lat, b.lat), doubleArrayOf(a.lon, b.lon),
            doubleArrayOf(0.0, 12.0), doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 72.0)).apply {
            ferries = listOf(Ferry("Ferry", a.lat, a.lon, b.lat, b.lon, 10.0, 0, 1))
        }
        assertNull(FuelRoutingCache(dir).route(key, time))
        FuelRoutingCache(dir).saveRoute(key, route, time)
        assertEquals(key, FuelRoutingCache.routeKey(listOf(a.copy(name = "Renamed"), b)))
        assertNull(FuelRoutingCache(dir).route(FuelRoutingCache.routeKey(listOf(b, a)), time))
        val restored = requireNotNull(FuelRoutingCache(dir).route(key, time.plusSeconds(1)))
        assertArrayEquals(route.km, restored.km, 0.0)
        assertEquals(route.ferries, restored.ferries)
        dir.walk().first { it.extension == "gz" }.writeText("corrupt")
        assertNull(FuelRoutingCache(dir).route(key, time))
        FuelRoutingCache(dir).saveRoute(key, route, time)
        assertNull(FuelRoutingCache(dir).route(key, time.plusSeconds(6 * 3600)))
    }

    @Test fun accessRetriesTemporaryFailuresAndExpiresAuthoritativeNegativesSooner() {
        val dir = files.newFolder()
        val cache = FuelRoutingCache(dir)
        cache.saveAccess("negative", PlanApi.FuelAccessResult.NoRoute, time)
        cache.saveAccess("timeout", PlanApi.FuelAccessResult.Unavailable(true), time)
        val routed = PlanApi.FuelAccessResult.Routed(PlanApi.FuelAccess(1.2, 2.0, 3.0, 4.0, "????"))
        cache.saveAccess("positive", routed, time)
        val reopened = FuelRoutingCache(dir)
        assertEquals(PlanApi.FuelAccessResult.NoRoute, reopened.access("negative", time.plusSeconds(1)))
        assertNull(reopened.access("timeout", time.plusSeconds(1)))
        assertNull(reopened.access("negative", time.plusSeconds(3 * 3600)))
        assertEquals(routed, reopened.access("positive", time.plusSeconds(3 * 3600)))
        assertNull(reopened.access("positive", time.plusSeconds(24 * 3600)))
    }

    @Test fun cacheCutsNetworkProbesAfterRestartAndOnAnOverlappingDirectedSegment() = runBlocking {
        val dir = files.newFolder()
        val offer = FuelOffer(FuelGrade.PETROL, FuelService.SELF, 1.7, "EUR", time)
        val station = FuelStation("mimit:1", "Station", "", "", "", "", "", "IT",
            43.05, 12.0, "Stradale", "MIMIT", listOf(offer))
        val projection = FuelProjection(station, offer, a, 0, 12.0, 10.0, 1.0, 0.0)
        val window = FuelAccessWindow(FuelAccessPoint(a, 0, FuelRejoin(12.0, 10.0, 1.0)),
            FuelAccessPoint(b, 0, FuelRejoin(24.0, 20.0, 2.0)))
        val prediction = FuelConsumption.estimate(FuelGrade.PETROL, FuelProfile.MILD, emptyList(), 7.0, 0.0)
        fun verifier(cache: FuelRoutingCache) = FuelRoadVerifier(clock = { time }, disk = cache,
            route = { _, _, begin ->
                check(begin())
                PlanApi.FuelAccessResult.Routed(PlanApi.FuelAccess(6.0, 6.5, 5.0, 6.0, "????"))
            })
        val first = verifier(FuelRoutingCache(dir))
        suspend fun probe(v: FuelRoadVerifier, w: FuelAccessWindow): Pair<Int, Int> {
            val budget = FuelProbeBudget(2)
            assertTrue(v.verify(projection, listOf(w), prediction, 8.0, budget) is FuelCheck.Verified)
            return budget.requests to budget.hits
        }
        assertEquals(1 to 0, probe(first, window))
        assertEquals(0 to 1, probe(first, window))
        val restarted = verifier(FuelRoutingCache(dir))
        assertEquals(0 to 1, probe(restarted, window))
        val overlap = window.copy(entry = window.entry.copy(progress = FuelRejoin(2.0, 1.0, 0.2)),
            exit = window.exit.copy(progress = FuelRejoin(14.0, 11.0, 1.2)))
        assertEquals(0 to 1, probe(restarted, overlap))
        assertEquals(1 to 0, probe(restarted, overlap.copy(exit = overlap.exit.copy(bearing = 180))))
    }
}
