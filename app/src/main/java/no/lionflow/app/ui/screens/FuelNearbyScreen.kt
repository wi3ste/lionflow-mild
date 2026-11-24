package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.lionflow.app.fuel.*
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.Planner
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.time.Instant

private enum class NearbyOrigin(val label: String) {
    PHONE("My position"), CAR("Car's position"), MAP("Map center"), ADDRESS("Address");
}

@Composable
internal fun FuelNearbyScreen(
    planner: FuelPlanner,
    addresses: Planner,
    ui: UiState,
    mapCenter: Place?,
    consumption: Double,
    now: Instant,
    close: () -> Unit,
    showOnMap: (FuelStation, FuelGrade, FuelServiceFilter) -> Unit,
) {
    val c = LocalLf.current
    val platform = LocalPlatform.current
    val catalog by planner.catalog.state.collectAsStateWithLifecycle()
    val favorites by planner.favorites.stations.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf(if (ui.myLat != null && ui.myLon != null) NearbyOrigin.PHONE else NearbyOrigin.CAR) }
    var address by remember { mutableStateOf<Place?>(null) }
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(NearbyFilters(planner.grade, planner.service, consumptionPer100 = consumption)) }
    var advanced by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("30") }
    var maximum by remember { mutableStateOf("") }
    var rate by remember(consumption) { mutableStateOf(formatFuelNumber(consumption, 1)) }
    val center = when (origin) {
        NearbyOrigin.PHONE -> ui.myLat?.let { lat -> ui.myLon?.let { Place(origin.label, "", lat, it) } }
        NearbyOrigin.CAR -> ui.status?.let { status -> status.lat?.let { lat -> status.lon?.let { Place(origin.label, "", lat, it) } } }
        NearbyOrigin.MAP -> mapCenter
        NearbyOrigin.ADDRESS -> address
    }
    val currentCenter by rememberUpdatedState(center)
    var distances by remember(center) { mutableStateOf(emptyMap<String, Double>()) }
    var activeCheck by remember(center) { mutableStateOf<String?>(null) }
    var failedCheck by remember(center) { mutableStateOf<String?>(null) }
    val snapshot = catalog.snapshot
    val results by produceState<NearbyResults?>(null, snapshot, center, filters, favorites, now, distances) {
        value = if (snapshot == null || center == null) null else withContext(Dispatchers.Default) {
            nearbyStations(snapshot.index, center, filters, favorites.keys, now, distances)
        }
    }
    DisposableEffect(Unit) {
        addresses.clearSearch()
        onDispose { addresses.clearSearch() }
    }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
        PlanHeader(back = true, title = "Find fuel nearby", onClose = close)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FuelChoice(NearbyOrigin.entries, origin, NearbyOrigin::label) { source ->
                        origin = source
                        if (source == NearbyOrigin.PHONE && ui.myLat == null) platform.requestLocation()
                    }
                    FuelChoice(listOf(2, 5, 10, 20, 50), filters.radiusKm, { "$it km" }) { filters = filters.copy(radiusKm = it, brand = null) }
                    FuelChoice(NearbySort.entries, filters.sort, NearbySort::label) { filters = filters.copy(sort = it) }
                }
                if (origin == NearbyOrigin.ADDRESS) {
                    OutlinedTextField(query, { input ->
                        query = input
                        address = null
                        addresses.search(input)
                    }, label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (address == null && query.trim().length >= 2) {
                        addresses.results.forEach { place ->
                            TextButton(onClick = { address = place; query = place.name; addresses.clearSearch() }) {
                                androidx.compose.material3.Text(place.name + " · " + place.detail, color = c.text)
                            }
                        }
                        if (addresses.results.isEmpty()) Text(if (addresses.searching) "Searching…" else "No places found", color = c.muted)
                    }
                }
                if (origin == NearbyOrigin.CAR && center != null) {
                    ui.status?.updatedAtMillis?.let { stamp ->
                        androidx.compose.material3.Text(fuelDate(Instant.ofEpochMilli(stamp)), color = c.muted, style = Type.small)
                    }
                }
                if (center == null && origin != NearbyOrigin.ADDRESS) Text("Position unavailable", style = Type.small, color = c.muted)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FuelChoice(FuelGrade.entries, filters.grade, FuelGrade::label) {
                        filters = filters.copy(grade = it, brand = null)
                    }
                    FuelChoice(FuelServiceFilter.entries, filters.service, FuelServiceFilter::label) { filters = filters.copy(service = it, brand = null) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { advanced = !advanced }) { Text("More filters", color = c.accentInk) }
                    TextButton(onClick = { saved = !saved }) { Text(if (saved) "Nearby stations" else "Favorites", color = c.accentInk) }
                }
                OutlinedTextField(amount, { amount = it; positive(it, 500.0)?.let { value -> filters = filters.copy(amount = value) } },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = positive(amount, 500.0) == null, label = { Text("Quantity to compare") }, suffix = { Text(filters.grade.unit) })
                if (advanced) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FuelChoice(StationNetwork.entries, filters.network, StationNetwork::label) { filters = filters.copy(network = it, brand = null) }
                        val brands = listOf<String?>(null) + (results?.brands.orEmpty() + listOfNotNull(filters.brand)).distinct()
                        FuelChoice(brands, filters.brand, { it ?: "All brands" }) { filters = filters.copy(brand = it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(maximum, { maximum = it; filters = filters.copy(maxPrice = positive(it, 20.0)) }, singleLine = true,
                            label = { Text("Maximum unit price") }, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = maximum.isNotBlank() && positive(maximum, 20.0) == null)
                        OutlinedTextField(rate, { rate = it; positive(it, 100.0)?.let { value -> filters = filters.copy(consumptionPer100 = value) } },
                            singleLine = true, label = { Text("Consumption / 100 km") }, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = positive(rate, 100.0) == null)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip("Recent prices only", filters.freshOnly) { filters = filters.copy(freshOnly = !filters.freshOnly, brand = null) }
                        FilterChip("Favorites only", filters.favoritesOnly) { filters = filters.copy(favoritesOnly = !filters.favoritesOnly) }
                    }
                }
                if (!saved) {
                    FuelSyncStatus(catalog) { planner.refresh(force = true) }
                    results?.median?.let {
                        Text("Savings use the median fresh price in this area and subtract the estimated round-trip fuel cost.",
                            style = Type.small, color = c.muted)
                        androidx.compose.material3.Text("€${formatFuelNumber(it, 3)}/${filters.grade.unit}", style = Type.small, color = c.text)
                    }
                }
            }
            if (saved) {
                val indexed = snapshot?.dataset?.stations.orEmpty().associateBy { it.id }
                if (favorites.isEmpty()) item { Text("No favorite stations", color = c.muted) }
                items(favorites.values.toList(), key = { it.id }) { favorite ->
                    LfCard {
                        androidx.compose.material3.Text(favorite.name, style = Type.label, color = c.text)
                        val station = indexed[favorite.id]
                        if (station == null) Text("Unavailable in the current catalog", color = c.muted, style = Type.small)
                        else androidx.compose.material3.Text(fuelPrice(station.offer(filters.grade, filters.service, now), now), color = c.text, style = Type.small)
                        Row {
                            if (station != null) TextButton(onClick = { showOnMap(station, filters.grade, filters.service) }) { Text("Show on map", color = c.accentInk) }
                            TextButton(onClick = { planner.favorites.remove(favorite.id) }) { Text("Remove favorite", color = c.accentInk) }
                        }
                    }
                }
            } else {
                val rows = results?.rows.orEmpty()
                if (results != null && rows.isEmpty()) item { Text("No stations match these filters", color = c.muted) }
                val top = rows.take(5).map { it.station.id }.toSet()
                items(rows, key = { it.station.id }) { row ->
                    val station = row.station
                    LfCard {
                        androidx.compose.material3.Text(stationName(station), style = Type.label, color = c.text)
                        androidx.compose.material3.Text(fuelPrice(row.offer, now), style = Type.body, color = c.text)
                        row.offer?.service?.let { Text(it.label, style = Type.small, color = c.muted) }
                        row.offer?.reportedAt?.let { androidx.compose.material3.Text(fuelDate(it), style = Type.small, color = c.muted) }
                        Text("${formatFuelNumber(row.straightKm, 1)} km straight-line", style = Type.small, color = c.muted)
                        row.roundTripKm?.let { Text("${formatFuelNumber(it, 1)} km by road, round trip", style = Type.small, color = c.muted) }
                        row.fillCost?.let { Text("Estimated fill €${formatFuelNumber(it, 2)}", style = Type.small, color = c.text) }
                        row.saving?.let { Text("Estimated saving €${formatFuelNumber(it, 2)}", style = Type.small, color = c.text) }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { showOnMap(station, filters.grade, filters.service) }) { Text("Show on map", color = c.accentInk) }
                            TextButton(onClick = { platform.openUrl(station.navigationUrl()) }) { Text("Navigate", color = c.accentInk) }
                            TextButton(onClick = { planner.favorites.toggle(station) }) {
                                Text(if (station.id in favorites) "Remove favorite" else "Add favorite", color = c.accentInk)
                            }
                        }
                        if (center != null && row.roundTripKm == null && station.id in top) {
                            TextButton(enabled = activeCheck == null, onClick = {
                                activeCheck = station.id
                                failedCheck = null
                                scope.launch {
                                    try {
                                        val distance = planner.stationRoundTrip(center, station)
                                        if (center == currentCenter) distances = distances + (station.id to distance)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        if (center == currentCenter) failedCheck = station.id
                                    } finally {
                                        if (center == currentCenter) activeCheck = null
                                    }
                                }
                            }) { Text(if (activeCheck == station.id) "Checking road distance…" else "Check road distance", color = c.accentInk) }
                        }
                        if (failedCheck == station.id) Text("Road distance unavailable", style = Type.small, color = c.danger)
                    }
                }
            }
        }
    }
}

internal fun FuelStation.navigationUrl() = "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=driving"

internal fun formatFuelNumber(value: Double, decimals: Int) = String.format(L10n.locale, "%.${decimals}f", value)

private fun positive(text: String, max: Double) = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= max }
