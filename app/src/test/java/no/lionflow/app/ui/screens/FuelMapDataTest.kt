package no.lionflow.app.ui.screens

import no.lionflow.app.fuel.*
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.time.Instant
import java.util.Locale

class FuelMapDataTest {
    @Test fun catalogIsHiddenByPlanAndRestoredOnClosing() {
        assertTrue(fuelCatalogVisible(true, false))
        assertFalse(fuelCatalogVisible(true, true))
        assertFalse(fuelCatalogVisible(false, false))
        assertTrue(fuelCatalogVisible(true, false))
    }

    @Test fun collectionRoundTripsCoordinatesIdsPricesAndMarkerColors() {
        val now = Instant.parse("2026-07-29T10:00:00Z")
        fun station(id: String, offer: FuelOffer?) = FuelStation(
            id, id, "", "", "", "", "", "IT", 42.0, 12.0, "Stradale", "test", listOfNotNull(offer),
        )
        val cheap = station("cheap", FuelOffer(FuelGrade.PETROL, FuelService.SELF, 1.234, "EUR", now))
        val old = station("old", cheap.offers.single().copy(price = 1.01, reportedAt = now.minusSeconds(86400 * 10L)))
        val unknown = station("unknown", null)
        val lpg = station("lpg", cheap.offers.single().copy(grade = FuelGrade.LPG))
        val snapshot = FuelSnapshot(FuelDataset(listOf(cheap, old, unknown, lpg, cheap), null), now)
        val data = fuelMapData(snapshot, FuelGrade.PETROL, FuelServiceFilter.ANY, now, Locale.ITALY)
        val features = FeatureCollection.fromJson(data.features.toJson()).features()!!
        assertEquals(data.stations.keys, features.map { it.getStringProperty("id") }.toSet())
        assertEquals(3, features.size)
        assertTrue(features.all { it.geometry() == Point.fromLngLat(12.0, 42.0) && !it.hasProperty("point_count") })
        assertEquals(listOf("cheap", "old", "regular"), features.map { it.getStringProperty("tone") })
        assertTrue(features.first().getStringProperty("price").contains("1,234"))
        assertEquals("—", features.last().getStringProperty("price"))
        assertEquals(2, fuelMapData(snapshot, FuelGrade.PETROL, FuelServiceFilter.SELF, now, Locale.ITALY).stations.size)
    }
}
