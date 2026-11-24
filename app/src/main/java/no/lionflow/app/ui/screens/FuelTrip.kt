package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.fuel.FuelCandidate
import no.lionflow.app.fuel.FuelPlan
import no.lionflow.app.fuel.FuelConstraints
import no.lionflow.app.fuel.FuelConsumption
import no.lionflow.app.fuel.FuelGrade
import no.lionflow.app.fuel.FuelOffer
import no.lionflow.app.fuel.FuelPlanner
import no.lionflow.app.fuel.FuelProfile
import no.lionflow.app.fuel.FuelServiceFilter
import no.lionflow.app.fuel.FuelStation
import no.lionflow.app.fuel.FuelStop
import no.lionflow.app.fuel.FuelStrategy
import no.lionflow.app.fuel.FuelTripRequest
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.Planner
import no.lionflow.app.ui.Busy
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.text.DateFormat
import java.text.NumberFormat
import java.time.Instant
import java.util.Date
import java.util.Currency

internal class FuelFormState(
    private val planner: FuelPlanner,
    val vin: String,
    defaultProfile: FuelProfile,
) {
    var from by mutableStateOf<Place?>(null)
    var to by mutableStateOf<Place?>(null)
    var vias by mutableStateOf<List<Place?>>(emptyList())
    var round by mutableStateOf(false)
    var depart by mutableStateOf<Long?>(null)
    var initial by mutableStateOf("")
    var percent by mutableStateOf(true)
    var profile by mutableStateOf(planner.setting(vin, "profile")?.let { name -> FuelProfile.entries.find { it.name == name } } ?: defaultProfile)
    var reserve by mutableStateOf(planner.setting(vin, "reserve") ?: "10")
    var safety by mutableStateOf(planner.setting(vin, "safety") ?: "5")
    var detour by mutableStateOf(planner.setting(vin, "detour") ?: "8")
    var strategy by mutableStateOf(FuelStrategy.BALANCED)
    private fun gradeKey(name: String) = "$name.${planner.grade.name}"
    var capacity by mutableStateOf("")
        private set
    var manual by mutableStateOf("")
        private set
    private val tankInputs = mutableMapOf<FuelGrade, Pair<String, String>>()

    fun useGrade(grade: FuelGrade) {
        tankInputs[planner.grade] = capacity to manual
        if (grade != planner.grade) initial = ""
        planner.grade = grade
        loadTank()
    }

    fun loadTank() {
        val draft = tankInputs[planner.grade]
        capacity = draft?.first ?: planner.setting(vin, gradeKey("capacity")).orEmpty()
        manual = draft?.second ?: planner.setting(vin, gradeKey("manual")).orEmpty()
    }

    fun setTank(value: String) { capacity = value }
    fun updateManual(value: String) { manual = value }

    fun save() = planner.save(vin, mapOf(
        "grade" to planner.grade.name, "profile" to profile.name, "reserve" to reserve,
        "safety" to safety, "detour" to detour, gradeKey("capacity") to capacity, gradeKey("manual") to manual,
    ))
}

@Composable
internal fun FuelTripForm(p: FuelPlanner, addresses: Planner, f: FuelFormState, ui: UiState) {
    val c = LocalLf.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var field by remember { mutableIntStateOf(-1) }
    var query by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var saved by remember(p.grade, f.capacity, f.manual, f.profile, f.reserve, f.safety, f.detour) { mutableStateOf(false) }
    val here = ui.status?.let { s -> if (s.lat != null && s.lon != null) Place("Car's position", "", s.lat, s.lon) else null }
        ?: if (ui.myLat != null && ui.myLon != null) Place("My position", "", ui.myLat, ui.myLon) else null
    fun open(index: Int) {
        field = index
        query = ""
        addresses.clearSearch()
    }
    fun dismiss() { open(-1); focus.clearFocus() }
    fun pick(place: Place?) {
        when (field) {
            0 -> f.from = place
            1 -> f.to = place
            else -> f.vias = f.vias.mapIndexed { i, value -> if (i + 2 == field) place else value }
        }
        dismiss()
    }
    LaunchedEffect(field) { if (field >= 0) requester.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        for (i in listOf(0) + f.vias.indices.map { it + 2 } + 1) {
            val place = when (i) { 0 -> f.from ?: here; 1 -> f.to; else -> f.vias[i - 2] }
            AddressRow(
                if (i == 0) LfIcons.Car else LfIcons.Pin, c.accentInk,
                when (i) { 0 -> "From"; 1 -> "Where to?"; else -> "Via" },
                place?.let(::placeName), field == i, query, requester, { query = it; addresses.search(it) },
                { open(i) }, { if (i >= 2 && place == null) f.vias = f.vias.filterIndexed { n, _ -> n != i - 2 }; dismiss() },
                onRemove = if (i >= 2) ({ f.vias = f.vias.filterIndexed { n, _ -> n != i - 2 }; dismiss() }) else null,
            )
        }
        if (field >= 0) {
            val results = if (query.trim().length < 2) addresses.saved + addresses.recent else addresses.results
            if (field == 0 && here != null) SuggestRow(LfIcons.Car, c.accentInk, placeName(here), "", null, {}) { pick(null) }
            for (place in results.distinctBy { it.name to it.detail }.take(8)) {
                SuggestRow(LfIcons.Pin, c.muted, place.name, place.detail, addresses.isSaved(place), { addresses.toggleSaved(place) }) { pick(place) }
            }
            if (results.isEmpty()) Text(when {
                query.trim().length < 2 -> "Type a place or address"
                addresses.searching -> "Searching…"
                else -> "No places found"
            }, style = Type.small, color = c.muted)
            return@Column
        }
        if (f.vias.size < 3) TextButton(onClick = { f.vias = f.vias + listOf(null); open(f.vias.size + 1) }) {
            Text("Add a stop on the way", color = c.accentInk, style = Type.small)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelChoice(listOf(false, true), f.round, { if (it) "Round trip" else "One way" }) { f.round = it }
            SecondaryButton(f.depart?.let { fuelDate(Instant.ofEpochMilli(it)) } ?: "Leave now") { picker = true }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelChoice(FuelGrade.entries, p.grade, FuelGrade::label, f::useGrade)
            FuelChoice(FuelProfile.entries, f.profile, FuelProfile::label) { f.profile = it }
        }
        if (f.profile == FuelProfile.PLUG_IN) Text("Petrol or diesel range only", style = Type.small, color = c.muted)

        val grade = p.grade
        val consumption = remember(grade, f.profile, ui.trips, f.manual) {
            FuelConsumption.estimate(grade, f.profile, ui.trips, f.manual.fuelNumber()?.takeIf { it in 0.5..40.0 }, 0.0)
        }
        val tank = f.capacity.fuelNumber()
        val pct = ui.status?.fuelPct?.takeIf { it in 0.0..100.0 }
        val range = ui.status?.fuelRangeKm?.takeIf { it in 1.0..1600.0 && grade in setOf(FuelGrade.PETROL, FuelGrade.DIESEL) }
        val amountGuess = if (tank != null && pct != null) pct * tank / 100 else range?.times(consumption.per100)?.div(100)
            ?.let { if (tank != null) it.coerceAtMost(tank) else it }
        val hint = if (f.percent) pct ?: if (tank != null && tank > 0) amountGuess?.times(100)?.div(tank) else null else amountGuess
        FuelNumber(L10n.tr("Tank capacity") + " (${grade.unit})", f.capacity, f::setTank)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FuelNumber(L10n.tr("Starting fuel") + " (${if (f.percent) "%" else grade.unit})", f.initial,
                { f.initial = it }, Modifier.weight(1f), hint?.let { fuelNumber(it, 1) })
            FuelChoice(listOf(true, false), f.percent, { if (it) "%" else grade.unit }) { selected ->
                if (selected != f.percent) { f.percent = selected; f.initial = "" }
            }
        }
        if (f.initial.isBlank() && hint != null) Text(
            if (pct != null) "Starting level from the vehicle" else "Starting level estimated from reported range",
            style = Type.small, color = c.muted,
        )
        FuelNumber(L10n.tr("Consumption override") + " (${grade.unit}/100 km)", f.manual, f::updateManual)
        if (f.manual.isBlank()) Text("Consumption is estimated after routing.", style = Type.small, color = c.muted)
        FuelNumber(L10n.tr("Minimum reserve") + " (%)", f.reserve, { f.reserve = it })
        FuelNumber(L10n.tr("Consumption safety margin") + " (%)", f.safety, { f.safety = it })
        FuelNumber(L10n.tr("Maximum detour per stop") + " (km)", f.detour, { f.detour = it })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelChoice(FuelServiceFilter.entries, p.service, FuelServiceFilter::label) { p.service = it }
            FuelChoice(FuelStrategy.entries, f.strategy, FuelStrategy::label) { f.strategy = it }
        }
        val reserve = f.reserve.fuelNumber()?.takeIf { it in 0.0..90.0 }
        val safety = f.safety.fuelNumber()?.takeIf { it in 0.0..50.0 }
        val detour = f.detour.fuelNumber()?.takeIf { it in 0.5..30.0 }
        val validSettings = tank != null && tank in 1.0..300.0 && reserve != null && safety != null && detour != null &&
            (f.manual.isBlank() || f.manual.fuelNumber()?.let { it in 0.5..40.0 } == true)
        if (f.vin.isNotBlank() && validSettings) SecondaryButton("Save vehicle fuel settings") {
            f.save()
            saved = true
            invalid = false
        }
        if (saved) Text("Vehicle fuel settings saved", color = c.muted, style = Type.tiny)
        PrimaryButton("Plan fuel trip", Modifier.fillMaxWidth(), icon = LfIcons.Route, enabled = p.busy == null && f.to != null) {
            val from = f.from ?: here?.let { position ->
                p.result?.request?.from?.takeIf { previous -> previous.name == position.name && previous.detail.isEmpty() &&
                    no.lionflow.app.plan.Geo.km(previous.lat, previous.lon, position.lat, position.lon) < 0.10 } ?: position
            }
            val to = f.to
            val level = if (f.initial.isBlank()) hint else f.initial.fuelNumber()
            if (!validSettings || from == null || to == null || level == null ||
                level !in 0.0..(if (f.percent) 100.0 else tank) || f.vias.any { it == null }) {
                invalid = true
                return@PrimaryButton
            }
            invalid = false
            focus.clearFocus()
            f.save()
            val initial = if (f.percent) tank * level / 100 else level
            p.calculate(FuelTripRequest(from, to, f.vias.filterNotNull(), f.round,
                f.depart?.let(Instant::ofEpochMilli)?.takeIf { it > Instant.now() } ?: Instant.now(), grade, p.service,
                f.profile, f.manual.fuelNumber(), checkNotNull(safety),
                FuelConstraints(tank, initial, tank * reserve / 100, f.strategy), detour))
        }
        if (invalid) Text("Check departure, tank capacity, starting fuel and limits.", style = Type.small, color = c.danger)
        FuelProgress(p)
    }
    if (picker) DepartPicker(f.depart, { f.depart = it; picker = false }, { picker = false })
}

@Composable
internal fun <T> FuelChoice(options: List<T>, value: T, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val c = LocalLf.current
    Box {
        FilterChip(label(value), false) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card) {
            options.forEach { item ->
                DropdownMenuItem(text = { Text(label(item), color = c.text, style = Type.body) },
                    onClick = { onSelect(item); open = false })
            }
        }
    }
}

@Composable
private fun FuelNumber(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    val c = LocalLf.current
    OutlinedTextField(value, { change(it.take(9)) }, modifier = modifier.fillMaxWidth(), singleLine = true,
        textStyle = Type.body.copy(color = c.text), label = { androidx.compose.material3.Text(label, color = c.muted, style = Type.small) },
        placeholder = hint?.let { { Text(it, color = c.muted, style = Type.body) } }, shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

private fun String.fuelNumber(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)
internal fun fuelNumber(value: Double, digits: Int) = NumberFormat.getNumberInstance(L10n.locale).apply {
    minimumFractionDigits = digits
    maximumFractionDigits = digits
}.format(value)
internal fun fuelMoney(value: Double?, currency: String = "EUR", digits: Int = 2): String = if (value == null) L10n.tr("Unknown") else
    NumberFormat.getCurrencyInstance(L10n.locale).apply {
        this.currency = Currency.getInstance(currency)
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }.format(value)
internal fun fuelDate(time: Instant): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, L10n.locale).format(Date.from(time))

@Composable
private fun FuelMetric(label: String, value: String) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Type.small, color = c.muted, modifier = Modifier.weight(1f))
        androidx.compose.material3.Text(value, style = Type.label, color = c.text)
    }
}

@Composable
internal fun FuelProgress(p: FuelPlanner) {
    val c = LocalLf.current
    p.busy?.let {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Busy(); Text(it, style = Type.small, color = c.muted) }
        if (p.result?.plan != null) Text("Verified plan · still optimizing", style = Type.small, color = c.muted)
        SecondaryButton(if (p.result?.plan != null) "Keep this plan" else "Cancel calculation") { p.cancel() }
    }
    p.error?.let { Text(it, style = Type.small, color = c.danger) }
}

@Composable
internal fun FuelTripResultPanel(p: FuelPlanner, settings: AppSettings, newHistory: Boolean, clickStation: (FuelStation) -> Unit) {
    val result = p.result ?: return
    val plan = result.plan ?: return
    val c = LocalLf.current
    val platform = LocalPlatform.current
    val unit = result.request.grade.unit
    var editingStop by remember(result) { mutableStateOf<FuelStop?>(null) }
    var showComparison by remember { mutableStateOf(false) }
    var showSamples by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val strategy = result.request.constraints.strategy
    if (showComparison) {
        val fastest = result.comparisons[FuelStrategy.FAST]
        val groups = result.comparisons.entries.groupBy { (_, comparison) ->
            comparison.stops.map { it.candidate.key to kotlin.math.round(it.purchased * 100) }
        }.values
        AlertDialog(onDismissRequest = { showComparison = false }, containerColor = c.card,
            title = { Text("Compare fuel strategies", color = c.text) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (group in groups) {
                        val comparison = group.first().value
                        var details by remember(group.first().key) { mutableStateOf(false) }
                        androidx.compose.material3.Text(group.joinToString(" · ") { L10n.tr(it.key.label) }, color = c.text, style = Type.label)
                        if (group.size > 1) Text("Same solution in verified stations", color = c.muted, style = Type.tiny)
                        FuelMetric("Estimated value of fuel consumed", fuelMoney(result.expectedCost(comparison)))
                        FuelMetric("Estimated total time", planDuration(comparison.totalMin))
                        FuelMetric("Fuel stops", comparison.stops.size.toString())
                        FuelMetric("Road detour", "${fmtDistance(comparison.detourKm, settings)} · ${signedDuration(comparison.extraMin)}")
                        FuelMetric("Savings vs fastest (fuel purchases)", fuelMoney(difference(fastest?.purchaseCost, comparison.purchaseCost)))
                        if (details) {
                            FuelMetric("Estimated spend on refuelling", fuelMoney(comparison.purchaseCost))
                            FuelMetric("Total distance", fmtDistance(comparison.totalKm, settings))
                            FuelMetric("Fuel at arrival", "${fuelNumber(comparison.remaining, 1)} $unit")
                        }
                        TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Show details", color = c.accentInk) }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            for ((choice, _) in group) {
                                TextButton(onClick = { p.selectStrategy(choice); showComparison = false }, enabled = choice != strategy) {
                                    Text(if (choice == strategy) "Selected: ${choice.label}" else choice.label, color = c.accentInk)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showComparison = false }) { Text("Close", color = c.accentInk) } },
        )
    }
    fun quantity(value: Double) = "${fuelNumber(value, 2)} $unit"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Fuel trip", style = Type.stat, color = c.text)
        if (p.busy != null) FuelProgress(p)
        FuelMetric("Selected strategy", L10n.tr(strategy.label))
        SecondaryButton("Compare fuel strategies") { showComparison = true }
        FuelMetric("Total distance", fmtDistance(plan.totalKm, settings))
        FuelMetric("Driving time", planDuration(plan.driveMin))
        FuelMetric("Estimated total time", planDuration(plan.totalMin))
        val prediction = result.prediction
        val expected = result.expectedUsed(plan)
        FuelMetric("Expected average consumption", "${fuelNumber(expected * 100 / plan.totalKm, 1)} $unit/100 km")
        FuelMetric("Fuel consumed", quantity(expected))
        FuelMetric("Estimated value of fuel consumed", fuelMoney(result.expectedCost(plan)))
        FuelMetric("Prediction source", L10n.tr(prediction.source.label))
        if (newHistory && p.busy == null) SecondaryButton("Recalculate with updated history") { p.recalculateConsumption() }
        FuelMetric("Consumption safety margin", "${fuelNumber(prediction.safety, 0)}%")
        FuelMetric("Prudent fuel estimate for reserve", quantity(plan.used))
        FuelMetric("Minimum reserve", quantity(result.request.constraints.reserve))
        if (prediction.samples.isNotEmpty()) {
            FuelMetric("Historical trips used", prediction.samples.size.toString())
            FuelMetric("Historical distance", fmtDistance(prediction.sampleKm, settings))
            if (prediction.samples.size < 3) Text("Limited historical support", style = Type.tiny, color = c.muted)
            TextButton(onClick = { showSamples = !showSamples }) { Text(if (showSamples) "Hide history" else "Show history", color = c.accentInk) }
            if (showSamples) prediction.samples.take(8).forEach { sample ->
                FuelMetric(fmtDistance(sample.journey.km, settings) + " · " + planDuration(sample.journey.minutes),
                    "${fuelNumber(sample.rate, 1)} $unit/100 km")
            }
        }
        if (no.lionflow.app.BuildConfig.DEBUG) {
            TextButton(onClick = { showDiagnostics = !showDiagnostics; if (showDiagnostics) p.diagnoseConsumption() }) {
                Text(if (showDiagnostics) "Hide diagnostics" else "Show diagnostics", color = c.accentInk)
            }
            if (showDiagnostics) {
                FuelMetric("Stations in corridor", result.search.corridor.toString())
                FuelMetric("Stations verified / examined", "${result.search.verified} / ${result.search.examined}")
                FuelMetric("Fresh prices at verified stations", result.search.fresh.toString())
                FuelMetric("Maximum detour per stop", fmtDistance(result.request.maxDetour, settings))
                result.search.priceRange?.let { (low, high) ->
                    FuelMetric("Verified quote range", "${fuelMoney(low, digits = 3)} – ${fuelMoney(high, digits = 3)}")
                }
                val backtest = p.backtest
                if (backtest == null) Text("Running local backtest…", style = Type.small, color = c.muted)
                else if (backtest.isEmpty()) Text("Insufficient validated history for backtesting", style = Type.small, color = c.muted)
                else backtest.forEach { band ->
                    Text(band.band, style = Type.label, color = c.text)
                    FuelMetric("Historical trips used", band.count.toString())
                    fun score(metric: no.lionflow.app.fuel.FuelError) =
                        "${fuelNumber(metric.mae, 2)} · ${fuelNumber(metric.mape, 1)}% · ${fuelNumber(metric.bias, 2)}"
                    FuelMetric("New MAE / MAPE / bias", score(band.personalized))
                    FuelMetric("Previous MAE / MAPE / bias", score(band.previous))
                }
            }
        }
        FuelMetric("Fuel to buy", quantity(plan.purchased))
        FuelMetric("Estimated spend on refuelling", fuelMoney(plan.purchaseCost))
        FuelMetric("Fuel stops", plan.stops.size.toString())
        FuelMetric("Extra distance", fmtDistance(plan.detourKm, settings))
        FuelMetric("Extra time for fuel stops", signedDuration(plan.extraMin))
        FuelMetric("Fuel at arrival", quantity(plan.remaining))
        if (plan.stops.isEmpty()) Text("No refuelling needed", color = c.muted, style = Type.small)
        if (result.limited) {
            Text("Plan based on a limited set of road-verified stations.", color = c.muted, style = Type.tiny)
            if (p.busy == null && result.search.canExtend) SecondaryButton("Check more stations") { p.searchMore() }
        }

        for ((i, stop) in plan.stops.withIndex()) {
            val station = stop.candidate.station
            val offer = stop.candidate.offer
            val shape = RoundedCornerShape(14.dp)
            Column(Modifier.fillMaxWidth().background(c.raised, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${i + 1}", style = Type.stat, color = c.accentInk)
                    androidx.compose.material3.Text(stationName(station), style = Type.label, color = c.text,
                        modifier = Modifier.weight(1f).clickable { clickStation(station) })
                    if (stop.candidate.key in p.locked) Text("Locked", color = c.accentInk, style = Type.tiny)
                }
                androidx.compose.material3.Text(station.location, style = Type.small, color = c.muted)
                FuelMetric("Position along the trip", fmtDistance(stop.candidate.projection.roadKm, settings))
                FuelMetric("Estimated arrival", fuelDate(result.request.departure.plusSeconds((stop.atMinute * 60).toLong())))
                FuelMetric("Fuel at arrival", quantity(stop.arrival))
                FuelMetric("Buy", quantity(stop.purchased))
                FuelMetric("Fuel after refuelling", quantity(stop.departure))
                Text(when {
                    stop.candidate.key in p.locked -> "Station kept by your edit"
                    strategy == FuelStrategy.ECONOMY -> "Part of the lowest estimated refuelling cost found"
                    strategy == FuelStrategy.FAST -> "Part of the fastest feasible plan found"
                    else -> "Part of the best cost and time trade-off found"
                }, color = c.muted, style = Type.tiny)
                FuelMetric("Unit price", fuelPrice(offer, result.priceTime))
                FuelMetric("Refuelling cost", fuelMoney(stop.cost, offer.currency))
                FuelMetric("Road detour", "${fmtDistance(stop.candidate.detourKm, settings)} · ${signedDuration(stop.candidate.detourMin)}")
                FuelMetric("Service", offer.service?.label?.let(L10n::tr) ?: L10n.tr("Unknown"))
                FuelMetric("Price reported", offer.reportedAt?.let(::fuelDate) ?: L10n.tr("Unknown"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (p.busy == null) SecondaryButton("Edit fuel stop") { editingStop = stop }
                    SecondaryButton("Navigate") { platform.openUrl("google.navigation:q=${station.lat},${station.lon}") }
                }
            }
        }
        if (p.busy == null) FuelProgress(p)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.busy == null) SecondaryButton("Edit trip") { p.editing = true }
            if (plan.stops.isNotEmpty() && p.busy == null) SecondaryButton("Reset fuel stops") { p.resetStops() }
        }
        PrimaryButton("Open in Maps", Modifier.fillMaxWidth(), icon = LfIcons.Locate) { platform.openUrl(result.navigationUrl()) }
    }
    editingStop?.let { stop ->
        var amount by remember(stop) { mutableStateOf(p.locked[stop.candidate.key]?.amount?.toString().orEmpty()) }
        var alternatives by remember(stop) { mutableStateOf<List<Pair<FuelCandidate, FuelPlan>>?>(null) }
        LaunchedEffect(stop) { alternatives = p.alternatives(stop) }
        AlertDialog(onDismissRequest = { editingStop = null }, containerColor = c.card,
            title = { Text("Edit fuel stop", color = c.text) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FuelNumber(L10n.tr("Fixed purchase (optional)") + " ($unit)", amount, { amount = it })
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        if (stop.candidate.key in p.locked) TextButton(onClick = { editingStop = null; p.editStops(stop, unlock = true) }) { Text("Unlock", color = c.accentInk) }
                        TextButton(onClick = { editingStop = null; p.editStops(stop, remove = true) }) { Text("Remove", color = c.danger) }
                    }
                    val options = alternatives
                    Text(when {
                        options == null -> "Checking feasible alternatives…"
                        options.isEmpty() -> "No feasible alternatives among verified stations"
                        else -> "Replace with a verified station"
                    }, color = c.muted, style = Type.small)
                    for ((candidate, revised) in options.orEmpty()) DropdownMenuItem(
                        text = {
                            Column {
                                androidx.compose.material3.Text("${stationName(candidate.station)} · ${fuelPrice(candidate.offer, result.priceTime)}", color = c.text, style = Type.small)
                                FuelMetric("Change in refuelling cost", fuelMoney(difference(revised.purchaseCost, plan.purchaseCost)))
                                FuelMetric("Change in trip time", signedDuration(revised.totalMin - plan.totalMin))
                            }
                        },
                        onClick = { editingStop = null; p.editStops(stop, replacement = candidate) },
                    )
                }
            },
            confirmButton = {
                val fixed = amount.fuelNumber()
                TextButton(enabled = amount.isBlank() || (fixed != null && fixed in 0.01..result.request.constraints.capacity),
                    onClick = { editingStop = null; p.editStops(stop, amount = fixed) }) { Text("Lock fuel stop", color = c.accentInk) }
            },
            dismissButton = { TextButton(onClick = { editingStop = null }) { Text("Cancel", color = c.muted) } },
        )
    }
}

internal fun stationName(station: FuelStation): String =
    listOf(station.title.ifBlank { L10n.tr("Fuel station") }, station.brand).filter(String::isNotBlank).distinct().joinToString(" · ")

internal fun fuelPrice(offer: FuelOffer?, now: Instant): String {
    if (offer?.price == null) return L10n.tr("Price unavailable")
    val value = "${fuelMoney(offer.price, offer.currency, 3)}/${offer.grade.unit}"
    return if (offer.fresh(now)) value else "$value · ${L10n.tr("Price may be outdated")}"
}

private fun difference(first: Double?, second: Double?): Double? =
    if (first != null && second != null) first - second else null

private fun signedDuration(minutes: Double): String =
    (if (minutes < -0.5) "−" else "") + planDuration(kotlin.math.abs(minutes))
