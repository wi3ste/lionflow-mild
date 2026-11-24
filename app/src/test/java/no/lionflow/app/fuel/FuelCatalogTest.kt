package no.lionflow.app.fuel

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import no.lionflow.app.plan.PlanApi
import no.lionflow.app.plan.RouteData
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class FuelCatalogTest {
    @get:Rule val directory = TemporaryFolder()
    private val now = Instant.parse("2026-07-29T10:00:00Z")

    @Test fun joinsByIdAndNormalizesActualColumns() {
        val data = fixture()
        assertEquals(LocalDate.of(2026, 7, 29), data.priceDate)
        assertEquals(listOf("mimit:17", "mimit:18"), data.stations.map { it.id })
        val station = data.stations.first()
        assertEquals("Stazione | Centro", station.title)
        assertEquals("Società Srl", station.operator)
        assertEquals("Via Roma 1, Città, RM", station.location)
        assertEquals("IT", station.country)
        assertEquals("MIMIT", station.source)
        val petrol = station.offer(FuelGrade.PETROL, FuelServiceFilter.SELF, now)!!
        assertEquals(1.789, petrol.price!!, 0.0)
        assertEquals("EUR", petrol.currency)
        assertEquals(Instant.parse("2026-07-28T10:30:11Z"), petrol.reportedAt)
        assertEquals(1.829, station.offer(FuelGrade.PETROL, FuelServiceFilter.SERVED, now)!!.price!!, 0.0)
        assertEquals("L", station.offer(FuelGrade.DIESEL, FuelServiceFilter.SELF, now)!!.grade.unit)
        assertEquals("kg", station.offer(FuelGrade.CNG, FuelServiceFilter.SERVED, now)!!.grade.unit)
        val invalid = station.offer(FuelGrade.LPG, FuelServiceFilter.ANY, now)!!
        assertNull(invalid.price)
        assertEquals(FuelService.SERVED, invalid.service)
        assertEquals(5, station.offers.size)
        assertTrue(data.stations.last().offers.isEmpty())
    }

    @Test fun supportsLegacyDelimiterEncodingAndBom() {
        val registry = String(resource("registry.csv"), Charsets.UTF_8)
            .replace("\"Stazione | Centro\"", "Stazione Centro").replace('|', ';')
        val data = MimitFuelProvider.parse(registry.toByteArray(charset("windows-1252")),
            ("\uFEFF" + String(resource("prices.csv"), Charsets.UTF_8).replace('|', ';')).toByteArray(Charsets.UTF_16LE))
        assertEquals("Società Srl", data.stations[0].operator)
        assertEquals("Stazione Centro", data.stations[0].name)
        assertEquals(1.789, data.stations[0].offer(FuelGrade.PETROL, FuelServiceFilter.ANY, now)!!.price!!, 0.0)
    }

    @Test fun rejectsUnexpectedHeaders() {
        assertThrows(IOException::class.java) { MimitFuelProvider.parse(resource("registry.csv"), "<html>oops</html>".toByteArray()) }
    }

    @Test fun standardGradesKeepUnitsAndRejectPremiumOrLiquefiedMethane() {
        assertEquals(FuelGrade.PETROL, FuelGrade.from(" Benzina senza piombo "))
        assertEquals(FuelGrade.CNG, FuelGrade.from("Metano gassoso"))
        assertEquals(FuelGrade.LPG, FuelGrade.from("Autogas"))
        assertNull(FuelGrade.from("Gasolio speciale"))
        assertNull(FuelGrade.from("Metano liquido"))
    }

    @Test fun freshPriceAndModeAreActuallySelected() {
        val station = fixture().stations.first()
        val old = station.offers.first { it.grade == FuelGrade.PETROL && it.service == FuelService.SELF }
            .copy(price = 1.0, reportedAt = now.minusSeconds(8 * 86400L))
        val withOld = station.copy(offers = station.offers.filterNot { it.grade == FuelGrade.PETROL && it.service == FuelService.SELF } + old)
        assertEquals(FuelService.SERVED, withOld.offer(FuelGrade.PETROL, FuelServiceFilter.ANY, now)?.service)
        assertFalse(old.fresh(now))
        assertFalse(old.copy(reportedAt = now.plusSeconds(7200)).fresh(now))
    }

    @Test fun concurrentLookupsCacheAcrossInstancesAndKeepLastValidOnFailure() = runBlocking {
        val cache = FuelCache(directory.newFolder().resolve("mimit.gz"))
        val data = fixture()
        var tick = now
        var calls = 0
        var response: FuelDataset? = data
        val provider = object : FuelStationProvider {
            override val source = "test"
            override val countries = setOf("IT")
            override suspend fun load(): FuelDataset {
                calls++
                return response ?: throw IOException("offline")
            }
        }
        val catalog = FuelCatalog(provider, cache, { tick })
        val first = async { catalog.refresh() }
        val second = async { catalog.refresh() }
        assertEquals(data, first.await().snapshot!!.dataset)
        assertEquals(data, second.await().snapshot!!.dataset)
        assertEquals(1, calls)
        assertEquals(data, FuelCatalog(provider, cache, { tick }).refresh().snapshot!!.dataset)
        assertEquals(1, calls)
        tick = tick.plusSeconds(90001)
        response = null
        val offline = catalog.refresh()
        assertTrue(offline.failed)
        assertEquals(now, offline.snapshot!!.syncedAt)
        assertEquals(data, cache.read()!!.dataset)
        response = FuelDataset(data.stations, data.priceDate!!.minusDays(1))
        assertTrue(catalog.refresh(force = true).failed)
        assertEquals(data, cache.read()!!.dataset)
        response = data.copy(stations = data.stations.map { it.copy(offers = emptyList()) })
        assertTrue(catalog.refresh(force = true).failed)
        assertEquals(data, cache.read()!!.dataset)
    }

    @Test fun failedAtomicWriteLeavesThePreviousSnapshot() {
        val cache = FuelCache(directory.newFolder().resolve("mimit.gz"))
        val data = fixture()
        cache.write(FuelSnapshot(data, now))
        val broken = data.copy(stations = listOf(data.stations[0].copy(name = "a".repeat(70_000))))
        assertThrows(IOException::class.java) { cache.write(FuelSnapshot(broken, now.plusSeconds(60))) }
        assertEquals(data, cache.read()!!.dataset)
    }

    @Test fun cachedCatalogCanProduceAPlanUsingOnlyRoadVerifiedDetours() {
        val catalog = fixture()
        val cache = FuelCache(directory.newFolder().resolve("mimit.gz"))
        cache.write(FuelSnapshot(catalog, now))
        val snapshot = cache.read()!!
        val model = FuelConsumption.estimate(FuelGrade.PETROL, FuelProfile.COMBUSTION, emptyList(), 10.0, 0.0)
        val road = RouteData(doubleArrayOf(41.9, 41.9, 41.9), doubleArrayOf(12.40, 12.49, 12.65),
            doubleArrayOf(0.0, 50.0, 140.0), doubleArrayOf(0.0, 35.0, 100.0), doubleArrayOf(0.0, 85.0, 85.0))
        val roadFuel = model.forRoute(road)
        val station = FuelCorridor.find(road, roadFuel, snapshot.index, FuelGrade.PETROL, FuelServiceFilter.SELF, 1.0, now).single()
        assertNull(FuelCandidate.verified(station, PlanApi.FuelAccess(5.0, 7.0, 10.0, 8.0, ""), 4.0, model))
        val checked = FuelCandidate.verified(station, PlanApi.FuelAccess(0.2, 0.2, 1.0, 1.0, ""), 4.0, model)!!
        val plan = FuelOptimizer.plan(roadFuel.last(), road.totalKm, road.totalMin,
            FuelConstraints(15.0, 7.0, 1.0, FuelStrategy.BALANCED), listOf(checked), now, 1.8)!!
        assertEquals("mimit:17", plan.stops.single().candidate.station.id)
        assertEquals(8.04, plan.purchased, 1e-8)
        assertEquals(1.0, plan.remaining, 1e-8)
    }

    private fun fixture() = MimitFuelProvider.parse(resource("registry.csv"), resource("prices.csv"))
    private fun resource(file: String) = requireNotNull(javaClass.getResourceAsStream("/fuel/$file")).use { it.readBytes() }
}
