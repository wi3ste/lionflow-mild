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
    }
}
