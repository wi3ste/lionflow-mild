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
    val consumption: FuelConsumption,
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
    val comparisons: Map<FuelStrategy, FuelPlan> = emptyMap(),
) {
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

internal class FuelPlanner(private val scope: CoroutineScope, private val app: LionFlowApp) {
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
    private data class CachedAccess(val time: Instant, val access: PlanApi.FuelAccess)
    private val roadAccess = LinkedHashMap<String, CachedAccess>(64, 0.75f, true)
    private var routingRetryAt = Instant.MIN

    fun selectStrategy(strategy: FuelStrategy) {
        if (busy != null) return
        val current = result ?: return
        val chosen = current.comparisons[strategy] ?: return
        result = current.copy(request = current.request.copy(constraints = current.request.constraints.copy(strategy = strategy)), plan = chosen)
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

    fun close() {
        work?.cancel()
        busy = null
        open = false
        editing = true
    }

    fun calculate(request: FuelTripRequest) {
        work?.cancel()
        result = null
        error = null
        locked = emptyMap()
        excluded = emptySet()
        busy = "Routing the fuel trip…"
        editing = false
        work = scope.launch {
            try {
                val end = if (request.round) request.from else request.to
                val via = if (request.round) request.via + request.to else request.via
                val road = PlanApi.fuelRoute(request.from, end, via)
                val fuel = withContext(Dispatchers.Default) { request.consumption.forRoute(road) }
                val required = fuel.last()
                val time = Instant.now()
                val direct = request.constraints.initial - required >= request.constraints.reserve
                busy = "Checking fuel stations…"
                val snapshot = if (direct && catalog.state.value.snapshot == null) null else catalog.refresh().snapshot
                val stations = snapshot?.index
                val projections = if (stations != null) withContext(Dispatchers.Default) {
                    FuelCorridor.find(road, fuel, stations, request.grade, request.service, minOf(request.maxDetour / 2, 5.0), time)
                        .filter { it.straightKm * 2 <= request.maxDetour }
                } else emptyList()
                val reference = withContext(Dispatchers.Default) {
                    val local = projections.map { it.offer }.filter { it.fresh(time) }.mapNotNull { it.price }
                    val national = if (local.isEmpty()) stations?.stations.orEmpty().mapNotNull {
                        it.offer(request.grade, request.service, time)?.takeIf { offer -> offer.fresh(time) }?.price
                    } else emptyList()
                    (local.ifEmpty { national }).sorted().let { it.getOrNull(it.size / 2) }
                }
                val shortlist = if (direct) emptyList() else shortlist(projections, request, required, time)
                val forward = if (request.via.isEmpty() && !request.round && road.ferries.isEmpty()) {
                    shortlist.filter { it.straightKm > 0.05 }.take(4).mapNotNull { item ->
                        findRejoin(road, fuel, item, request.maxDetour)?.let { item.key to it }
                    }.toMap()
                } else emptyMap()
                val rejected = mutableMapOf<String, Int>()
                var checked = 0
                val verified = verifyFuelBatches(shortlist) { batch ->
                    busy = "Checking fuel stations… $checked/${shortlist.size}"
                    verify(batch, request, time, forward, rejected).also { checked += batch.size }
                }
                val comparisons = optimize(road, required, request, verified, time, reference)
                val computed = comparisons[request.constraints.strategy]
                if (BuildConfig.DEBUG) Log.d("FuelPlanner", "corridor=${projections.size} shortlisted=${shortlist.size} " +
                    "verified=${verified.size} rejected=${shortlist.size - verified.size} reasons=$rejected " +
                    "scores=${comparisons.mapValues { it.value.score }}")
                result = FuelTripResult(request, road, required, verified, reference, time,
                    !direct && (projections.size > verified.size), computed, comparisons)
                if (computed != null) editing = false
                else error = when {
                    stations == null -> "Fuel data unavailable. Refresh stations and try again."
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

    private fun shortlist(projections: List<FuelProjection>, request: FuelTripRequest, required: Double, now: Instant): List<FuelProjection> {
        val (capacity, initial, reserve) = request.constraints
        val range = capacity - reserve
        val budget = (12 + 6 * ceil(required / range).coerceIn(1.0, 4.0).toInt()).coerceAtMost(36)
        val first = projections.filter { it.roadFuel <= initial - reserve + 1e-7 || it.roadKm < 0.01 }
        val opening = FuelCorridor.shortlist(first, range, now, 4)
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
        shortlist: List<FuelProjection>, request: FuelTripRequest, now: Instant,
        forward: Map<String, ForwardAccess>, rejected: MutableMap<String, Int>,
    ): List<FuelCandidate> = coroutineScope {
        val requests = Semaphore(2)
        shortlist.map { candidate ->
            async {
                requests.withPermit {
                    val station = candidate.station
                    suspend fun access(rejoin: ForwardAccess?): PlanApi.FuelAccess? {
                        val key = "${station.id}|${station.lat}|${station.lon}|${candidate.anchor.lat}|${candidate.anchor.lon}|${candidate.bearing}|" +
                            rejoin?.let { "${it.place.lat}|${it.place.lon}|${it.bearing}" }.orEmpty()
                        val cached = roadAccess[key]?.takeIf { it.time.plusSeconds(86400).isAfter(now) }?.access
                        return cached ?: try {
                            if (Instant.now() < routingRetryAt) return null
                            withTimeoutOrNull(12_000) {
                                PlanApi.fuelAccess(candidate.anchor, Place(station.title, station.location, station.lat, station.lon),
                                    candidate.bearing, rejoin?.place ?: candidate.anchor, rejoin?.bearing ?: candidate.bearing)
                            }
                        } catch (_: PlanApi.FuelRoutingRateLimited) {
                            routingRetryAt = Instant.now().plusSeconds(60)
                            null
                        }?.also {
                            roadAccess[key] = CachedAccess(now, it)
                            if (roadAccess.size > 256) roadAccess.remove(roadAccess.keys.first())
                        }
                    }
                    val loop = access(null)
                    val original = loop?.let { FuelCandidate.verified(candidate, it, request.maxDetour, request.consumption) }
                    val next = forward[candidate.key]
                    val through = next?.let { access(it) }
                    val later = through?.let { FuelCandidate.verified(candidate, it, request.maxDetour, request.consumption, next.progress) }
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

internal suspend fun <T, R> verifyFuelBatches(candidates: List<T>, verify: suspend (List<T>) -> List<R>): List<R> =
    buildList {
        for (batch in candidates.chunked(8)) addAll(verify(batch))
    }
