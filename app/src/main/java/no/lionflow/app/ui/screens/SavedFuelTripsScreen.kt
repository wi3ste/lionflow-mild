package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.lionflow.app.fuel.*
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.time.Instant
import java.util.UUID

@Composable
internal fun SavedFuelTripsScreen(
    planner: FuelPlanner, vin: String, ui: UiState, refreshStatus: () -> Unit, loadTrips: () -> Unit,
    close: () -> Unit, edit: (SavedFuelTrip, Boolean) -> Unit,
) {
    val c = LocalLf.current
    val platform = LocalPlatform.current
    val change by planner.savedTrips.changes.collectAsStateWithLifecycle()
    val plans = remember(vin, change) { planner.savedTrips.list(vin) }
    val active = remember(vin, change) { planner.savedTrips.active(vin) }
    val logsChanged by planner.log.changes.collectAsStateWithLifecycle()
    val events = remember(vin, logsChanged) { planner.log.entries(vin) }
    var details by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SavedFuelTrip?>(null) }
    var ending by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(active != null) }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.systemBars)) {
        PlanHeader(back = true, title = "Saved fuel trips", onClose = close)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active != null) {
                item {
                    TextButton(onClick = { results = !results }) {
                        Text(if (results) "Hide active trip" else "Show active trip", color = c.accentInk)
                    }
                    if (results) ActiveFuelTripCard(active, ui, events,
                        { planner.savedTrips.setActive(vin, it) },
                        refreshStatus, loadTrips, { ending = true },
                        { edit(active.plan, true) }, platform::openUrl)
                }
            }
            if (plans.isEmpty()) item { Text("No saved fuel trips", color = c.muted) }
            items(plans, key = { it.id }) { trip ->
                LfCard {
                    androidx.compose.material3.Text(trip.title, style = Type.label, color = c.text)
                    androidx.compose.material3.Text(fuelDate(trip.calculated), style = Type.small, color = c.muted)
                    Text(trip.request.constraints.strategy.label, style = Type.small, color = c.muted)
                    SavedTripMetric("Distance", "${formatFuelNumber(trip.km, 1)} km")
                    SavedTripMetric("Planned fuel purchases", trip.pumpCost?.let(::tripEuro))
                    TextButton(onClick = { details = if (details == trip.id) null else trip.id }) {
                        Text(if (details == trip.id) "Hide original plan" else "View original plan", color = c.accentInk)
                    }
                    if (details == trip.id) {
                        SavedTripMetric("Departure", fuelDate(trip.request.departure))
                        if (trip.request.via.isNotEmpty()) SavedTripMetric("Waypoints", trip.request.via.joinToString(" · ") { it.name })
                        SavedTripMetric("Total planned duration", duration(trip.totalMin))
                        SavedTripMetric("Expected consumption", "${formatFuelNumber(trip.expected, 2)} ${trip.request.grade.unit}")
                        SavedTripMetric("Estimated value of fuel consumed", trip.expectedCost?.let(::tripEuro))
                        for (stop in trip.stops) {
                            SavedTripMetric(stop.name, "${formatFuelNumber(stop.amount, 2)} ${trip.request.grade.unit}")
                            stop.price?.let { SavedTripMetric("Price used", "${tripEuro(it, 3)}/${trip.request.grade.unit}") }
                            if (stop.locked) Text("Confirmed stop", style = Type.small, color = c.muted)
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { edit(trip, false) }) { Text("Edit trip", color = c.accentInk) }
                            TextButton(onClick = { edit(trip, true) }) { Text("Update prices and starting fuel", color = c.accentInk) }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { planner.savedTrips.save(vin, trip.copy(id = UUID.randomUUID().toString())) }) {
                                Text("Duplicate", color = c.accentInk)
                            }
                            TextButton(onClick = { deleting = trip }) { Text("Delete", color = c.danger) }
                        }
                        if (active == null || active.endedAt != null) {
                            PrimaryButton("Start trip", Modifier.fillMaxWidth()) {
                                val now = System.currentTimeMillis()
                                val initial = ui.status?.takeIf { status ->
                                    status.updatedAtMillis?.let { now - it in 0..60 * 60_000L } == true && !status.moving
                                }?.odometerKm
                                planner.savedTrips.setActive(vin, ActiveFuelTrip(trip, now, initial))
                                results = true
                                details = null
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { trip ->
        ConfirmFuelAction("Delete saved trip?", { deleting = null }) {
            planner.savedTrips.remove(vin, trip.id)
            deleting = null
        }
    }
    if (ending && active?.endedAt == null) {
        ConfirmFuelAction("Finish active trip?", { ending = false }) {
            active?.let { planner.savedTrips.setActive(vin, it.copy(endedAt = System.currentTimeMillis())) }
            ending = false
            loadTrips()
        }
    }
}

@Composable
private fun ActiveFuelTripCard(
    active: ActiveFuelTrip, ui: UiState, events: List<Refuel>, save: (ActiveFuelTrip) -> Unit,
    refresh: () -> Unit, loadTrips: () -> Unit, finish: () -> Unit, review: () -> Unit, navigate: (String) -> Unit,
) {
    val c = LocalLf.current
    val plan = active.plan
    val unit = plan.request.grade.unit
    val status = ui.status
    val progress = active.progress(status?.odometerKm?.takeIf { (status.updatedAtMillis ?: 0L) >= active.startedAt })
    val left = progress?.let { (plan.km - it).coerceAtLeast(0.0) }
    val fraction = progress?.div(plan.km)?.coerceIn(0.0, 1.0)
    val end = active.endedAt
    val candidates = remember(active, ui.trips) { active.links(ui.trips) }
    val selected = candidates.filter { it.id in active.actualTripIds }
    val actual = actualFuelTrip(selected, plan.request.grade)
    val next = plan.stops.getOrNull(active.completedStops)
    val gauge = status?.fuelPct
    val reserve = plan.request.constraints.reserve
    val capacity = plan.request.constraints.capacity
    val fresh = (status?.fuelAtMillis ?: status?.updatedAtMillis)?.let { System.currentTimeMillis() - it in 0..90 * 60_000L } == true
    val fuelRange = status?.fuelRangeKm
    val tooFar = progress != null && fuelRange != null && (next?.accessAtKm ?: plan.km) - progress > fuelRange
    val reserveRisk = fresh && gauge != null && gauge * capacity / 100 < reserve
    val offPlan = fresh && progress != null && next != null && progress > next.accessAtKm + 4
    val window = if (end != null) active.startedAt..end else LongRange.EMPTY
    val refuels = events.filter { it.at in window && it.grade == plan.request.grade && it.confirmed }
    val paid = refuels.takeIf { it.isNotEmpty() && it.all { row -> row.total != null && row.currency == "EUR" } }?.sumOf { it.total ?: 0.0 }

    LfCard {
        Text(if (end == null) "Active fuel trip" else "Completed fuel trip", style = Type.label, color = c.text)
        androidx.compose.material3.Text(plan.title, style = Type.body, color = c.text)
        if (end == null) {
            SavedTripMetric("Vehicle update", (status?.fuelAtMillis ?: status?.updatedAtMillis)?.let { fuelDate(Instant.ofEpochMilli(it)) })
            SavedTripMetric("Distance from odometer", progress?.let { "${formatFuelNumber(it, 1)} km" })
            SavedTripMetric("Estimated distance remaining", left?.let { "${formatFuelNumber(it, 1)} km" })
            SavedTripMetric("Estimated time remaining", fraction?.let { duration(plan.totalMin * (1 - it)) })
            SavedTripMetric("Expected consumption", "${formatFuelNumber(plan.expected, 2)} $unit")
            SavedTripMetric("Estimated fuel used at this distance", fraction?.let { "${formatFuelNumber(plan.expected * it, 2)} $unit" })
            SavedTripMetric("Reported fuel level", gauge?.let { "${formatFuelNumber(it, 0)} %" })
            SavedTripMetric("Reported fuel range", fuelRange?.let { "${formatFuelNumber(it, 0)} km" })
            if (!fresh) Text("Vehicle fuel data is out of date or unavailable", style = Type.small, color = c.muted)
            next?.let { stop ->
                SavedTripMetric("Next fuel stop", stop.name)
                SavedTripMetric("Recommended fill", "${formatFuelNumber(stop.amount, 2)} $unit")
                TextButton(onClick = { save(active.copy(completedStops = active.completedStops + 1)) }) {
                    Text("Mark stop visited", color = c.accentInk)
                }
            }
            if (reserveRisk || offPlan || (fresh && tooFar)) {
                Text("Check the remaining fuel and the next stop", style = Type.small, color = c.danger)
                TextButton(onClick = review) { Text("Review planned stops", color = c.accentInk) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { navigate(plan.navigationUrl(next)) }) { Text("Open in Maps", color = c.accentInk) }
                TextButton(enabled = !ui.refreshing, onClick = refresh) { Text("Refresh vehicle", color = c.accentInk) }
                TextButton(onClick = finish) { Text("Finish trip", color = c.accentInk) }
            }
        } else {
            Text("Select the recorded legs that belong to this trip", style = Type.small, color = c.muted)
            if (candidates.isEmpty()) Text("No recorded legs in the trip window", style = Type.small, color = c.muted)
            candidates.forEach { trip ->
                val at = trip.startMillis ?: return@forEach
                TextButton(onClick = {
                    val ids = active.actualTripIds
                    save(active.copy(actualTripIds = if (trip.id in ids) ids - trip.id else ids + trip.id))
                }) {
                    androidx.compose.material3.Text(
                        "${if (trip.id in active.actualTripIds) "✓" else "○"} ${fuelDate(Instant.ofEpochMilli(at))} · ${formatFuelNumber(trip.distanceKm, 1)} km",
                        style = Type.small, color = c.accentInk)
                }
            }
            SavedTripMetric("Planned / actual distance", "${formatFuelNumber(plan.km, 1)} / ${actual?.distance?.let { formatFuelNumber(it, 1) } ?: "—"} km")
            SavedTripMetric("Planned / recorded drive time", "${duration(plan.driveMin)} / ${actual?.durationMinutes?.let(::duration) ?: "—"}")
            SavedTripMetric("Expected / recorded fuel used",
                "${formatFuelNumber(plan.expected, 2)} / ${actual?.liters?.let { formatFuelNumber(it, 2) } ?: "—"} $unit")
            if (plan.km > 0 && actual != null && actual.distance > 0 && actual.liters != null) {
                SavedTripMetric("Expected / recorded consumption",
                    "${formatFuelNumber(plan.expected * 100 / plan.km, 2)} / ${formatFuelNumber(actual.liters * 100 / actual.distance, 2)} $unit/100 km")
            }
            SavedTripMetric("Planned stops / confirmed refuels in trip window", "${plan.stops.size} / ${refuels.size}")
            SavedTripMetric("Planned purchases / confirmed spend", "${plan.pumpCost?.let(::tripEuro) ?: "—"} / ${paid?.let(::tripEuro) ?: "—"}")
            TextButton(enabled = !ui.tripsLoading, onClick = loadTrips) { Text("Refresh recorded trips", color = c.accentInk) }
        }
    }
}

@Composable
internal fun SavedTripComparison(original: SavedFuelTrip, updated: FuelTripResult) {
    val plan = updated.plan ?: return
    val c = LocalLf.current
    LfCard {
        Text("Original / updated plan", style = Type.label, color = c.text)
        SavedTripMetric("Distance", "${formatFuelNumber(original.km, 1)} / ${formatFuelNumber(plan.totalKm, 1)} km")
        SavedTripMetric("Total planned duration", "${duration(original.totalMin)} / ${duration(plan.totalMin)}")
        if (original.request.grade.unit == updated.request.grade.unit) {
            SavedTripMetric("Expected consumption", "${formatFuelNumber(original.expected, 2)} / ${formatFuelNumber(updated.expectedUsed(plan), 2)} ${updated.request.grade.unit}")
        }
        SavedTripMetric("Planned fuel purchases", "${original.pumpCost?.let(::tripEuro) ?: "—"} / ${plan.purchaseCost?.let(::tripEuro) ?: "—"}")
    }
}

@Composable
private fun SavedTripMetric(label: String, value: String?) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = Type.small, color = c.muted)
        androidx.compose.material3.Text(value ?: "—", style = Type.small, color = c.text)
    }
}

@Composable
private fun ConfirmFuelAction(title: String, close: () -> Unit, confirm: () -> Unit) {
    val c = LocalLf.current
    AlertDialog(onDismissRequest = close, containerColor = c.card, title = { Text(title, color = c.text) },
        confirmButton = { TextButton(onClick = confirm) { Text("Confirm", color = c.accentInk) } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel", color = c.muted) } })
}

private fun tripEuro(value: Double) = tripEuro(value, 2)
private fun tripEuro(value: Double, decimals: Int) = "€${formatFuelNumber(value, decimals)}"
private fun duration(minutes: Double) = "${minutes.toInt() / 60} h ${minutes.toInt() % 60} min"
