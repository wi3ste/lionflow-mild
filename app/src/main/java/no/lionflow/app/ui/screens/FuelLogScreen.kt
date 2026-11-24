package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.lionflow.app.fuel.*
import no.lionflow.app.model.Trip
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class FuelPeriod(val label: String) {
    MONTH("This month"), YEAR("This year"), ALL("All time");
}

@Composable
internal fun FuelLogScreen(
    log: FuelLog, vin: String, defaultGrade: FuelGrade, trips: List<Trip>, loadingTrips: Boolean,
    loadTrips: (Int) -> Unit, close: () -> Unit,
) {
    val c = LocalLf.current
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val change by log.changes.collectAsStateWithLifecycle()
    val rows = remember(vin, change) { log.entries(vin) }
    var grade by remember { mutableStateOf(defaultGrade) }
    var period by remember { mutableStateOf(FuelPeriod.MONTH) }
    var edit by remember { mutableStateOf<Refuel?>(null) }
    var delete by remember { mutableStateOf<Refuel?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val date = LocalDate.now(zone)
    val today = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val begin = when (period) {
        FuelPeriod.MONTH -> date.withDayOfMonth(1)
        FuelPeriod.YEAR -> date.withDayOfYear(1)
        FuelPeriod.ALL -> LocalDate.of(1900, 1, 1)
    }
    val previous = when (period) {
        FuelPeriod.MONTH -> begin.minusMonths(1)
        FuelPeriod.YEAR -> begin.minusYears(1)
        FuelPeriod.ALL -> null
    }
    val from = begin.atStartOfDay(zone).toInstant().toEpochMilli()
    val currencies = listOf("EUR") + rows.map { it.currency }.distinct().filter { it != "EUR" }
    var currency by remember { mutableStateOf("EUR") }
    val stats = remember(rows, trips, from, today, grade, currency) { fuelEconomics(rows, trips, from, today, grade, currency) }
    val comparison = remember(rows, trips, previous, from, grade, currency) {
        previous?.let { fuelEconomics(rows, trips, it.atStartOfDay(zone).toInstant().toEpochMilli(), from, grade, currency) }
    }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.systemBars)) {
        PlanHeader(back = true, title = "Fuel log", onClose = close)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                PrimaryButton("Add refuel", Modifier.fillMaxWidth()) {
                    edit = Refuel(at = System.currentTimeMillis(), grade = grade, amount = null, currency = currency)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FuelChoice(FuelGrade.entries, grade, FuelGrade::label) { grade = it }
                    FuelChoice(FuelPeriod.entries, period, FuelPeriod::label) { period = it }
                    if (currencies.size > 1) FuelChoice(currencies, currency, { it }) { currency = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        showStats = !showStats
                        if (showStats && trips.isEmpty() && !loadingTrips) loadTrips(30)
                    }) { Text(if (showStats) "Hide fuel statistics" else "Fuel statistics", color = c.accentInk) }
                    TextButton(enabled = rows.isNotEmpty(), onClick = {
                        scope.launch {
                            try {
                                platform.share(withContext(Dispatchers.IO) { log.export(vin) }, "text/csv")
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                exportFailed = true
                            }
                        }
                    }) { Text("Export CSV", color = c.accentInk) }
                }
                if (exportFailed) Text("Could not export refuels", style = Type.small, color = c.danger)
                if (showStats) {
                    LfCard {
                        FuelStat("Confirmed purchases", stats.spend?.let { "${formatFuelNumber(it, 2)} $currency" })
                        FuelStat("Quantity purchased", stats.quantity?.let { "${formatFuelNumber(it, 2)} ${grade.unit}" })
                        FuelStat("Weighted average price", stats.averagePrice?.let { "${formatFuelNumber(it, 3)} $currency/${grade.unit}" })
                        comparison?.let {
                            FuelStat("Previous period purchases", it.spend?.let { cost -> "${formatFuelNumber(cost, 2)} $currency" })
                        }
                        FuelStat("Distance in available trips", "${formatFuelNumber(stats.distance, 1)} km")
                        FuelStat("Recorded consumption", stats.fuelPer100?.let { "${formatFuelNumber(it, 2)} ${grade.unit}/100 km" })
                        FuelStat("Estimated cost of fuel consumed", stats.consumptionCost(stats.averagePrice)?.let { "${formatFuelNumber(it, 2)} $currency" })
                        stats.costPerKm(stats.averagePrice)?.let { perKm ->
                            FuelStat("Estimated fuel cost per km", "${formatFuelNumber(perKm, 3)} $currency")
                            FuelStat("Estimated fuel cost per 100 km", "${formatFuelNumber(perKm * 100, 2)} $currency")
                        }
                        if (stats.estimated > 0) FuelStat("Entries with estimates", stats.estimated.toString())
                        if (stats.byStation.isNotEmpty()) {
                            Text("Refuels by station", style = Type.label, color = c.text)
                            stats.byStation.entries.sortedByDescending { it.value }.forEach { (name, count) ->
                                if (name.isBlank()) FuelStat("Unknown station", count.toString())
                                else androidx.compose.material3.Text("$name · $count", style = Type.small, color = c.text)
                            }
                        }
                        if (loadingTrips) Text("Loading trips…", style = Type.small, color = c.muted)
                        else TextButton(onClick = { loadTrips(if (period == FuelPeriod.MONTH) 65 else 735) }) {
                            Text("Load more trip history", color = c.accentInk)
                        }
                    }
                }
            }
            val filtered = rows.filter { it.grade == grade && it.at in from until today }
            if (filtered.isEmpty()) item { Text("No refuels for this period", color = c.muted) }
            items(filtered, key = { it.id }) { row ->
                LfCard {
                    androidx.compose.material3.Text(fuelDate(Instant.ofEpochMilli(row.at)), style = Type.label, color = c.text)
                    Text(row.source.label, style = Type.small, color = c.muted)
                    row.stationName?.let { androidx.compose.material3.Text(it, style = Type.small, color = c.text) }
                    val quantity = row.amount?.let { "${if (row.quantityEstimated) "≈ " else ""}${formatFuelNumber(it, 2)} ${row.grade.unit}" }
                    val total = row.total?.let { "${formatFuelNumber(it, 2)} ${row.currency}" }
                    androidx.compose.material3.Text(listOfNotNull(quantity, total).joinToString(" · "), style = Type.body, color = c.text)
                    row.estimatedPrice?.takeIf { !row.confirmed }?.let {
                        FuelStat("MIMIT estimated price", "${formatFuelNumber(it, 3)} EUR/${row.grade.unit}")
                    }
                    Row {
                        TextButton(onClick = { edit = row }) { Text(if (row.confirmed) "Edit" else "Review refuel", color = c.accentInk) }
                        TextButton(onClick = { delete = row }) { Text("Delete", color = c.danger) }
                    }
                }
            }
        }
    }
    edit?.let { row ->
        RefuelEditor(row, { edit = null }) {
            log.save(vin, it)
            grade = it.grade
            if (it.at !in from until today) period = FuelPeriod.ALL
            edit = null
        }
    }
    delete?.let { row ->
        AlertDialog(onDismissRequest = { delete = null }, containerColor = c.card,
            title = { Text("Delete this refuel?", color = c.text) },
            confirmButton = { TextButton(onClick = { log.remove(vin, row.id); delete = null }) { Text("Delete", color = c.danger) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel", color = c.muted) } })
    }
}

@Composable
private fun FuelStat(label: String, value: String?) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = Type.small, color = c.muted)
        androidx.compose.material3.Text(value ?: "—", style = Type.small, color = c.text)
    }
}

private val inputDate = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
    .withResolverStyle(java.time.format.ResolverStyle.STRICT)

@Composable
private fun RefuelEditor(row: Refuel, close: () -> Unit, save: (Refuel) -> Unit) {
    val c = LocalLf.current
    val zone = ZoneId.systemDefault()
    var date by remember { mutableStateOf(Instant.ofEpochMilli(row.at).atZone(zone).format(inputDate)) }
    var grade by remember { mutableStateOf(row.grade) }
    var station by remember { mutableStateOf(row.stationName.orEmpty()) }
    var currency by remember { mutableStateOf(row.currency) }
    var notes by remember { mutableStateOf(row.notes) }
    var full by remember { mutableStateOf(row.full) }
    var estimate by remember { mutableStateOf(row.quantityEstimated) }
    var advanced by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf(row.amount?.let { formatFuelNumber(it, 2) }.orEmpty()) }
    var cost by remember { mutableStateOf(row.total?.let { formatFuelNumber(it, 2) }.orEmpty()) }
    var price by remember { mutableStateOf(row.unitPrice?.let { formatFuelNumber(it, 3) }.orEmpty()) }
    var odo by remember { mutableStateOf(row.odometer?.toString().orEmpty()) }
    var before by remember { mutableStateOf(row.before?.toString().orEmpty()) }
    var after by remember { mutableStateOf(row.after?.toString().orEmpty()) }
    var rangeBefore by remember { mutableStateOf(row.rangeBefore?.toString().orEmpty()) }
    var rangeAfter by remember { mutableStateOf(row.rangeAfter?.toString().orEmpty()) }
    fun number(text: String, min: Double, max: Double) = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in min..max }
    val time = runCatching { LocalDateTime.parse(date.trim(), inputDate).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    val quantity = number(amount, .01, 500.0)
    val paid = number(cost, 0.0, 10000.0)
    val unitPrice = number(price, 0.0, 100.0)
    val actual = paid ?: if (!estimate && quantity != null && unitPrice != null) quantity * unitPrice else null
    val odoValue = number(odo, 0.0, 5_000_000.0)
    val beforeValue = number(before, 0.0, 100.0)
    val afterValue = number(after, 0.0, 100.0)
    val rangeBeforeValue = number(rangeBefore, 0.0, 3000.0)
    val rangeAfterValue = number(rangeAfter, 0.0, 3000.0)
    val code = currency.trim().uppercase(java.util.Locale.ROOT)
    val valid = time != null && time in 0..(System.currentTimeMillis() + 60_000) && (quantity != null || (row.quantityEstimated && amount.isBlank())) &&
        (cost.isBlank() || paid != null) && (price.isBlank() || unitPrice != null) &&
        (odo.isBlank() || odoValue != null) && (before.isBlank() || beforeValue != null) && (after.isBlank() || afterValue != null) &&
        (rangeBefore.isBlank() || rangeBeforeValue != null) && (rangeAfter.isBlank() || rangeAfterValue != null) &&
        runCatching { java.util.Currency.getInstance(code) }.isSuccess

    AlertDialog(onDismissRequest = close, containerColor = c.card, title = { Text("Refuel", color = c.text) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelChoice(FuelGrade.entries, grade, FuelGrade::label) { grade = it }
            FuelEditField(date, { date = it }, "Date and time (YYYY-MM-DD HH:mm)")
            FuelEditField(amount, { amount = it }, L10n.tr("Quantity") + " (${grade.unit})", true)
            if (row.quantityEstimated) FilterChip("Quantity is estimated", estimate) { estimate = !estimate }
            FuelEditField(cost, { cost = it; if (it.isNotBlank()) price = "" }, "Amount paid", true)
            if (cost.isBlank() && !estimate) FuelEditField(price, { price = it }, "Price per unit paid", true)
            FuelEditField(station, { station = it }, "Station")
            FuelChoice(listOf<Boolean?>(null, false, true), full, { if (it == null) "Fill type unknown" else if (it) "Full tank" else "Partial fill" }) { full = it }
            TextButton(onClick = { advanced = !advanced }) { Text("Refuel details", color = c.accentInk) }
            if (advanced) {
                FuelEditField(odo, { odo = it }, "Odometer (km)", true)
                FuelEditField(currency, { currency = it }, "Currency (ISO code)")
                FuelEditField(before, { before = it }, "Fuel level before (%)", true)
                FuelEditField(after, { after = it }, "Fuel level after (%)", true)
                FuelEditField(rangeBefore, { rangeBefore = it }, "Range before (km)", true)
                FuelEditField(rangeAfter, { rangeAfter = it }, "Range after (km)", true)
                FuelEditField(notes, { notes = it }, "Notes")
            }
        }
    }, confirmButton = {
        TextButton(enabled = valid, onClick = {
            save(row.copy(at = requireNotNull(time), grade = grade, amount = quantity, total = actual, currency = code,
                stationId = row.stationId.takeIf { row.stationName == station.trim() }, stationName = station.trim().takeIf(String::isNotBlank),
                odometer = odoValue, before = beforeValue, after = afterValue, full = full, notes = notes,
                rangeBefore = rangeBeforeValue, rangeAfter = rangeAfterValue, quantityEstimated = estimate || quantity == null,
                source = if (row.source == RefuelSource.DETECTED) RefuelSource.CONFIRMED else row.source))
        }) { Text(if (row.source == RefuelSource.DETECTED) "Confirm refuel" else "Save", color = if (valid) c.accentInk else c.muted) }
    }, dismissButton = { TextButton(onClick = close) { Text("Cancel", color = c.muted) } })
}

@Composable
private fun FuelEditField(value: String, change: (String) -> Unit, label: String, decimal: Boolean = false) {
    OutlinedTextField(value, change, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text))
}
