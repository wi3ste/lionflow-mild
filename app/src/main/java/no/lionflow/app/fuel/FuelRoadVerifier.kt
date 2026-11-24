package no.lionflow.app.fuel

import no.lionflow.app.plan.Geo
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.PlanApi
import no.lionflow.app.plan.RouteData
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt

internal enum class FuelCheckReason(val label: String) {
    ROUTED("Road verified"), NO_ROAD("No route in checked windows"), LIMIT("Exceeds added-distance limit"),
    SNAP("Inconsistent station snap"), NETWORK("Router unavailable"), TIMEOUT("Router timeout"),
    THROTTLED("Router rate limit"), BUDGET("Verification budget reached"),
}

internal sealed interface FuelCheck {
    data class Verified(val candidate: FuelCandidate) : FuelCheck
    data class Rejected(val reason: FuelCheckReason, val netKm: Double? = null) : FuelCheck
    data class Deferred(val reason: FuelCheckReason, val retryAt: Instant) : FuelCheck
}

internal class FuelProbeBudget(private val limit: Int) {
    private val network = AtomicInteger()
    val requests get() = network.get()
    var hits = 0
        internal set
    val exhausted get() = requests >= limit

    fun beginRequest(): Boolean {
        while (true) {
            val count = network.get()
            if (count >= limit) return false
            if (network.compareAndSet(count, count + 1)) return true
        }
    }
}

internal data class FuelAccessPoint(val place: Place, val bearing: Int, val progress: FuelRejoin)
internal data class FuelAccessWindow(val entry: FuelAccessPoint, val exit: FuelAccessPoint) {
    val replacedKm get() = exit.progress.km - entry.progress.km
}

internal class FuelRoadVerifier(
    private val clock: () -> Instant = Instant::now,
    private val disk: FuelRoutingCache? = null,
    private val route: suspend (FuelAccessWindow, FuelStation, () -> Boolean) -> PlanApi.FuelAccessResult =
        { window, station, begin ->
            PlanApi.fuelAccess(window.entry.place, Place(station.title, station.location, station.lat, station.lon), window.entry.bearing,
                window.exit.place, window.exit.bearing, begin)
        },
) {
    // Fuel amounts and timing offsets belong to the current trip. The cache stores router geometry only.
    private val cache = FuelMemo<String, PlanApi.FuelAccessResult>(1024, 24 * 3600)
    private var retryAt = Instant.MIN
    val throttled get() = clock() < retryAt

    private suspend fun diskAccess(key: String, now: Instant) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { disk?.access(key, now) }

    suspend fun verify(
        projection: FuelProjection, windows: List<FuelAccessWindow>, prediction: FuelConsumption, limit: Double, budget: FuelProbeBudget,
    ): FuelCheck {
        var nearestRejected: Double? = null
        var reason = FuelCheckReason.NO_ROAD
        var temporary: FuelCheck.Deferred? = null
        for (window in windows) {
            val station = projection.station
            val key = FuelRoutingCache.accessKey(window, station)
            val now = clock()
            val cached = if (disk != null) diskAccess(key, now) else cache.get(key, now)
            val answer = if (cached != null) {
                budget.hits++
                cached
            } else {
                if (now < retryAt) return FuelCheck.Deferred(FuelCheckReason.THROTTLED, retryAt)
                if (budget.exhausted) return FuelCheck.Deferred(FuelCheckReason.BUDGET, now)
                try {
                    route(window, station, budget::beginRequest)
                } catch (limited: PlanApi.FuelRoutingRateLimited) {
                    retryAt = clock().plusSeconds(limited.retrySeconds)
                    return FuelCheck.Deferred(FuelCheckReason.THROTTLED, retryAt)
                }.also {
                    if (disk != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { disk.saveAccess(key, it, now) }
                    } else if (it is PlanApi.FuelAccessResult.Routed || it == PlanApi.FuelAccessResult.NoRoute) {
                        cache.put(key, it, now)
                    }
                }
            }
            when (answer) {
                is PlanApi.FuelAccessResult.Routed -> {
                    val access = answer.access
                    val net = access.outKm + access.backKm - window.replacedKm
                    FuelCandidate.verified(projection, access, limit, prediction, window.exit.progress, window.entry.progress)
                        ?.let { return FuelCheck.Verified(it) }
                    if (nearestRejected == null || net < nearestRejected) {
                        nearestRejected = net
                        reason = if (net > limit) FuelCheckReason.LIMIT else FuelCheckReason.SNAP
                    }
                }
                PlanApi.FuelAccessResult.NoRoute -> Unit
                is PlanApi.FuelAccessResult.Unavailable -> {
                    temporary = FuelCheck.Deferred(if (answer.timeout) FuelCheckReason.TIMEOUT else FuelCheckReason.NETWORK,
                        clock().plusSeconds(20))
                    // A network failure on both public endpoints need not be repeated for every geometry.
                    break
                }
                PlanApi.FuelAccessResult.Budget -> return FuelCheck.Deferred(FuelCheckReason.BUDGET, clock())
            }
        }
        return temporary ?: FuelCheck.Rejected(reason, nearestRejected)
    }
}

internal class FuelAccessWindows(
    private val road: RouteData,
    private val fuel: DoubleArray,
    private val protected: List<Double>,
    private val limit: Double,
) {
    fun forStation(projection: FuelProjection): List<FuelAccessWindow> {
        val at = FuelAccessPoint(projection.anchor, projection.bearing, FuelRejoin(projection.roadKm, projection.roadMin, projection.roadFuel))
        val loop = FuelAccessWindow(at, at)
        val before = protected.lastOrNull { it <= projection.roadKm } ?: 0.0
        val after = protected.firstOrNull { it >= projection.roadKm } ?: road.totalKm
        fun forward(span: Double): FuelAccessWindow? {
            val entry = maxOf(before, projection.roadKm - span)
            val exit = minOf(after, projection.roadKm + span)
            if (projection.roadKm - entry < 0.15 || exit - projection.roadKm < 0.15) return null
            return FuelAccessWindow(point(entry), point(exit))
        }
        val first = maxOf(1.2, limit * 0.55, projection.straightKm * 1.5)
        val through = listOfNotNull(forward(first), forward(maxOf(first * 2, limit * 1.5))).distinct()
        return if (projection.straightKm <= 0.10) listOf(loop) + through else through + loop
    }

    private fun point(km: Double): FuelAccessPoint {
        val found = road.km.binarySearch(km)
        val index = (if (found >= 0) found else -found - 1).coerceIn(1, road.size - 1)
        val ratio = ((km - road.km[index - 1]) / (road.km[index] - road.km[index - 1])).takeIf { it.isFinite() } ?: 0.0
        fun at(series: DoubleArray) = series[index - 1] + ratio * (series[index] - series[index - 1])
        val lat = at(road.lat)
        val bearing = (Math.toDegrees(atan2((road.lon[index] - road.lon[index - 1]) * cos(Math.toRadians(lat)),
            road.lat[index] - road.lat[index - 1])).roundToInt() + 360) % 360
        return FuelAccessPoint(Place("", "", lat, at(road.lon)), bearing, FuelRejoin(km, at(road.min), at(fuel)))
    }
}

internal fun protectedFuelKm(road: RouteData, vias: List<Place>): List<Double> {
    var after = 0
    val stops = vias.map { place ->
        val closest = (after until road.size).minByOrNull { Geo.km(road.lat[it], road.lon[it], place.lat, place.lon) } ?: after
        after = closest
        road.km[closest]
    }
    return (stops + road.ferries.flatMap { listOfNotNull(road.km.getOrNull(it.idx), road.km.getOrNull(it.endIdx)) }).sorted()
}
