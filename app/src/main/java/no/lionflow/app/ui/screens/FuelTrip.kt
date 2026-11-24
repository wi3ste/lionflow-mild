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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.fuel.FuelCatalogState
import no.lionflow.app.fuel.FuelCandidate
import no.lionflow.app.fuel.FuelCheck
import no.lionflow.app.fuel.FuelCheckReason
import no.lionflow.app.fuel.FuelPlan
import no.lionflow.app.fuel.FuelConstraints
import no.lionflow.app.fuel.FuelConsumption
import no.lionflow.app.fuel.FuelGrade
import no.lionflow.app.fuel.FuelOffer
import no.lionflow.app.fuel.FuelPlanner
import no.lionflow.app.fuel.FuelProfile
import no.lionflow.app.fuel.FuelQuoteStats
import no.lionflow.app.fuel.FuelSearchRow
import no.lionflow.app.fuel.FuelSearchSummary
import no.lionflow.app.fuel.FuelServiceFilter
import no.lionflow.app.fuel.FuelStation
import no.lionflow.app.fuel.FuelStop
import no.lionflow.app.fuel.FuelStrategy
import no.lionflow.app.fuel.FuelTripRequest
import no.lionflow.app.fuel.FuelTripResult
import no.lionflow.app.fuel.defaultStartingFuel
import no.lionflow.app.fuel.defaultTankCapacity
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

private const val DEFAULT_RESERVE = "10"
private const val DEFAULT_MARGIN = "5"
private const val DEFAULT_DETOUR = "8"

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
    var initial by mutableStateOf<String?>(null)
    var percent by mutableStateOf(true)
    private val savedProfile = planner.setting(vin, "profile")?.let { name -> FuelProfile.entries.find { it.name == name } }
    private var profileExplicit = savedProfile != null
    var profile by mutableStateOf(savedProfile ?: defaultProfile)
        private set
    var reserve by mutableStateOf(planner.setting(vin, "reserve") ?: DEFAULT_RESERVE)
    var safety by mutableStateOf(planner.setting(vin, "safety") ?: DEFAULT_MARGIN)
    var detour by mutableStateOf(planner.setting(vin, "detour") ?: DEFAULT_DETOUR)
    var strategy by mutableStateOf(FuelStrategy.BALANCED)
    private fun gradeKey(name: String) = "$name.${planner.grade.name}"
    var capacity by mutableStateOf<String?>(null)
        private set
    var manual by mutableStateOf("")
        private set
    private val tankInputs = mutableMapOf<FuelGrade, Pair<String?, String>>()

    fun useGrade(grade: FuelGrade) {
        tankInputs[planner.grade] = capacity to manual
        if (grade != planner.grade) initial = null
        planner.grade = grade
        loadTank()
    }

    fun loadTank() {
        val draft = tankInputs[planner.grade]
        capacity = draft?.first ?: planner.setting(vin, gradeKey("capacity"))
        manual = draft?.second ?: planner.setting(vin, gradeKey("manual")).orEmpty()
    }

    fun setTank(value: String) { capacity = value }
    fun useSuggestedTank() { capacity = null }
    fun updateManual(value: String) { manual = value }
    fun useProfile(value: FuelProfile) { profileExplicit = true; profile = value }
    fun suggestProfile(value: FuelProfile) { if (!profileExplicit) profile = value }

    fun save() = planner.save(vin, mapOf(
        "grade" to planner.grade.name, "profile" to profile.name, "reserve" to reserve,
        "safety" to safety, "detour" to detour, gradeKey("capacity") to capacity.orEmpty(), gradeKey("manual") to manual,
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
    var submitted by remember(f) { mutableStateOf(false) }
    var showOptions by remember(f) { mutableStateOf(false) }
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
        Text("Required fields marked with *", style = Type.small, color = c.muted)
        for (i in listOf(0) + f.vias.indices.map { it + 2 } + 1) {
            val place = when (i) { 0 -> f.from ?: here; 1 -> f.to; else -> f.vias[i - 2] }
            AddressRow(
                if (i == 0) LfIcons.Car else LfIcons.Pin, c.accentInk,
                when (i) { 0 -> "From *"; 1 -> "Where to? *"; else -> "Optional stop" },
                place?.let(::placeName), field == i, query, requester, { query = it; addresses.search(it) },
                { open(i) }, { if (i >= 2 && place == null) f.vias = f.vias.filterIndexed { n, _ -> n != i - 2 }; dismiss() },
                onRemove = if (i >= 2) ({ f.vias = f.vias.filterIndexed { n, _ -> n != i - 2 }; dismiss() }) else null,
            )
            if (submitted && field < 0 && place == null && i < 2) {
                Text(if (i == 0) "Select a departure point" else "Select a destination", style = Type.small, color = c.danger)
            }
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
            FuelChoice(FuelGrade.entries, p.grade, FuelGrade::label, f::useGrade)
            FuelChoice(FuelProfile.entries, f.profile, FuelProfile::label, f::useProfile)
        }
        if (f.profile == FuelProfile.PLUG_IN) Text("Petrol or diesel range only", style = Type.small, color = c.muted)

        val grade = p.grade
        val consumption = remember(grade, f.profile, ui.trips, f.manual) {
            FuelConsumption.estimate(grade, f.profile, ui.trips, f.manual.fuelNumber()?.takeIf { it in 0.5..40.0 }, 0.0)
        }
        val modelTank = defaultTankCapacity(ui.vehicle, grade)
        val tankInput = f.capacity ?: modelTank?.let { fuelNumber(it, 0) }.orEmpty()
        val tank = tankInput.fuelNumber()?.takeIf { it in 1.0..300.0 }
        val suggestedLevel = defaultStartingFuel(ui.vehicle, ui.status, grade, tank, f.percent, consumption.per100)
        val levelInput = f.initial ?: suggestedLevel?.let { fuelNumber(it.value, 1) }.orEmpty()
        val initial = f.initial
        val level = (if (initial == null) suggestedLevel?.value else initial.fuelNumber())
            ?.takeIf { it >= 0 && (if (f.percent) it <= 100 else tank != null && it <= tank) }

        FuelNumber(
            L10n.tr("Tank capacity") + " (${grade.unit}) *", tankInput, f::setTank,
            hint = if (f.capacity == null && modelTank != null) "Capacity from vehicle specifications" else null,
            error = if (submitted && tank == null) "Enter a tank capacity between 1 and 300." else null,
        )
        if (modelTank != null && f.capacity != null && tank != modelTank) {
            TextButton(onClick = f::useSuggestedTank) {
                Text("Use vehicle tank capacity", style = Type.small, color = c.accentInk)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            FuelNumber(
                L10n.tr("Starting fuel") + " (${if (f.percent) "%" else grade.unit}) *", levelInput,
                { f.initial = it }, Modifier.weight(1f),
                hint = if (f.initial == null && suggestedLevel != null) {
                    if (suggestedLevel.fromGauge) "Starting level from the vehicle" else "Starting level estimated from reported range"
                } else null,
                error = when {
                    !submitted || level != null -> null
                    f.percent -> "Enter a starting level from 0 to 100%."
                    else -> "Enter an amount within the tank capacity."
                },
            )
            Box(Modifier.padding(top = 8.dp)) {
                FuelChoice(listOf(true, false), f.percent, { if (it) "%" else grade.unit }) { selected ->
                    if (selected != f.percent) {
                        f.initial = f.initial?.let { text ->
                            val current = text.fuelNumber()
                            if (current == null || tank == null) "" else {
                                val converted = fuelNumber(if (selected) current / tank * 100 else tank * (current / 100), 2)
                                if (!selected && current <= 100 && (converted.fuelNumber() ?: 0.0) > tank) tankInput else converted
                            }
                        }
                        f.percent = selected
                    }
                }
            }
        }
        if (f.initial != null && suggestedLevel != null) {
            TextButton(onClick = { f.initial = null }) {
                Text(if (suggestedLevel.fromGauge) "Use vehicle fuel level" else "Use estimated fuel level", style = Type.small, color = c.accentInk)
            }
        }

        val reserve = f.reserve.ifBlank { DEFAULT_RESERVE }.fuelNumber()?.takeIf { it in 0.0..90.0 }
        val safety = f.safety.ifBlank { DEFAULT_MARGIN }.fuelNumber()?.takeIf { it in 0.0..50.0 }
        val detour = f.detour.ifBlank { DEFAULT_DETOUR }.fuelNumber()?.takeIf { it in 0.5..30.0 }
        val manual = f.manual.fuelNumber()?.takeIf { it in 0.5..40.0 }
        val optionsValid = reserve != null && safety != null && detour != null && (f.manual.isBlank() || manual != null)
        SecondaryButton(if (showOptions) "Hide optional trip settings" else "Show optional trip settings") { showOptions = !showOptions }
        if (showOptions) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FuelChoice(listOf(false, true), f.round, { if (it) "Round trip" else "One way" }) { f.round = it }
                SecondaryButton(f.depart?.let { fuelDate(Instant.ofEpochMilli(it)) } ?: "Leave now") { picker = true }
            }
            FuelNumber(L10n.tr("Consumption override") + " (${grade.unit}/100 km)", f.manual, f::updateManual,
                hint = if (f.manual.isBlank()) "Consumption is estimated after routing." else null,
                error = if (submitted && f.manual.isNotBlank() && manual == null) "Enter a consumption between 0.5 and 40." else null)
            FuelNumber(L10n.tr("Minimum reserve") + " (%)", f.reserve, { f.reserve = it }, default = DEFAULT_RESERVE,
                error = if (submitted && reserve == null) "Enter a reserve from 0 to 90%." else null)
            FuelNumber(L10n.tr("Consumption safety margin") + " (%)", f.safety, { f.safety = it }, default = DEFAULT_MARGIN,
                error = if (submitted && safety == null) "Enter a margin from 0 to 50%." else null)
            FuelNumber(L10n.tr("Maximum detour per stop") + " (km)", f.detour, { f.detour = it }, default = DEFAULT_DETOUR,
                error = if (submitted && detour == null) "Enter a detour between 0.5 and 30 km." else null)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FuelChoice(FuelServiceFilter.entries, p.service, FuelServiceFilter::label) { p.service = it }
                FuelChoice(FuelStrategy.entries, f.strategy, FuelStrategy::label) { f.strategy = it }
            }
            if (f.vin.isNotBlank() && tank != null && optionsValid) SecondaryButton("Save vehicle fuel settings") {
                f.save()
                saved = true
            }
            if (saved) Text("Vehicle fuel settings saved", color = c.muted, style = Type.tiny)
        }

        PrimaryButton("Plan fuel trip", Modifier.fillMaxWidth(), icon = LfIcons.Route, enabled = p.busy == null) {
            val from = f.from ?: here?.let { position ->
                p.result?.request?.from?.takeIf { previous -> previous.name == position.name && previous.detail.isEmpty() &&
                    no.lionflow.app.plan.Geo.km(previous.lat, previous.lon, position.lat, position.lon) < 0.10 } ?: position
            }
            val to = f.to
            if (!optionsValid) showOptions = true
            if (from == null || to == null || tank == null || level == null || reserve == null || safety == null ||
                detour == null || !optionsValid) {
                submitted = true
                return@PrimaryButton
            }
            submitted = false
            focus.clearFocus()
            f.save()
            val startingAmount = if (f.percent) tank * (level / 100) else level
            p.calculate(FuelTripRequest(from, to, f.vias.filterNotNull(), f.round,
                f.depart?.let(Instant::ofEpochMilli)?.takeIf { it > Instant.now() } ?: Instant.now(), grade, p.service,
                f.profile, manual, safety, FuelConstraints(tank, startingAmount, tank * reserve / 100, f.strategy), detour))
        }
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
private fun FuelNumber(
    label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier,
    hint: String? = null, error: String? = null, default: String? = null,
) {
    val c = LocalLf.current
    OutlinedTextField(
        value, { change(it.take(9)) }, modifier = modifier.fillMaxWidth(), singleLine = true,
        textStyle = Type.body.copy(color = c.text), label = { androidx.compose.material3.Text(label, color = c.muted, style = Type.small) },
        isError = error != null, shape = RoundedCornerShape(14.dp),
        supportingText = when {
            error != null -> { { Text(error, color = c.danger, style = Type.small) } }
            hint != null -> { { Text(hint, color = c.muted, style = Type.small) } }
            default != null && value.isBlank() -> { { Text("Default: $default", color = c.muted, style = Type.small) } }
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun String.fuelNumber(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)
internal fun fuelNumber(value: Double, digits: Int) = NumberFormat.getNumberInstance(L10n.locale).apply {
    minimumFractionDigits = digits
    maximumFractionDigits = digits
}.formatFuelValue(value)
internal fun fuelMoney(value: Double?, currency: String = "EUR", digits: Int = 2): String = if (value == null) L10n.tr("Unknown") else
    NumberFormat.getCurrencyInstance(L10n.locale).apply {
        this.currency = Currency.getInstance(currency)
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }.formatFuelValue(value)
private fun NumberFormat.formatFuelValue(value: Double): String {
    val formatted = format(value)
    return if (value.toRawBits() < 0 && formatted == format(-0.0)) format(0.0) else formatted
}
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
internal fun FuelProgress(p: FuelPlanner, showExtension: Boolean = true) {
    val c = LocalLf.current
    p.busy?.let {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Busy(); Text(it, style = Type.small, color = c.muted) }
        if (p.result?.plan != null) Text("Verified plan · still optimizing", style = Type.small, color = c.muted)
        SecondaryButton(if (p.result?.plan != null) "Keep this plan" else "Cancel calculation") { p.cancel() }
    }
    if (showExtension) FuelExtensionStatus(p)
    p.error?.let { Text(it, style = Type.small, color = c.danger) }
    val result = p.result
    if (no.lionflow.app.BuildConfig.DEBUG && result != null && result.plan == null && result.search.corridor > 0) {
        var showDiagnostics by remember { mutableStateOf(false) }
        TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
            Text(if (showDiagnostics) "Hide diagnostics" else "Show diagnostics", color = c.accentInk)
        }
        if (showDiagnostics) FuelSearchDiagnostics(result.search, settings = null)
    }
}

@Composable
private fun FuelExtensionStatus(p: FuelPlanner) {
    p.extensionProgress?.let { progress ->
        FuelMetric("Additional stations checked", "${progress.examined} / ${progress.planned - progress.selectedRetries}")
        if (progress.selectedRetries > 0) FuelMetric("Stations retried", "${progress.retried} / ${progress.selectedRetries}")
        FuelMetric("Stations accepted", progress.accepted.toString())
    }
    p.extensionMessage?.let { Text(it, style = Type.small, color = LocalLf.current.muted) }
}

private val FuelSearchRow.reason: FuelCheckReason get() = when (val outcome = check) {
    is FuelCheck.Verified -> FuelCheckReason.ROUTED
    is FuelCheck.Rejected -> outcome.reason
    is FuelCheck.Deferred -> outcome.reason
}

@Composable
private fun FuelSearchDiagnostics(search: FuelSearchSummary, settings: AppSettings?) {
    val c = LocalLf.current
    var showCandidates by remember { mutableStateOf(false) }
    fun quotes(stats: FuelQuoteStats?) = stats?.let {
        listOf(it.low, it.median, it.high).joinToString(" / ") { quote -> fuelMoney(quote, digits = 3) }
    } ?: L10n.tr("Unknown")
    fun distance(km: Double) = if (settings != null) fmtDistance(km, settings) else "${fuelNumber(km, 1)} km"

    FuelMetric("Stations in corridor", search.corridor.toString())
    FuelMetric("Corridor quotes (min / median / max)", quotes(search.corridorQuotes))
    FuelMetric("Shortlisted quotes (min / median / max)", quotes(search.queuedQuotes))
    FuelMetric("Verified quotes (min / median / max)", quotes(search.verifiedQuotes))
    FuelMetric("Stations verified / examined", "${search.verified} / ${search.examined}")
    FuelMetric("Stations remaining to examine", search.unexamined.toString())
    FuelMetric("Fresh prices at verified stations", search.fresh.toString())
    FuelMetric("Itinerary routing requests", search.routeRequests.toString())
    FuelMetric("Cached itinerary route responses", search.routeCacheHits.toString())
    FuelMetric("Station routing requests", search.requests.toString())
    FuelMetric("Cached station route responses", search.cacheHits.toString())
    val reasons = search.rows.filter { it.check !is FuelCheck.Verified }.groupingBy { it.reason }.eachCount()
    if (reasons.isNotEmpty()) {
        Text("Verification outcomes", style = Type.label, color = c.text)
        reasons.toSortedMap().forEach { (reason, count) -> FuelMetric(reason.label, count.toString()) }
    }
    val promising = search.rows.filter { it.check !is FuelCheck.Verified && (it.choice.potential ?: 0.0) > 0.0 }
        .sortedByDescending { it.choice.potential }.take(4)
    if (promising.isNotEmpty()) {
        Text("Promising stations skipped", style = Type.label, color = c.text)
        promising.forEach { row ->
            androidx.compose.material3.Text(stationName(row.choice.projection.station), style = Type.small, color = c.text)
            FuelMetric(row.reason.label, fuelMoney(row.choice.projection.offer.price, digits = 3))
        }
    }
    if (search.rows.isNotEmpty()) TextButton(onClick = { showCandidates = true }) {
        Text("Candidate verification details", color = c.accentInk)
    }
    if (showCandidates) AlertDialog(
        onDismissRequest = { showCandidates = false }, containerColor = c.card,
        title = { Text("Candidate verification details", color = c.text) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(search.rows, key = { it.choice.projection.key }) { row ->
                    val projection = row.choice.projection
                    val extra = when (val outcome = row.check) {
                        is FuelCheck.Verified -> outcome.candidate.detourKm
                        is FuelCheck.Rejected -> outcome.netKm
                        is FuelCheck.Deferred -> null
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        androidx.compose.material3.Text(stationName(projection.station), style = Type.label, color = c.text)
                        FuelMetric("Position along the trip", distance(projection.roadKm))
                        FuelMetric("Unit price", fuelMoney(projection.offer.price, projection.offer.currency, 3))
                        FuelMetric("Straight-line distance", distance(projection.straightKm))
                        FuelMetric("Added road distance", extra?.let(::distance) ?: L10n.tr("Unknown"))
                        Text(row.reason.label, style = Type.small, color = c.muted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showCandidates = false }) { Text("Close", color = c.accentInk) } },
    )
}

@Composable
internal fun FuelTripResultPanel(
    p: FuelPlanner,
    result: FuelTripResult,
    settings: AppSettings,
    catalog: FuelCatalogState,
    newHistory: Boolean,
    clickStation: (FuelStation) -> Unit,
) {
    val plan = result.plan ?: return
    val c = LocalLf.current
    val unit = result.request.grade.unit
    var editingStop by remember(result) { mutableStateOf<FuelStop?>(null) }
    var showComparison by remember { mutableStateOf(false) }
    var tripDetails by remember(result.road) { mutableStateOf(false) }
    val strategy = result.request.constraints.strategy
    fun quantity(value: Double) = "${fuelNumber(value, 2)} $unit"
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
                            FuelMetric("Net fuel used by detours", quantity(result.expectedDetourFuel(comparison)))
                            FuelMetric("Estimated value of detour fuel", fuelMoney(result.expectedDetourValue(comparison)))
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p.busy != null || p.error != null) FuelProgress(p, showExtension = false)
        FuelMetric("Total distance", fmtDistance(plan.totalKm, settings))
        FuelMetric("Estimated total time", planDuration(plan.totalMin))
        FuelMetric("Estimated spend on refuelling", fuelMoney(plan.purchaseCost))
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { tripDetails = !tripDetails }.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (tripDetails) "Hide trip details" else "Show trip details",
                Modifier.weight(1f), style = Type.label, color = c.accentInk)
            FuelDisclosureChevron(tripDetails)
        }
        if (tripDetails) {
            FuelTripDetails(p, result, plan, settings, catalog, newHistory) { showComparison = true }
        }

        if (plan.stops.isEmpty()) Text("No refuelling needed", color = c.muted, style = Type.small)
        else FuelMetric("Fuel stops", plan.stops.size.toString())
        for ((index, stop) in plan.stops.withIndex()) key(result.road, stop.candidate.key) {
            FuelStopCard(p, result, stop, index, settings, clickStation) { editingStop = stop }
        }
        if (p.busy == null) TextButton(onClick = { p.editing = true }) {
            Text("Edit trip", color = c.accentInk)
        }
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
                                FuelMetric("Change in detour fuel", quantity(result.expectedDetourFuel(revised) - result.expectedDetourFuel(plan)))
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

@Composable
private fun FuelDisclosureChevron(expanded: Boolean) {
    LfIcon(LfIcons.Chevron, LocalLf.current.accentInk, 18.dp, modifier = Modifier.rotate(if (expanded) -90f else 90f))
}

@Composable
private fun FuelTripDetails(
    p: FuelPlanner,
    result: FuelTripResult,
    plan: FuelPlan,
    settings: AppSettings,
    catalog: FuelCatalogState,
    newHistory: Boolean,
    compare: () -> Unit,
) {
    val c = LocalLf.current
    val prediction = result.prediction
    val unit = result.request.grade.unit
    val expected = result.expectedUsed(plan)
    fun quantity(value: Double) = "${fuelNumber(value, 2)} $unit"
    var showSamples by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FuelMetric("Selected strategy", L10n.tr(result.request.constraints.strategy.label))
        SecondaryButton("Compare fuel strategies", onClick = compare)
        FuelMetric("Driving time", planDuration(plan.driveMin))
        FuelMetric("Expected average consumption", "${fuelNumber(expected * 100 / plan.totalKm, 1)} $unit/100 km")
        FuelMetric("Fuel consumed", quantity(expected))
        FuelMetric("Estimated value of fuel consumed", fuelMoney(result.expectedCost(plan)))
        FuelMetric("Fuel to buy", quantity(plan.purchased))
        FuelMetric("Fuel at arrival", quantity(plan.remaining))
        if (plan.stops.isNotEmpty()) {
            FuelMetric("Extra distance", fmtDistance(plan.detourKm, settings))
            FuelMetric("Net fuel used by detours", quantity(result.expectedDetourFuel(plan)))
            FuelMetric("Estimated value of detour fuel", fuelMoney(result.expectedDetourValue(plan)))
            FuelMetric("Extra time for fuel stops", signedDuration(plan.extraMin))
        }
        FuelMetric("Prediction source", L10n.tr(prediction.source.label))
        FuelMetric("Consumption safety margin", "${fuelNumber(prediction.safety, 0)}%")
        FuelMetric("Prudent fuel estimate for reserve", quantity(plan.used))
        FuelMetric("Minimum reserve", quantity(result.request.constraints.reserve))
        if (prediction.samples.isNotEmpty()) {
            TextButton(onClick = { showSamples = !showSamples }) {
                Text(if (showSamples) "Hide history" else "Show history", color = c.accentInk)
            }
            if (showSamples) {
                FuelMetric("Historical trips used", prediction.samples.size.toString())
                FuelMetric("Historical distance", fmtDistance(prediction.sampleKm, settings))
                if (prediction.samples.size < 3) Text("Limited historical support", style = Type.tiny, color = c.muted)
                prediction.samples.take(8).forEach { sample ->
                    FuelMetric(fmtDistance(sample.journey.km, settings) + " · " + planDuration(sample.journey.minutes),
                        "${fuelNumber(sample.rate, 1)} $unit/100 km")
                }
            }
        }
        if (newHistory && p.busy == null) SecondaryButton("Recalculate with updated history") { p.recalculateConsumption() }
        if (result.limited) {
            Text("Plan based on a limited set of road-verified stations.", color = c.muted, style = Type.tiny)
            if (p.busy == null && result.search.canExtend) SecondaryButton("Check more stations") { p.searchMore() }
        }
        FuelExtensionStatus(p)
        if (plan.stops.isNotEmpty() && p.busy == null) SecondaryButton("Reset fuel stops") { p.resetStops() }
        if (no.lionflow.app.BuildConfig.DEBUG) {
            TextButton(onClick = { showDiagnostics = !showDiagnostics; if (showDiagnostics) p.diagnoseConsumption() }) {
                Text(if (showDiagnostics) "Hide diagnostics" else "Show diagnostics", color = c.accentInk)
            }
            if (showDiagnostics) {
                FuelSearchDiagnostics(result.search, settings)
                FuelMetric("Maximum detour per stop", fmtDistance(result.request.maxDetour, settings))
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
        FuelSyncStatus(catalog) { p.refresh(force = true) }
    }
}

@Composable
private fun FuelStopCard(
    p: FuelPlanner,
    result: FuelTripResult,
    stop: FuelStop,
    index: Int,
    settings: AppSettings,
    clickStation: (FuelStation) -> Unit,
    editStop: () -> Unit,
) {
    val c = LocalLf.current
    val platform = LocalPlatform.current
    val station = stop.candidate.station
    val offer = stop.candidate.offer
    val locked = stop.candidate.key in p.locked
    val shape = RoundedCornerShape(14.dp)
    var expanded by remember { mutableStateOf(false) }
    fun quantity(value: Double) = "${fuelNumber(value, 2)} ${result.request.grade.unit}"

    Column(Modifier.fillMaxWidth().clip(shape).background(c.raised), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(
                role = Role.Button,
                onClickLabel = L10n.tr(if (expanded) "Hide stop details" else "Show stop details"),
            ) { expanded = !expanded }.heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${index + 1}", style = Type.stat, color = c.accentInk)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                androidx.compose.material3.Text(stationName(station), style = Type.label, color = c.text)
                Text("Buy ${quantity(stop.purchased)}", style = Type.small, color = c.muted)
            }
            if (locked) Text("Locked", color = c.accentInk, style = Type.tiny)
            FuelDisclosureChevron(expanded)
        }
        if (expanded) Column(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            androidx.compose.material3.Text(station.location, style = Type.small, color = c.muted)
            FuelMetric("Position along the trip", fmtDistance(stop.candidate.projection.roadKm, settings))
            FuelMetric("Estimated arrival", fuelDate(result.request.departure.plusSeconds((stop.atMinute * 60).toLong())))
            FuelMetric("Fuel at arrival", quantity(stop.arrival))
            FuelMetric("Fuel after refuelling", quantity(stop.departure))
            Text(when {
                locked -> "Station kept by your edit"
                result.request.constraints.strategy == FuelStrategy.ECONOMY -> "Part of the lowest estimated refuelling cost found"
                result.request.constraints.strategy == FuelStrategy.FAST -> "Part of the fastest feasible plan found"
                else -> "Part of the best cost and time trade-off found"
            }, color = c.muted, style = Type.tiny)
            FuelMetric("Unit price", fuelPrice(offer, result.priceTime))
            FuelMetric("Refuelling cost", fuelMoney(stop.cost, offer.currency))
            FuelMetric("Road detour", "${fmtDistance(stop.candidate.detourKm, settings)} · ${signedDuration(stop.candidate.detourMin)}")
            FuelMetric("Net fuel used by this detour", quantity(result.expectedDetourFuel(stop)))
            FuelMetric("Estimated value of detour fuel", fuelMoney(result.expectedDetourValue(stop), offer.currency))
            FuelMetric("Service", offer.service?.label?.let(L10n::tr) ?: L10n.tr("Unknown"))
            FuelMetric("Price reported", offer.reportedAt?.let(::fuelDate) ?: L10n.tr("Unknown"))
            TextButton(onClick = { clickStation(station) }) { Text("Show station on map", color = c.accentInk) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.busy == null) SecondaryButton("Edit fuel stop", onClick = editStop)
                SecondaryButton("Navigate") { platform.openUrl("google.navigation:q=${station.lat},${station.lon}") }
            }
        }
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
