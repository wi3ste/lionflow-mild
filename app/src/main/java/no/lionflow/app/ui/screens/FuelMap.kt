package no.lionflow.app.ui.screens

import android.graphics.PointF
import android.util.Log
import no.lionflow.app.BuildConfig
import org.maplibre.android.style.layers.Layer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.lionflow.app.fuel.FuelCatalogState
import no.lionflow.app.fuel.FuelGrade
import no.lionflow.app.fuel.FuelOffer
import no.lionflow.app.fuel.FuelServiceFilter
import no.lionflow.app.fuel.FuelSnapshot
import no.lionflow.app.fuel.FuelStation
import no.lionflow.app.fuel.FuelTripResult
import no.lionflow.app.fuel.MimitFuelProvider
import no.lionflow.app.plan.Geo
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.time.Instant
import java.text.NumberFormat
import java.util.Currency

private const val TAG = "FuelMap"
internal const val STATIONS = "lf-fuel-stations"
internal const val CLUSTERS = "lf-fuel-clusters"
internal const val DOTS = "lf-fuel-dots"
private const val LABELS = "lf-fuel-labels"
private const val LABEL_SOURCE = "lf-fuel-price-source"
private const val STOPS = "lf-fuel-stops"
private const val DETOURS = "lf-fuel-detours"
private val NO_FEATURES = FeatureCollection.fromFeatures(emptyList<Feature>())

internal data class FuelMapData(val stations: Map<String, FuelStation>, val features: FeatureCollection)

internal fun fuelMapData(snapshot: FuelSnapshot?, grade: FuelGrade, service: FuelServiceFilter, now: Instant, locale: java.util.Locale): FuelMapData {
    val rows = snapshot?.index?.stations.orEmpty().mapNotNull { station ->
        val offer = station.offer(grade, service, now)
        if (offer != null || (station.offers.isEmpty() && service == FuelServiceFilter.ANY)) station to offer else null
    }.distinctBy { it.first.id }
    val prices = rows.mapNotNull { it.second?.takeIf { offer -> offer.fresh(now) }?.price }.sorted()
    val threshold = prices.getOrNull(prices.size / 10)
    val formatters = mutableMapOf<String, NumberFormat>()
    return FuelMapData(rows.associate { it.first.id to it.first }, FeatureCollection.fromFeatures(rows.map { (station, offer) ->
        Feature.fromGeometry(Point.fromLngLat(station.lon, station.lat)).apply {
            addStringProperty("id", station.id)
            val label = offer?.price?.let {
                formatters.getOrPut(offer.currency) {
                    NumberFormat.getCurrencyInstance(locale).apply {
                        currency = Currency.getInstance(offer.currency)
                        minimumFractionDigits = 3
                        maximumFractionDigits = 3
                    }
                }.format(it)
            }
            addStringProperty("price", label ?: "—")
            addStringProperty("tone", when {
                offer?.price != null && !offer.fresh(now) -> "old"
                threshold != null && offer?.price?.let { it <= threshold } == true -> "cheap"
                else -> "regular"
            })
        }
    }))
}

internal fun addFuelLayers(style: Style) {
    fun source(id: String, options: GeoJsonOptions? = null) {
        if (style.getSource(id) != null) return
        runCatching { style.addSource(if (options == null) GeoJsonSource(id) else GeoJsonSource(id, options)) }
            .onFailure { Log.e(TAG, "Cannot add source $id", it) }
    }
    fun layer(id: String, create: () -> Layer) {
        if (style.getLayer(id) != null) return
        runCatching { style.addLayer(create()) }
            .onFailure { Log.e(TAG, "Cannot add layer $id", it) }
    }
    source(STATIONS, GeoJsonOptions().withCluster(true).withClusterMaxZoom(11).withClusterRadius(48))
    source(STOPS)
    source(DETOURS)
    source(LABEL_SOURCE)
    val clustered = Expression.has("point_count")
    val individual = Expression.not(clustered)
    layer(DETOURS) { LineLayer(DETOURS, DETOURS).withProperties(
        PropertyFactory.lineColor("#DB7815"), PropertyFactory.lineWidth(4f), PropertyFactory.lineDasharray(arrayOf(1.5f, 1f))) }
    layer(CLUSTERS) { CircleLayer(CLUSTERS, STATIONS).withFilter(clustered).withProperties(
        PropertyFactory.circleColor("#805321"), PropertyFactory.circleRadius(16f), PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(1.5f)) }
    layer(DOTS) { CircleLayer(DOTS, STATIONS).withFilter(individual).withProperties(
        PropertyFactory.circleColor(Expression.switchCase(
            Expression.eq(Expression.get("tone"), Expression.literal("cheap")), Expression.color(0xFF207445.toInt()),
            Expression.eq(Expression.get("tone"), Expression.literal("old")), Expression.color(0xFF706864.toInt()),
            Expression.color(0xFFB76518.toInt()))),
        PropertyFactory.circleRadius(7f), PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.5f)) }
    // An unloaded or unavailable glyph must never hold back the station tile itself.
    layer(LABELS) { SymbolLayer(LABELS, LABEL_SOURCE).withProperties(
        PropertyFactory.textField(Expression.toString(Expression.get("price"))), PropertyFactory.textSize(12f),
        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
        PropertyFactory.textOffset(arrayOf(0f, 1.05f)), PropertyFactory.textColor("#272018"),
        PropertyFactory.textHaloColor("#FFFFFF"), PropertyFactory.textHaloWidth(2f)).apply { minZoom = 12f } }
    layer(STOPS) { CircleLayer(STOPS, STOPS).withProperties(
        PropertyFactory.circleColor("#2F63DC"), PropertyFactory.circleRadius(11f),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f)) }
}

internal fun updateFuelCatalogLayer(style: Style, mapData: FuelMapData?) {
    addFuelLayers(style)
    val source = style.getSourceAs<GeoJsonSource>(STATIONS) ?: return
    val features = mapData?.features ?: NO_FEATURES
    source.setGeoJson(features)
    style.getSourceAs<GeoJsonSource>(LABEL_SOURCE)?.setGeoJson(features)
    if (BuildConfig.DEBUG) Log.d(TAG, "source=$STATIONS features=${mapData?.stations?.size ?: 0} " +
        "layers=${listOf(CLUSTERS, DOTS, LABELS, STOPS).filter { style.getLayer(it) != null }}")
}

internal fun updateFuelPlanLayers(style: Style, result: FuelTripResult?) {
    if (result == null && style.getSource(STOPS) == null) return
    addFuelLayers(style)
    val stops = result?.plan?.stops.orEmpty()
    style.getSourceAs<GeoJsonSource>(STOPS)?.setGeoJson(FeatureCollection.fromFeatures(stops.map { stop ->
        val station = stop.candidate.station
        Feature.fromGeometry(Point.fromLngLat(station.lon, station.lat)).apply {
            addStringProperty("id", station.id)
        }
    }))
    style.getSourceAs<GeoJsonSource>(DETOURS)?.setGeoJson(FeatureCollection.fromFeatures(stops.mapNotNull { stop ->
        val coords = runCatching { Geo.decodePolyline(stop.candidate.shape) }.getOrNull() ?: return@mapNotNull null
        if (coords.first.size < 2) return@mapNotNull null
        Feature.fromGeometry(LineString.fromLngLats(coords.first.indices.map { Point.fromLngLat(coords.second[it], coords.first[it]) }))
    }))
}

internal fun fuelAt(map: MapLibreMap, point: PointF, lookup: (String) -> FuelStation?, show: (FuelStation) -> Unit): Boolean {
    val marker = map.queryRenderedFeatures(point, STOPS, DOTS, LABELS).firstOrNull()?.getStringProperty("id")
    val station = marker?.let(lookup)
    if (station != null) { show(station); return true }
    val cluster = map.queryRenderedFeatures(point, CLUSTERS).firstOrNull() ?: return false
    val coordinates = cluster.geometry() as? Point ?: return false
    val zoom = map.style?.getSourceAs<GeoJsonSource>(STATIONS)?.getClusterExpansionZoom(cluster)?.toDouble()
        ?: (map.cameraPosition.zoom + 2)
    map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(coordinates.latitude(), coordinates.longitude()), zoom))
    return true
}

@Composable
internal fun FuelStationDetails(station: FuelStation, offer: FuelOffer?, now: Instant, close: () -> Unit) {
    val c = LocalLf.current
    val platform = LocalPlatform.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        androidx.compose.material3.Text(stationName(station), style = Type.label, color = c.text)
        androidx.compose.material3.Text(station.location, style = Type.small, color = c.muted)
        val metadata = listOf(station.operator, station.kind).filter(String::isNotBlank).distinct().joinToString(" · ")
        if (metadata.isNotBlank()) androidx.compose.material3.Text(metadata, style = Type.tiny, color = c.muted)
        Text(fuelPrice(offer, now), style = Type.label, color = c.text)
        offer?.service?.let { Text(it.label, style = Type.small, color = c.muted) }
        offer?.reportedAt?.let {
            Text("Price reported", style = Type.tiny, color = c.muted)
            androidx.compose.material3.Text(fuelDate(it), style = Type.tiny, color = c.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Navigate", Modifier.weight(1f)) { platform.openUrl("google.navigation:q=${station.lat},${station.lon}") }
            SecondaryButton("Close", onClick = close)
        }
    }
}

@Composable
internal fun FuelSyncStatus(state: FuelCatalogState, refresh: () -> Unit) {
    val c = LocalLf.current
    val synced = state.snapshot?.syncedAt
    when {
        state.refreshing -> Text("Updating fuel stations…", style = Type.tiny, color = c.muted)
        state.failed -> Text(if (synced == null) "Fuel station update failed. Tap to retry." else "Update failed. Showing cached fuel stations.",
            style = Type.tiny, color = c.danger, modifier = Modifier.clickable(onClick = refresh))
        synced != null && state.snapshot.dataset.stations.isEmpty() -> Text("No fuel stations available", style = Type.tiny, color = c.muted)
    }
    if (synced != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Last fuel sync", style = Type.tiny, color = c.muted)
            androidx.compose.material3.Text(fuelDate(synced), style = Type.tiny, color = c.muted)
        }
    }
}

@Composable
internal fun FuelAttribution() {
    val c = LocalLf.current
    val platform = LocalPlatform.current
    Text("Fuel data: Ministero delle Imprese e del Made in Italy · Osservaprezzi Carburanti · IODL 2.0", style = Type.tiny, color = c.muted,
        modifier = Modifier.clickable { platform.openUrl(MimitFuelProvider.PAGE) }.padding(vertical = 2.dp))
}
