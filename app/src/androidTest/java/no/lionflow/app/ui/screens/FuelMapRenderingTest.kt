package no.lionflow.app.ui.screens

import android.graphics.RectF
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import no.lionflow.app.fuel.*
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.RouteData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.android.maps.Style
import java.time.Instant
import java.util.Locale

class FuelMapRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rendersClustersAndRecreatesMissingMarkerLayer() {
        val position = LatLng(42.0, 12.0)
        val now = Instant.parse("2026-07-29T10:00:00Z")
        val offer = FuelOffer(FuelGrade.PETROL, FuelService.SELF, 1.5, "EUR", now)
        val stations = List(2) { i ->
            FuelStation("id-$i", "Station $i", "", "", "", "", "", "IT", 42.0, 12.0 + i * 0.005,
                "Stradale", "test", listOf(offer))
        }
        val data = fuelMapData(FuelSnapshot(FuelDataset(stations, null), now), FuelGrade.PETROL, FuelServiceFilter.ANY, now, Locale.ITALY)
        var map: MapLibreMap? = null
        var style: Style? = null
        var mapView: MapView? = null

        fun rendered(layer: String): List<Feature> {
            val view = mapView ?: return emptyList()
            return map?.queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), layer).orEmpty()
        }

        fun state(): String {
            val current = style
            return "view=${mapView?.width}x${mapView?.height}, camera=${map?.cameraPosition}, " +
                "layers=${current?.layers?.map { it.id }}, source=${current?.getSourceAs<GeoJsonSource>(STATIONS)?.querySourceFeatures(null)?.size}, " +
                "clusters=${rendered(CLUSTERS).map { it.getNumberProperty("point_count") }}, markers=${rendered(DOTS).map { it.getStringProperty("id") }}"
        }

        fun awaitRender(phase: String, predicate: () -> Boolean) {
            try {
                compose.waitUntil(25_000) { compose.runOnUiThread { predicate() } }
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("$phase: ${compose.runOnUiThread { state() }}", e)
            }
        }

        compose.setContent {
            val context = LocalContext.current
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val view = remember {
                MapLibre.getInstance(context)
                MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) }
            }
            AndroidView({ view }, Modifier.fillMaxSize())
            DisposableEffect(view, lifecycle) {
                val events = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> view.onStart()
                        Lifecycle.Event.ON_RESUME -> view.onResume()
                        Lifecycle.Event.ON_PAUSE -> view.onPause()
                        Lifecycle.Event.ON_STOP -> view.onStop()
                        else -> Unit
                    }
                }
                mapView = view
                view.addOnDidFailLoadingMapListener { Log.e("FuelMapTest", "Style loading failed: $it") }
                lifecycle.addObserver(events)
                view.getMapAsync { instance ->
                    instance.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#ddd"}}]}""")) { loaded ->
                        updateFuelCatalogLayer(loaded, data)
                        instance.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 7.0))
                        map = instance
                        style = loaded
                    }
                }
                onDispose {
                    lifecycle.removeObserver(events)
                    view.onPause()
                    view.onStop()
                    view.onDestroy()
                }
            }
        }

        awaitRender("style callback") { style != null && mapView?.width?.let { it > 0 } == true }
        awaitRender("cluster at zoom 7") { rendered(CLUSTERS).any { it.getNumberProperty("point_count").toInt() == 2 } }
        compose.runOnUiThread {
            val loaded = checkNotNull(style)
            loaded.removeLayer(DOTS)
            updateFuelCatalogLayer(loaded, data)
            checkNotNull(map).moveCamera(CameraUpdateFactory.newLatLngZoom(position, 15.0))
        }
        awaitRender("restored station marker at zoom 15") { rendered(DOTS).any { it.getStringProperty("id") == "id-0" } }
        val from = Place("From", "", 42.0, 12.0)
        val to = Place("To", "", 42.0, 12.005)
        val road = RouteData(doubleArrayOf(from.lat, to.lat), doubleArrayOf(from.lon, to.lon),
            doubleArrayOf(0.0, 0.4), doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 24.0))
        val prediction = FuelConsumption.estimate(FuelGrade.PETROL, FuelProfile.MILD, emptyList(), 5.0, 5.0)
        val constraints = FuelConstraints(40.0, 10.0, 3.0, FuelStrategy.BALANCED)
        val request = FuelTripRequest(from, to, emptyList(), false, now, FuelGrade.PETROL, FuelServiceFilter.ANY,
            FuelProfile.MILD, 5.0, 5.0, constraints, 5.0)
        fun option(index: Int): FuelPlan {
            val projection = FuelProjection(stations[index], offer, if (index == 0) from else to, 90,
                index * 0.4, index.toDouble(), 0.01, 0.0)
            return FuelPlan(0.02, 13.0, listOf(FuelStop(FuelCandidate(projection, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                10.0, 3.0, 0.0)), 1.0, 0.4, 0.03)
        }
        val first = option(0)
        val second = option(1)
        val result = FuelTripResult(request, road, 0.02, emptyList(), 1.5, now, true, first, prediction,
            FuelSearchSummary(2, 2, 2, 1.5 to 1.5, 2, false), mapOf(FuelStrategy.BALANCED to first, FuelStrategy.FAST to second))
        compose.runOnUiThread {
            val loaded = checkNotNull(style)
            assertNotNull(loaded.getLayer(COUNTS))
            updateFuelCatalogLayer(loaded, data.takeIf { fuelCatalogVisible(enabled = true, hasPlan = true) })
            updateFuelPlanLayers(loaded, result)
        }
        awaitRender("plan isolates the selected station") {
            rendered(DOTS).isEmpty() && rendered(CLUSTERS).isEmpty() && rendered(WAYPOINTS).isNotEmpty() &&
                rendered(STOPS).map { it.getStringProperty("id") } == listOf("id-0")
        }
        compose.runOnUiThread {
            val fast = result.withStrategy(FuelStrategy.FAST)
            assertEquals(FuelStrategy.FAST, fast.request.constraints.strategy)
            updateFuelPlanLayers(checkNotNull(style), fast)
        }
        awaitRender("strategy updates the inspected stop") { rendered(STOPS).map { it.getStringProperty("id") } == listOf("id-1") }
        compose.runOnUiThread {
            val loaded = checkNotNull(style)
            updateFuelPlanLayers(loaded, null)
            updateFuelCatalogLayer(loaded, data.takeIf { fuelCatalogVisible(enabled = true, hasPlan = false) })
        }
        awaitRender("closing restores the catalogue") { rendered(DOTS).isNotEmpty() && rendered(STOPS).isEmpty() }
    }
}
