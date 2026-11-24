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
                val shortlist = if (direct) emptyList() else shortlist(projections, request, time)
                val verified = verify(shortlist.take(16), request, time).toMutableList()
                var computed = optimize(road, required, request, verified, time, reference)
                if (computed == null && shortlist.size > 16) {
                    verified += verify(shortlist.drop(16), request, time)
                    computed = optimize(road, required, request, verified, time, reference)
                }
                result = FuelTripResult(request, road, required, verified.toList(), reference, time,
                    !direct && (projections.size > verified.size), computed)
                if (computed != null) editing = false
                else error = when {
                    stations == null -> "Fuel data unavailable. Refresh stations and try again."
                    projections.isEmpty() -> "No compatible fuel stations found along this route."
                    else -> "No feasible fuel plan found. Check your fuel, reserve and detour limit."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
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
            val revised = optimize(current.road, current.roadFuel, current.request, current.candidates,
                current.priceTime, current.reference, newLocks, newExclusions)
            if (revised != null) {
                result = current.copy(plan = revised)
                locked = newLocks
                excluded = newExclusions
                error = null
            } else error = "That change would make the fuel plan infeasible."
            busy = null
        }
    }

    private fun shortlist(projections: List<FuelProjection>, request: FuelTripRequest, now: Instant): List<FuelProjection> {
        val (_, initial, reserve, strategy) = request.constraints
        val budget = 24
        val range = request.constraints.capacity - reserve
        val first = projections.filter { it.roadFuel <= initial - reserve + 1e-7 }
        val opening = FuelCorridor.shortlist(first, range, now, 5, strategy)
        return (opening + FuelCorridor.shortlist(projections, range, now, budget, strategy)).distinctBy { it.key }.take(budget)
    }

    private suspend fun verify(shortlist: List<FuelProjection>, request: FuelTripRequest, now: Instant): List<FuelCandidate> = coroutineScope {
        val requests = Semaphore(2)
        shortlist.map { candidate ->
            async {
                requests.withPermit {
                    val station = candidate.station
                    val key = "${station.id}|${candidate.anchor.lat}|${candidate.anchor.lon}|${candidate.bearing}"
                    val cached = roadAccess[key]?.takeIf { it.time.plusSeconds(86400).isAfter(now) }?.access
                    val access = cached ?: try {
                        if (Instant.now() < routingRetryAt) return@withPermit null
                        withTimeoutOrNull(12_000) {
                            PlanApi.fuelAccess(candidate.anchor, Place(station.title, station.location, station.lat, station.lon), candidate.bearing)
                        }
                    } catch (_: PlanApi.FuelRoutingRateLimited) {
                        routingRetryAt = Instant.now().plusSeconds(60)
                        null
                    }?.also {
                        roadAccess[key] = CachedAccess(now, it)
                        if (roadAccess.size > 256) roadAccess.remove(roadAccess.keys.first())
                    } ?: return@withPermit null
                    FuelCandidate.verified(candidate, access, request.maxDetour, request.consumption)
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun optimize(
        road: RouteData, roadFuel: Double, request: FuelTripRequest, candidates: List<FuelCandidate>,
        now: Instant, reference: Double?, newLocks: Map<String, FuelStopEdit> = emptyMap(), newExclusions: Set<String> = emptySet(),
    ): FuelPlan? = withContext(Dispatchers.Default) {
        FuelOptimizer.plan(roadFuel, road.totalKm, road.totalMin, request.constraints, candidates, now, reference, newLocks, newExclusions)
    }
}
