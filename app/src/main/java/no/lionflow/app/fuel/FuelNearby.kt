package no.lionflow.app.fuel

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.lionflow.app.plan.Geo
import no.lionflow.app.plan.Place
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.cos

internal enum class StationNetwork(val label: String) {
    ANY("All roads"), ROAD("Local roads"), MOTORWAY("Motorway");
    fun accepts(station: FuelStation) = when (this) {
        ANY -> true
        ROAD -> normalized(station.kind) == "stradale"
        MOTORWAY -> normalized(station.kind).startsWith("autostrad")
    }
}

internal enum class NearbySort(val label: String) {
    PRICE("Price"), DISTANCE("Distance"), VALUE("Best value"),
}

internal data class NearbyFilters(
    val grade: FuelGrade,
    val service: FuelServiceFilter,
    val radiusKm: Int = 10,
    val brand: String? = null,
    val network: StationNetwork = StationNetwork.ANY,
    val maxPrice: Double? = null,
    val freshOnly: Boolean = true,
    val favoritesOnly: Boolean = false,
    val sort: NearbySort = NearbySort.VALUE,
    val amount: Double = 30.0,
    val consumptionPer100: Double,
)

internal data class NearbyStation(
    val station: FuelStation,
    val offer: FuelOffer?,
    val straightKm: Double,
    val roundTripKm: Double?,
    val fillCost: Double?,
    val saving: Double?,
)

internal data class NearbyResults(val rows: List<NearbyStation>, val median: Double?, val brands: List<String>)

internal fun nearbyStations(
    index: StationIndex,
    center: Place,
    filters: NearbyFilters,
    favorites: Set<String>,
    now: Instant,
    roundTripDistances: Map<String, Double> = emptyMap(),
): NearbyResults {
    val dLat = filters.radiusKm / 110.0
    val dLon = dLat / cos(Math.toRadians(center.lat)).coerceAtLeast(0.01)
    val stations = index.within(center.lat - dLat, (center.lon - dLon + 540) % 360 - 180,
        center.lat + dLat, (center.lon + dLon + 540) % 360 - 180).mapNotNull { station ->
        val km = Geo.km(center.lat, center.lon, station.lat, station.lon)
        if (km > filters.radiusKm || !filters.network.accepts(station)) return@mapNotNull null
        val offer = station.offer(filters.grade, filters.service, now)
        if (offer == null && (station.offers.isNotEmpty() || filters.service != FuelServiceFilter.ANY)) return@mapNotNull null
        if (filters.freshOnly && (offer?.price == null || !offer.fresh(now))) return@mapNotNull null
        Triple(station, offer, km)
    }.toList()
    val median = FuelQuoteStats.of(stations.mapNotNull { (_, offer, _) -> offer?.price?.takeIf { offer.fresh(now) } })?.median
    val rows = stations.mapNotNull { (station, offer, km) ->
        if (filters.brand != null && station.brand != filters.brand) return@mapNotNull null
        if (filters.favoritesOnly && station.id !in favorites) return@mapNotNull null
        val price = offer?.price
        if (filters.maxPrice != null && (price == null || price > filters.maxPrice)) return@mapNotNull null
        val roundTrip = roundTripDistances[station.id]
        val saving = if (price != null && median != null && offer.fresh(now)) {
            filters.amount * (median - price) - (roundTrip ?: 2 * km) * filters.consumptionPer100 / 100 * median
        } else null
        NearbyStation(station, offer, km, roundTrip, price?.times(filters.amount), saving)
    }
    val order = when (filters.sort) {
        NearbySort.PRICE -> compareBy<NearbyStation> { it.offer?.price ?: Double.POSITIVE_INFINITY }
        NearbySort.DISTANCE -> compareBy { it.straightKm }
        NearbySort.VALUE -> compareByDescending { it.saving ?: Double.NEGATIVE_INFINITY }
    }
    return NearbyResults(rows.sortedWith(order.thenBy { it.straightKm }.thenBy { it.station.id }), median,
        stations.map { it.first.brand }.filter(String::isNotBlank).distinct().sorted())
}

internal data class FavoriteStation(val id: String, val name: String, val brand: String)

internal class FuelFavorites(context: Context) {
    private val prefs = context.getSharedPreferences("fuel_favorites", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(load())
    val stations = current.asStateFlow()

    @Synchronized
    fun toggle(station: FuelStation) {
        val existing = current.value
        write(if (station.id in existing) existing - station.id
            else existing + (station.id to FavoriteStation(station.id, station.title, station.brand)))
    }

    @Synchronized
    fun remove(id: String) = write(current.value - id)

    private fun write(values: Map<String, FavoriteStation>) {
        val array = JSONArray()
        values.values.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("brand", it.brand)) }
        prefs.edit().putString("stations.v1", array.toString()).apply()
        current.value = values
    }

    private fun load(): Map<String, FavoriteStation> = runCatching {
        val array = JSONArray(prefs.getString("stations.v1", "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { row ->
            val id = row.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            FavoriteStation(id, row.optString("name"), row.optString("brand"))
        }.associateBy { it.id }
    }.getOrDefault(emptyMap())
}
