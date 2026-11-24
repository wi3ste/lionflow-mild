package no.lionflow.app.ui.screens

import android.graphics.PointF
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
import no.lionflow.app.ui.l10n.L10n

private const val STATIONS = "lf-fuel-stations"
private const val CLUSTERS = "lf-fuel-clusters"
private const val DOTS = "lf-fuel-dots"
private const val LABELS = "lf-fuel-labels"
private const val STOPS = "lf-fuel-stops"
private const val DETOURS = "lf-fuel-detours"
private val NO_FEATURES = FeatureCollection.fromFeatures(emptyList<Feature>())

internal data class FuelMapData(val stations: Map<String, FuelStation>, val geoJson: String)

internal fun fuelMapData(snapshot: FuelSnapshot?, grade: FuelGrade, service: FuelServiceFilter, now: Instant): FuelMapData {
    val rows = snapshot?.index?.stations.orEmpty().mapNotNull { station ->
        val offer = station.offer(grade, service, now)
        if (offer != null || (station.offers.isEmpty() && service == FuelServiceFilter.ANY)) station to offer else null
    }
    val prices = rows.mapNotNull { it.second?.takeIf { offer -> offer.fresh(now) }?.price }.sorted()
    val threshold = prices.getOrNull(prices.size / 10)
    val formatters = mutableMapOf<String, NumberFormat>()
    return FuelMapData(rows.associate { it.first.id to it.first }, FeatureCollection.fromFeatures(rows.map { (station, offer) ->
        Feature.fromGeometry(Point.fromLngLat(station.lon, station.lat)).apply {
            addStringProperty("id", station.id)
            val label = offer?.price?.let {
                formatters.getOrPut(offer.currency) {
                    NumberFormat.getCurrencyInstance(L10n.locale).apply {
                        currency = Currency.getInstance(offer.currency)
                        minimumFractionDigits = 3
                        maximumFractionDigits = 3
                    }
                }.format(it)
            }
            addStringProperty("price", label ?: "—")
            addBooleanProperty("cheap", threshold != null && offer?.fresh(now) == true && offer.price?.let { it <= threshold } == true)
            addBooleanProperty("old", offer?.price != null && !offer.fresh(now))
        }
    }).toJson())
}

internal fun addFuelLayers(style: Style) {
    if (style.getSource(STATIONS) != null) return
    style.addSource(GeoJsonSource(STATIONS, NO_FEATURES, GeoJsonOptions().withCluster(true).withClusterMaxZoom(12).withClusterRadius(48)))
    style.addSource(GeoJsonSource(STOPS, NO_FEATURES))
    style.addSource(GeoJsonSource(DETOURS, NO_FEATURES))
    style.addLayer(LineLayer(DETOURS, DETOURS).withProperties(
        PropertyFactory.lineColor("#DB7815"), PropertyFactory.lineWidth(4f), PropertyFactory.lineDasharray(arrayOf(1.5f, 1f))))
    style.addLayer(CircleLayer(CLUSTERS, STATIONS).withFilter(Expression.has("point_count")).withProperties(
        PropertyFactory.circleColor("#805321"), PropertyFactory.circleRadius(16f), PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(1.5f)))
    style.addLayer(SymbolLayer("lf-fuel-counts", STATIONS).withFilter(Expression.has("point_count")).withProperties(
        PropertyFactory.textField(Expression.toString(Expression.get("point_count_abbreviated"))),
        PropertyFactory.textColor("#FFFFFF"), PropertyFactory.textSize(12f), PropertyFactory.textAllowOverlap(true)))
    style.addLayer(CircleLayer(DOTS, STATIONS).withFilter(Expression.not(Expression.has("point_count"))).withProperties(
        PropertyFactory.circleColor(Expression.switchCase(
            Expression.get("cheap"), Expression.color(0xFF207445.toInt()),
            Expression.get("old"), Expression.color(0xFF706864.toInt()),
            Expression.color(0xFFB76518.toInt()))),
        PropertyFactory.circleRadius(6f), PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.3f)))
    style.addLayer(SymbolLayer(LABELS, STATIONS).withFilter(Expression.not(Expression.has("point_count"))).withProperties(
        PropertyFactory.textField(Expression.get("price")), PropertyFactory.textSize(12f),
        PropertyFactory.textOffset(arrayOf(0f, 1.05f)), PropertyFactory.textColor("#272018"),
        PropertyFactory.textHaloColor("#FFFFFF"), PropertyFactory.textHaloWidth(2f)).apply { minZoom = 12.5f })
    style.addLayer(SymbolLayer(STOPS, STOPS).withProperties(
        PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
}

internal fun updateFuelCatalogLayer(style: Style, mapData: FuelMapData?) {
    addFuelLayers(style)
    val source = style.getSourceAs<GeoJsonSource>(STATIONS) ?: return
    if (mapData == null) source.setGeoJson(NO_FEATURES) else source.setGeoJson(mapData.geoJson)
}

internal fun updateFuelPlanLayers(style: Style, result: FuelTripResult?) {
    if (result == null && style.getSource(STOPS) == null) return
    addFuelLayers(style)
    val stops = result?.plan?.stops.orEmpty()
    style.getSourceAs<GeoJsonSource>(STOPS)?.setGeoJson(FeatureCollection.fromFeatures(stops.mapIndexed { i, stop ->
        val station = stop.candidate.station
        Feature.fromGeometry(Point.fromLngLat(station.lon, station.lat)).apply {
            addStringProperty("id", station.id)
            addStringProperty("icon", "lf-stop-${(i + 1).takeIf { it <= 12 } ?: 0}")
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
