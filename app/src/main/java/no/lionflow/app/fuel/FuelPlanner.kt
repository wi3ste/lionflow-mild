package no.lionflow.app.fuel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import no.lionflow.app.LionFlowApp
import no.lionflow.app.plan.Geo
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.PlanApi
import no.lionflow.app.plan.RouteData
import java.time.Instant
import no.lionflow.app.model.Trip
import no.lionflow.app.route.Route
import no.lionflow.app.route.Routes
import kotlin.math.abs
import java.util.Locale
import no.lionflow.app.BuildConfig
import android.util.Log
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt

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
    val priceRange: Pair<Double, Double>?,
    val fresh: Int,
    val canExtend: Boolean,
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
    private data class RouteKey(val coordinates: List<Pair<Double, Double>>)
    private data class SearchKey(val route: RouteKey, val grade: FuelGrade, val service: FuelServiceFilter, val detour: Double)
    private val routes = FuelMemo<RouteKey, RouteData>(6, 6 * 3600)
    private val roadAccess = FuelMemo<String, PlanApi.FuelAccess>(512, 24 * 3600)
    private var lastSearch: SearchKey? = null
    private var lastRequest: FuelTripRequest? = null
    private var lastTrips: List<Trip> = emptyList()
    private var lastRoutes: List<Route> = emptyList()
    private var priorProjections = emptyList<String>()
    var backtest by mutableStateOf<List<FuelBacktest>?>(null)
        private set
    private var routingRetryAt = Instant.MIN

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
        lastRequest = null
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
        busy = null
        if (result?.plan == null) editing = true
    }

    fun close() {
        cancel()
        open = false
        editing = true
    }

    fun searchMore() {
        val request = result?.request ?: lastRequest ?: return
        calculate(request, extend = true)
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

    fun calculate(request: FuelTripRequest, extend: Boolean = false) {
        work?.cancel()
        val end = if (request.round) request.from else request.to
        val via = if (request.round) request.via + request.to else request.via
        val routeKey = RouteKey((listOf(request.from) + via + end).map { it.lat to it.lon })
        val key = SearchKey(routeKey, request.grade, request.service, request.maxDetour)
        val reuse = key == lastSearch
        if (!reuse) {
            locked = emptyMap()
            excluded = emptySet()
            priorProjections = emptyList()
        }
        val constraintsLocked = locked
        val exclusions = excluded
        val published = result?.takeIf { extend && reuse }
        result = published
        backtest = null
        error = null
        lastRequest = request
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
                val cachedRoad = routes.get(routeKey, now)
                val road = timed("route") {
                    cachedRoad ?: PlanApi.fuelRoute(request.from, end, via).also { routes.put(routeKey, it, now) }
                }
                val cacheOnly = reuse && cachedRoad != null && !extend
                val searchRequest = request
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
                val projections = timed("corridor") {
                    if (stations == null || direct) emptyList() else withContext(Dispatchers.Default) {
                        FuelCorridor.find(road, fuel, stations, request.grade, request.service, minOf(request.maxDetour / 2, 5.0), now)
                            .filter { it.straightKm * 2 <= request.maxDetour }
                    }
                }
                val reference = timed("prices") {
                    withContext(Dispatchers.Default) {
                        val local = projections.map { it.offer }.filter { it.fresh(now) }.mapNotNull { it.price }
                        val national = if (local.isEmpty()) stations?.stations.orEmpty().mapNotNull {
                            it.offer(request.grade, request.service, now)?.takeIf { offer -> offer.fresh(now) }?.price
                        } else emptyList()
                        (local.ifEmpty { national }).sorted().let { it.getOrNull(it.size / 2) }
                    }
                }
                val remembered = if (cachedRoad == null) emptySet() else priorProjections.toSet()
                val full = timed("shortlist") { shortlist(projections, searchRequest, required, now, extend) }
                val retryUnavailable = extend && published?.plan == null && full.all { it.key in remembered }
                val shortlist = if (cacheOnly) projections.filter { it.key in remembered } else {
                    val previous = projections.filter { it.key in remembered }
                    if (extend) previous + full.filter { it.key !in remembered }.take(16) else full
                }
                val forward = if (request.via.isEmpty() && !request.round && road.ferries.isEmpty()) {
                    val options = shortlist.filter { it.straightKm > 0.05 }
                    (options.filter { it.key in remembered } + options.filter { it.key !in remembered }.take(4))
                        .mapNotNull { item ->
                        findRejoin(road, fuel, item, request.maxDetour)?.let { item.key to it }
                    }.toMap()
                } else emptyMap()
                val verified = mutableListOf<FuelCandidate>()
                val rejected = mutableMapOf<String, Int>()
                var checked = 0
                var probes = 0
                var firstPlan: Long? = null
                val checkedKeys = linkedSetOf<String>().apply { if (reuse) addAll(remembered) }
                lastSearch = key
                suspend fun wave(batch: List<FuelProjection>): Map<FuelStrategy, Double> {
                    busy = "Checking fuel stations… $checked/${shortlist.size}"
                    verified += timed("verify") {
                        verify(batch, request, prediction, now, forward, rejected, cacheOnly,
                            if (retryUnavailable) emptySet() else remembered) { probes++ }
                    }
                    checked += batch.size
                    checkedKeys += batch.map { it.key }
                    priorProjections = checkedKeys.toList()
                    val comparisons = timed("solve") {
                        optimize(road, required, request, verified, now, reference, constraintsLocked, exclusions)
                    }
                    val selected = result?.request?.constraints?.strategy ?: request.constraints.strategy
                    val active = request.copy(constraints = request.constraints.copy(strategy = selected))
                    val plan = comparisons[selected]
                    if (plan != null || result == null) timed("publish") {
                        val quotes = verified.mapNotNull { it.offer.price }
                        result = FuelTripResult(active, road, required, verified.toList(), reference, now,
                            !direct && projections.size > verified.size, plan, prediction,
                            FuelSearchSummary(projections.size, verified.size, checked, quotes.minOrNull()?.let { it to quotes.max() },
                                verified.count { it.offer.price != null && it.offer.fresh(now) },
                                !direct && when {
                                    plan == null && (cacheOnly || "rate-limit" in rejected || "routing-unavailable" in rejected) -> true
                                    extend -> full.any { it.key !in checkedKeys }
                                    else -> projections.size > checkedKeys.size
                                }), comparisons)
                        if (plan != null && firstPlan == null) firstPlan = (System.nanoTime() - started) / 1_000_000
                    }
                    return comparisons.mapValues { it.value.score }
                }
                if (cacheOnly) wave(shortlist) else searchFuelWaves(shortlist, firstFuelWave(required, request.constraints), ::wave)
                if (BuildConfig.DEBUG) Log.d("FuelPlanner", "timingMs=$timings roadHit=${cachedRoad != null} cacheOnly=$cacheOnly " +
                    "firstPlanSearchMs=$firstPlan probes=$probes corridor=${projections.size} tested=$checked " +
                    "verified=${verified.size} rejected=$rejected scores=${result?.comparisons?.mapValues { it.value.score }}")
                if (result?.plan == null) error = when {
                    cacheOnly -> "The cached stations cannot cover the new fuel settings."
                    stations == null && !direct -> "Fuel data unavailable. Refresh stations and try again."
                    projections.isEmpty() -> "No compatible fuel stations found along this route."
                    else -> "No feasible fuel plan found. Check your fuel, reserve and detour limit."
                }
            } catch (e: CancellationException) {
                throw e
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

    private fun shortlist(projections: List<FuelProjection>, request: FuelTripRequest, required: Double, now: Instant, extend: Boolean): List<FuelProjection> {
        val (capacity, initial, reserve) = request.constraints
        val range = capacity - reserve
        val budget = if (extend) 36 else 6 + 3 * ceil(required / range).toInt().coerceIn(1, 4)
        val first = projections.filter { it.roadFuel <= initial - reserve + 1e-7 || it.roadKm < 0.01 }
        val opening = FuelCorridor.shortlist(first, range, now, 2)
        return (opening + FuelCorridor.shortlist(projections, range, now, budget)).distinctBy { it.key }.take(budget)
    }

    private data class ForwardAccess(val place: Place, val bearing: Int, val progress: FuelRejoin)

    private fun findRejoin(road: RouteData, fuel: DoubleArray, candidate: FuelProjection, limit: Double): ForwardAccess? {
        val ahead = (candidate.straightKm * 2).coerceIn(0.6, minOf(3.0, maxOf(0.6, limit)))
        val km = candidate.roadKm + ahead
        val idx = road.km.binarySearch(km).let { if (it >= 0) it else -it - 1 }
        if (idx !in 1 until road.size || road.km[idx] <= road.km[idx - 1]) return null
        val ratio = (km - road.km[idx - 1]) / (road.km[idx] - road.km[idx - 1])
        fun at(series: DoubleArray) = series[idx - 1] + ratio * (series[idx] - series[idx - 1])
        val lat = at(road.lat)
        val lon = at(road.lon)
        val bearing = (Math.toDegrees(atan2((road.lon[idx] - road.lon[idx - 1]) * cos(Math.toRadians(lat)),
            road.lat[idx] - road.lat[idx - 1])).roundToInt() + 360) % 360
        return ForwardAccess(Place("", "", lat, lon), bearing, FuelRejoin(km, at(road.min), at(fuel)))
    }

    private suspend fun verify(
        shortlist: List<FuelProjection>, request: FuelTripRequest, prediction: FuelConsumption, now: Instant,
        forward: Map<String, ForwardAccess>, rejected: MutableMap<String, Int>, cacheOnly: Boolean, remembered: Set<String>, onProbe: () -> Unit,
    ): List<FuelCandidate> = coroutineScope {
        val requests = Semaphore(2)
        shortlist.map { candidate ->
            async {
                requests.withPermit {
                    val station = candidate.station
                    suspend fun access(rejoin: ForwardAccess?): PlanApi.FuelAccess? {
                        val key = "${station.id}|${station.lat}|${station.lon}|${candidate.anchor.lat}|${candidate.anchor.lon}|${candidate.bearing}|" +
                            rejoin?.let { "${it.place.lat}|${it.place.lon}|${it.bearing}" }.orEmpty()
                        val cached = roadAccess.get(key, now)
                        if (cached != null) return cached
                        if (cacheOnly || candidate.key in remembered || Instant.now() < routingRetryAt) return null
                        return try {
                            onProbe()
                            withTimeoutOrNull(12_000) {
                                PlanApi.fuelAccess(candidate.anchor, Place(station.title, station.location, station.lat, station.lon),
                                    candidate.bearing, rejoin?.place ?: candidate.anchor, rejoin?.bearing ?: candidate.bearing)
                            }
                        } catch (_: PlanApi.FuelRoutingRateLimited) {
                            routingRetryAt = Instant.now().plusSeconds(60)
                            null
                        }.also { if (it != null) roadAccess.put(key, it, now) }
                    }
                    val loop = access(null)
                    val original = loop?.let { FuelCandidate.verified(candidate, it, request.maxDetour, prediction) }
                    val next = forward[candidate.key]
                    val through = next?.takeIf { original == null || original.detourMin > 8 }?.let { access(it) }
                    val later = through?.let { FuelCandidate.verified(candidate, it, request.maxDetour, prediction, next.progress) }
                    val chosen = later?.takeIf {
                        original == null || (it.outFuel <= original.outFuel && it.detourFuel <= original.detourFuel &&
                            it.detourMin <= original.detourMin && it.backFuel <= original.backFuel)
                    } ?: original
                    if (chosen == null) {
                        val reason = when {
                            Instant.now() < routingRetryAt -> "rate-limit"
                            loop == null && through == null -> "routing-unavailable"
                            listOfNotNull(loop, through).any { it.outKm + it.backKm > request.maxDetour } -> "distance-limit"
                            else -> "invalid-snap"
                        }
                        rejected[reason] = rejected.getOrDefault(reason, 0) + 1
                    }
                    chosen
                }
            }
        }.awaitAll().filterNotNull()
    }

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
