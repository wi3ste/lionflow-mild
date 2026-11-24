package no.lionflow.app.fuel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import no.lionflow.app.LionFlowApp
import no.lionflow.app.plan.Geo
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.PlanApi
import no.lionflow.app.plan.RouteData
import java.io.File
import java.time.Instant
import no.lionflow.app.model.Trip
import no.lionflow.app.route.Route
import no.lionflow.app.route.Routes
import kotlin.math.abs
import java.util.Locale
import no.lionflow.app.BuildConfig
import android.util.Log

internal data class FuelTripRequest(
    val from: Place,
    val to: Place,
    val via: List<Place>,
    val round: Boolean,
    val departure: Instant,
    val grade: FuelGrade,
    val service: FuelServiceFilter,
    val profile: FuelProfile,
    val manualConsumption: Double?,
    val safety: Double,
    val constraints: FuelConstraints,
    val maxDetour: Double,
)

internal data class FuelTripResult(
    val request: FuelTripRequest,
    val road: RouteData,
    val roadFuel: Double,
    val candidates: List<FuelCandidate>,
    val reference: Double?,
    val priceTime: Instant,
    val limited: Boolean,
    val plan: FuelPlan?,
    val prediction: FuelConsumption,
    val search: FuelSearchSummary,
    val comparisons: Map<FuelStrategy, FuelPlan> = emptyMap(),
) {
    fun expectedUsed(plan: FuelPlan) = plan.used / prediction.safetyFactor
    fun expectedCost(plan: FuelPlan) =
        FuelOptimizer.consumedCost(expectedUsed(plan), request.constraints.initial, reference, plan.stops)

    fun expectedDetourFuel(stop: FuelStop) = stop.candidate.detourFuel / prediction.safetyFactor
    fun expectedDetourFuel(plan: FuelPlan) = plan.detourFuel / prediction.safetyFactor

    fun expectedDetourValue(stop: FuelStop): Double? {
        val fuel = expectedDetourFuel(stop)
        return if (kotlin.math.abs(fuel) < 1e-7) 0.0 else (stop.candidate.offer.price ?: reference)?.times(fuel)
    }

    fun expectedDetourValue(plan: FuelPlan): Double? {
        var total = 0.0
        for (stop in plan.stops) total += expectedDetourValue(stop) ?: return null
        return total
    }

    fun navigationUrl(): String {
        val origin = request.from
        val destination = if (request.round) origin else request.to
        val vias = request.via + listOfNotNull(request.to.takeIf { request.round })
        val points = vias.map { place -> road.km[Geo.project(road, place.lat, place.lon).first] to "${place.lat},${place.lon}" }
        val stops = plan?.stops.orEmpty().map { stop ->
            val station = stop.candidate.station
            stop.candidate.projection.roadKm to "${station.lat},${station.lon}"
        }
        val waypoints = (points + stops).sortedBy { it.first }.joinToString("%7C") { it.second }
        return "https://www.google.com/maps/dir/?api=1&travelmode=driving&origin=${origin.lat},${origin.lon}" +
            "&destination=${destination.lat},${destination.lon}" + if (waypoints.isNotEmpty()) "&waypoints=$waypoints" else ""
    }
}

internal data class FuelSearchSummary(
    val corridor: Int,
    val verified: Int,
    val examined: Int,
    val fresh: Int,
    val canExtend: Boolean,
    val corridorQuotes: FuelQuoteStats? = null,
    val queuedQuotes: FuelQuoteStats? = null,
    val verifiedQuotes: FuelQuoteStats? = null,
    val unexamined: Int = 0,
    val requests: Int = 0,
    val cacheHits: Int = 0,
    val routeRequests: Int = 0,
    val routeCacheHits: Int = 0,
    val rows: List<FuelSearchRow> = emptyList(),
)

internal data class FuelHistoryInput(val trips: List<Trip>, val routes: List<Route>, val loading: Boolean)

internal class FuelPlanner(
    private val scope: CoroutineScope,
    private val app: LionFlowApp,
    private val history: () -> FuelHistoryInput,
) {
    val catalog = FuelCatalog.get(app)
    var open by mutableStateOf(false)
    var editing by mutableStateOf(true)
    var grade by mutableStateOf(FuelGrade.PETROL)
    var service by mutableStateOf(FuelServiceFilter.ANY)
    var result by mutableStateOf<FuelTripResult?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var locked by mutableStateOf<Map<String, FuelStopEdit>>(emptyMap())
        private set
    private var excluded = emptySet<String>()
    private var work: Job? = null
    private var vehicleKey: String? = null
    private data class SearchKey(val route: String, val grade: FuelGrade, val service: FuelServiceFilter, val detour: Double)
    private val routingCache = FuelRoutingCache(File(app.noBackupFilesDir, "fuel-routing"))
    private val verifier = FuelRoadVerifier(disk = routingCache)
    private data class SearchContext(
        val search: FuelStationSearch, val fuel: DoubleArray, val windows: FuelAccessWindows,
    )
    private var context: SearchContext? = null
    private var lastSearch: SearchKey? = null
    private var lastTrips: List<Trip> = emptyList()
    private var lastRoutes: List<Route> = emptyList()
    var extensionProgress by mutableStateOf<FuelExtensionProgress?>(null)
        private set
    var extensionMessage by mutableStateOf<String?>(null)
        private set
    var backtest by mutableStateOf<List<FuelBacktest>?>(null)
        private set

    fun selectStrategy(strategy: FuelStrategy) {
        val current = result ?: return
        result = current.withStrategy(strategy)
    }

    suspend fun alternatives(stop: FuelStop): List<Pair<FuelCandidate, FuelPlan>> {
        val current = result ?: return emptyList()
        val taken = current.plan?.stops.orEmpty().map { it.candidate.key }.toSet()
        val possible = current.candidates.asSequence()
            .filter { it.key !in taken && it.station.id != stop.candidate.station.id }
            .sortedBy { kotlin.math.abs(it.projection.roadKm - stop.candidate.projection.roadKm) }
            .distinctBy { it.station.id }.take(8).toList()
        val stationLocks = locked - stop.candidate.key
        val omissions = excluded + stop.candidate.key
        return withContext(Dispatchers.Default) {
            possible.mapNotNull { candidate ->
                FuelOptimizer.plan(current.roadFuel, current.road.totalKm, current.road.totalMin, current.request.constraints,
                    current.candidates, current.priceTime, current.reference, stationLocks + (candidate.key to FuelStopEdit()),
                    omissions - candidate.key)?.let { candidate to it }
            }.sortedWith { first, second ->
                when {
                    first.second.isBetterThan(second.second) -> -1
                    second.second.isBetterThan(first.second) -> 1
                    else -> 0
                }
            }.take(4)
        }
    }

    fun selectVehicle(vin: String, motorization: String) {
        if (vehicleKey == vin) return
        close()
        result = null
        error = null
        vehicleKey = vin
        lastSearch = null
        context = null
        lastTrips = emptyList()
        lastRoutes = emptyList()
        backtest = null
        grade = setting(vin, "grade")?.let { runCatching { FuelGrade.valueOf(it) }.getOrNull() }
            ?: FuelGrade.from(motorization)
            ?: when {
                "diesel" in motorization.lowercase(Locale.ROOT) -> FuelGrade.DIESEL
                else -> FuelGrade.PETROL
            }
    }

    fun setting(vin: String, name: String): String? =
        if (vin.isBlank()) null else app.settings.raw("fuel.$name.$vin").takeIf(String::isNotBlank)

    fun save(vin: String, values: Map<String, String>) {
        if (vin.isNotBlank()) values.forEach { (name, value) -> app.settings.setRaw("fuel.$name.$vin", value) }
    }

    fun refresh(force: Boolean = false) {
        FuelCatalog.schedule(app)
        scope.launch { catalog.refresh(force) }
    }

    fun cancel() {
        work?.cancel()
        if (busy == "Checking additional stations…") extensionMessage = "Additional station check cancelled"
        busy = null
        if (result?.plan == null) editing = true
    }

    fun close() {
        cancel()
        open = false
        editing = true
    }

    fun searchMore() {
        if (busy != null) return
        val previous = result ?: return
        val base = context ?: return
        extensionMessage = null
        extensionProgress = null
        busy = "Checking additional stations…"
        work = scope.launch {
            try {
                val now = Instant.now()
                val active = if (base.search.corridorSize == 0 && previous.plan == null) {
                    val index = (catalog.state.value.snapshot ?: catalog.refresh().snapshot)?.index
                    val projections = withContext(Dispatchers.Default) {
                        index?.let {
                            FuelCorridor.find(previous.road, base.fuel, it, previous.request.grade, previous.request.service,
                                corridorWidth(previous.request.maxDetour), now)
                        }.orEmpty()
                    }
                    base.copy(search = FuelStationSearch(projections, previous.road.totalKm)).also { context = it }
                } else base
                val search = active.search
                val batch = withContext(Dispatchers.Default) {
                    search.extension(previous.request.constraints, previous.roadFuel,
                        previous.comparisons[FuelStrategy.ECONOMY] ?: previous.plan, now)
                }
                val budget = FuelProbeBudget(14)
                val limit = previous.request.maxDetour
                val progress = search.check(batch, budget, Instant::now, { projection ->
                    verifier.verify(projection, active.windows.forStation(projection), previous.prediction, limit, budget)
                }) { extensionProgress = it }
                val candidates = search.candidates
                val reference = previous.reference ?: search.referencePrice(now)
                val revised = if (candidates.size > previous.candidates.size) optimize(previous.road, previous.roadFuel, previous.request, candidates,
                    previous.priceTime, reference, locked, excluded) else emptyMap()
                val comparisons = betterFuelPlans(previous.comparisons, revised)
                val current = result ?: previous
                val summary = search.summary(Instant.now()).copy(routeRequests = previous.search.routeRequests,
                    routeCacheHits = previous.search.routeCacheHits)
                result = current.copy(candidates = candidates, comparisons = comparisons,
                    plan = comparisons[current.request.constraints.strategy] ?: current.plan,
                    reference = reference, search = summary,
                    limited = summary.canExtend || summary.verified < summary.corridor)
                extensionMessage = when {
                    comparisons != previous.comparisons -> "Better fuel options found"
                    batch.isEmpty() && !search.hasMore -> "No more stations in the corridor"
                    verifier.throttled -> "Waiting for the router retry window"
                    progress.examined == 0 && progress.retried == 0 -> "Waiting for the router retry window"
                    else -> "No better plan found in this group"
                }
                if (result?.plan != null) error = null
                if (BuildConfig.DEBUG) Log.d("FuelPlanner", "extension=$progress requests=${budget.requests} hits=${budget.hits} " +
                    "verified=${summary.verified} unexamined=${summary.unexamined}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w("FuelPlanner", "Extension failed", e)
                extensionMessage = "Could not finish checking additional stations"
            }
            busy = null
        }
    }

    fun historyChanged(trips: List<Trip>) = result?.request?.manualConsumption == null && trips != lastTrips

    fun recalculateConsumption() {
        result?.request?.let { calculate(it) }
    }

    fun diagnoseConsumption() {
        val request = result?.request ?: return
        val trips = lastTrips
        val local = lastRoutes
        val vin = vehicleKey
        backtest = null
        scope.launch {
            val calibration = calibration(request, trips, local)
            val scores = withContext(Dispatchers.Default) { FuelConsumption.backtest(request.grade, request.profile, calibration) }
            if (vehicleKey == vin && result?.request == request) backtest = scores
        }
    }

    fun calculate(request: FuelTripRequest) {
        work?.cancel()
        val end = if (request.round) request.from else request.to
        val via = if (request.round) request.via + request.to else request.via
        val routeKey = FuelRoutingCache.routeKey(listOf(request.from) + via + end)
        val key = SearchKey(routeKey, request.grade, request.service, request.maxDetour)
        val reuse = key == lastSearch
        if (!reuse) {
            locked = emptyMap()
            excluded = emptySet()
            context = null
        }
        extensionProgress = null
        extensionMessage = null
        val constraintsLocked = locked
        val exclusions = excluded
        val previous = result?.takeIf { reuse }
        result = null
        backtest = null
        error = null
        busy = "Routing the fuel trip…"
        editing = false
        work = scope.launch {
            val started = System.nanoTime()
            val timings = linkedMapOf<String, Long>()
            suspend fun <T> timed(phase: String, block: suspend () -> T): T {
                val start = System.nanoTime()
                return block().also { timings[phase] = (timings[phase] ?: 0) + (System.nanoTime() - start) / 1_000_000 }
            }
            try {
                val now = Instant.now()
                val cachedRoad = withContext(Dispatchers.IO) { routingCache.route(routeKey, now) }
                var routeRequests = 0
                val road = timed("route") {
                    cachedRoad ?: PlanApi.fuelRoute(request.from, end, via) { routeRequests++ }.also {
                        withContext(Dispatchers.IO) { routingCache.saveRoute(routeKey, it, now) }
                    }
                }
                val cachedSearch = context?.search?.takeIf { reuse && cachedRoad != null }
                val cacheOnly = cachedSearch != null
                val prediction = timed("consumption") {
                    if (request.manualConsumption == null && history().loading && history().trips.none { it.calibrationLPer100 != null }) {
                        busy = "Waiting briefly for driving history…"
                        withTimeoutOrNull(2500) { while (history().loading && history().trips.none { it.calibrationLPer100 != null }) delay(150) }
                    }
                    val input = history()
                    lastTrips = input.trips
                    lastRoutes = input.routes
                    val samples = if (request.manualConsumption == null) calibration(request, input.trips, input.routes) else emptyList()
                    withContext(Dispatchers.Default) {
                        FuelConsumption.predict(request.grade, request.profile, samples, request.manualConsumption, request.safety,
                            FuelJourney.fromRoute(road), samples.filter { it.matches(road) }.map { it.trip.id }.toSet())
                    }
                }
                val fuel = withContext(Dispatchers.Default) { prediction.forRoute(road) }
                if (fuel.isEmpty()) error("Empty fuel route")
                val required = fuel.last()
                val direct = request.constraints.initial - required >= request.constraints.reserve && constraintsLocked.isEmpty()
                busy = "Checking fuel stations…"
                val snapshot = timed("catalog") {
                    catalog.state.value.snapshot ?: if (direct || cacheOnly) null else catalog.refresh().snapshot
                }
                val stations = snapshot?.index
                val search = timed("corridor") {
                    withContext(Dispatchers.Default) {
                        cachedSearch?.rebased(road, fuel, prediction, request.maxDetour) ?: FuelStationSearch(
                            if (stations == null || direct) emptyList() else FuelCorridor.find(
                                road, fuel, stations, request.grade, request.service, corridorWidth(request.maxDetour), now,
                            ),
                            road.totalKm,
                        )
                    }
                }
                val reference = timed("prices") {
                    previous?.reference?.takeIf { cacheOnly } ?: search.referencePrice(now) ?: withContext(Dispatchers.Default) {
                        val national = stations?.stations.orEmpty().mapNotNull {
                            it.offer(request.grade, request.service, now)?.takeIf { offer -> offer.fresh(now) }?.price
                        }
                        FuelQuoteStats.of(national)?.median
                    }
                }
                val windows = FuelAccessWindows(road, fuel, protectedFuelKm(road, via), request.maxDetour)
                context = SearchContext(search, fuel, windows)
                lastSearch = key
                val shortlist = timed("shortlist") {
                    if (direct || cacheOnly) emptyList() else withContext(Dispatchers.Default) {
                        search.automatic(request.constraints, required, now)
                    }
                }
                val budget = FuelProbeBudget(16)
                var firstPlan: Long? = null
                suspend fun wave(batch: List<FuelSearchChoice>): Map<FuelStrategy, Double> {
                    timed("verify") {
                        search.check(batch, budget, Instant::now, { projection ->
                            verifier.verify(projection, windows.forStation(projection), prediction, request.maxDetour, budget)
                        }) { progress ->
                            busy = "Checking fuel stations… ${progress.examined}/${progress.planned}"
                        }
                    }
                    val verified = search.candidates
                    val existing = result
                    val revised = timed("solve") {
                        optimize(road, required, request, verified, now, reference, constraintsLocked, exclusions)
                    }
                    val comparisons = betterFuelPlans(existing?.comparisons.orEmpty(), revised)
                    val selected = result?.request?.constraints?.strategy ?: request.constraints.strategy
                    val active = request.copy(constraints = request.constraints.copy(strategy = selected))
                    val plan = comparisons[selected]
                    timed("publish") {
                        val summary = search.summary(now)
                        // An explicit check can load the corridor after a previously direct trip.
                        val canExtend = !direct && (summary.canExtend || (cacheOnly && plan == null && summary.corridor == 0))
                        result = FuelTripResult(active, road, required, verified, reference, now,
                            !direct && (canExtend || summary.verified < summary.corridor), plan, prediction,
                            summary.copy(canExtend = canExtend, routeRequests = routeRequests, routeCacheHits = if (cachedRoad != null) 1 else 0), comparisons)
                        if (plan != null && firstPlan == null) firstPlan = (System.nanoTime() - started) / 1_000_000
                    }
                    return comparisons.mapValues { it.value.score }
                }
                if (cacheOnly) wave(emptyList()) else searchFuelWaves(shortlist, initialWave = 1, stop = {
                    budget.exhausted || verifier.throttled
                }, evaluate = ::wave)
                if (BuildConfig.DEBUG) Log.d("FuelPlanner", "timingMs=$timings roadHit=${cachedRoad != null} cacheOnly=$cacheOnly " +
                    "firstPlanMs=$firstPlan accessRequests=${budget.requests} hits=${budget.hits} " +
                    "corridor=${search.corridorSize} verified=${search.candidates.size}")
                if (result?.plan == null) error = when {
                    cacheOnly -> "The cached stations cannot cover the new fuel settings."
                    stations == null && !direct -> "Fuel data unavailable. Refresh stations and try again."
                    search.corridorSize == 0 -> "No compatible fuel stations found along this route."
                    else -> "No feasible fuel plan found. Check your fuel, reserve and detour limit."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: PlanApi.FuelRoutingRateLimited) {
                error = "Waiting for the router retry window"
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w("FuelPlanner", "Could not calculate the fuel trip", e)
                error = "Could not calculate the road route. Check your connection and try again."
            }
            busy = null
        }
    }

    private suspend fun calibration(request: FuelTripRequest, trips: List<Trip>, local: List<Route>): List<FuelCalibration> =
        withContext(Dispatchers.IO) {
            FuelConsumption.samples(request.grade, request.profile, trips).map { sample ->
                val trip = sample.trip
                val start = trip.startMillis ?: return@map sample
                val end = trip.endMillis ?: return@map sample
                val trace = Routes.forTrip(local, start, end) ?: return@map sample
                val overlap = minOf(end, trace.endMillis) - maxOf(start, trace.startMillis)
                if (end <= start || overlap < (end - start) * 0.85 || abs(trace.km - trip.distanceKm) > trip.distanceKm * 0.15) return@map sample
                val points = Routes.points(trace.file)
                if (points.size < 5 || points.any { it.first !in -90.0..90.0 || it.second !in -180.0..180.0 }) return@map sample
                val ends = trip.calibrationEnds
                if (ends != null && (Geo.km(ends.aLat, ends.aLon, points.first().first, points.first().second) > 2 ||
                    Geo.km(ends.bLat, ends.bLon, points.last().first, points.last().second) > 2)) return@map sample
                val km = DoubleArray(points.size)
                var fast = 0.0
                for (i in 1 until points.size) {
                    val a = points[i - 1]
                    val b = points[i]
                    val segment = Geo.km(a.first, a.second, b.first, b.second)
                    val seconds = (b.third - a.third) / 1000.0
                    if (seconds <= 0 || segment * 3600 / seconds > 185) return@map sample
                    if (segment * 3600 / seconds >= 95) fast += segment
                    km[i] = km[i - 1] + segment
                }
                if (km.last() <= 0 || abs(km.last() - trip.distanceKm) > trip.distanceKm * 0.20) return@map sample
                val checkpoints = (0..8).map { step ->
                    val target = km.last() * step / 8
                    val found = km.binarySearch(target)
                    val index = (if (found >= 0) found else -found - 1).coerceIn(1, points.lastIndex)
                    val alpha = ((target - km[index - 1]) / (km[index] - km[index - 1])).takeIf { it.isFinite() } ?: 0.0
                    val a = points[index - 1]
                    val b = points[index]
                    (a.first + (b.first - a.first) * alpha) to (a.second + (b.second - a.second) * alpha)
                }
                sample.copy(journey = sample.journey.copy(motorwayShare = fast / km.last()), trace = checkpoints)
            }
        }

    fun editStops(old: FuelStop, replacement: FuelCandidate? = null, amount: Double? = null, remove: Boolean = false, unlock: Boolean = false) {
        if (busy != null) return
        val current = result ?: return
        val key = old.candidate.key
        val newLocks = locked - key + when {
            remove || unlock -> emptyMap()
            replacement != null -> mapOf(replacement.key to FuelStopEdit())
            else -> mapOf(key to FuelStopEdit(amount))
        }
        val newExclusions = if (remove || replacement != null) (excluded + key) - setOfNotNull(replacement?.key) else excluded
        recalculate(current, newLocks, newExclusions)
    }

    fun resetStops() {
        if (busy != null) return
        result?.let { recalculate(it, emptyMap(), emptySet()) }
    }

    private fun recalculate(current: FuelTripResult, newLocks: Map<String, FuelStopEdit>, newExclusions: Set<String>) {
        busy = "Recalculating fuel stops…"
        work = scope.launch {
            val comparisons = optimize(current.road, current.roadFuel, current.request, current.candidates,
                current.priceTime, current.reference, newLocks, newExclusions)
            val revised = comparisons[current.request.constraints.strategy]
            if (revised != null) {
                result = current.copy(plan = revised, comparisons = comparisons)
                locked = newLocks
                excluded = newExclusions
                error = null
            } else error = "That change would make the fuel plan infeasible."
            busy = null
        }
    }

    // A lateral offset is a search radius, not a test of the extra road distance.
    private fun corridorWidth(limit: Double) = limit.coerceIn(0.6, 12.0)

    private suspend fun optimize(
        road: RouteData, roadFuel: Double, request: FuelTripRequest, candidates: List<FuelCandidate>,
        now: Instant, reference: Double?, newLocks: Map<String, FuelStopEdit> = emptyMap(), newExclusions: Set<String> = emptySet(),
    ): Map<FuelStrategy, FuelPlan> = withContext(Dispatchers.Default) {
        FuelStrategy.entries.mapNotNull { strategy ->
            FuelOptimizer.plan(roadFuel, road.totalKm, road.totalMin, request.constraints.copy(strategy = strategy),
                candidates, now, reference, newLocks, newExclusions)?.let { strategy to it }
        }.toMap()
    }
}
