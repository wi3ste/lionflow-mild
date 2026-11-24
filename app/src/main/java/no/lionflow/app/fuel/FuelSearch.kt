package no.lionflow.app.fuel

import no.lionflow.app.plan.PlanApi
import no.lionflow.app.plan.RouteData
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil

/** Process-local, bounded, and independent of the current tank, driver or strategy. */
internal class FuelMemo<K, V>(private val limit: Int, private val ttlSeconds: Long) {
    private data class Entry<V>(val value: V, val at: Instant)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    fun get(key: K, now: Instant): V? {
        val entry = entries[key] ?: return null
        if (!entry.at.plusSeconds(ttlSeconds).isAfter(now)) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: K, value: V, now: Instant) {
        entries[key] = Entry(value, now)
        if (entries.size > limit) entries.remove(entries.keys.first())
    }
}

/** Publish the first feasible plan, then try one more mixed wave within the automatic budget. */
internal suspend fun <T> searchFuelWaves(
    shortlist: List<T>, initialWave: Int, stop: () -> Boolean = { false },
    evaluate: suspend (List<T>) -> Map<FuelStrategy, Double>,
) {
    if (shortlist.isEmpty()) {
        evaluate(emptyList())
        return
    }
    var feasible = false
    var start = 0
    var count = initialWave.coerceAtLeast(1)
    while (start < shortlist.size) {
        val end = minOf(shortlist.size, start + count)
        val scores = evaluate(shortlist.subList(start, end))
        if (feasible || stop()) return
        feasible = scores.size == FuelStrategy.entries.size
        start = end
        count = 4
    }
}

internal fun FuelTripResult.withStrategy(strategy: FuelStrategy): FuelTripResult {
    val chosen = comparisons[strategy] ?: return this
    return copy(request = request.copy(constraints = request.constraints.copy(strategy = strategy)), plan = chosen)
}

internal data class FuelQuoteStats(val low: Double, val median: Double, val high: Double) {
    companion object {
        fun of(quotes: List<Double>): FuelQuoteStats? {
            val sorted = quotes.sorted()
            if (sorted.isEmpty()) return null
            val middle = (sorted[sorted.lastIndex / 2] + sorted[sorted.size / 2]) / 2
            return FuelQuoteStats(sorted.first(), middle, sorted.last())
        }
    }
}

internal data class FuelSearchChoice(val projection: FuelProjection, val potential: Double?)
internal data class FuelSearchRow(val choice: FuelSearchChoice, val check: FuelCheck)
internal data class FuelExtensionProgress(val planned: Int, val selectedRetries: Int, val examined: Int, val accepted: Int, val retried: Int)

internal class FuelStationSearch(initial: List<FuelProjection>, private val routeKm: Double) {
    private val projections = initial.distinctBy { it.key }.sortedBy { it.roadKm }
    val corridorSize = projections.map { it.station.id }.distinct().size
    val corridorPrices = FuelQuoteStats.of(projections.distinctBy { it.station.id }.mapNotNull { it.offer.price })
    private val checked = linkedMapOf<String, FuelSearchRow>()
    private val queued = linkedMapOf<String, FuelSearchChoice>()
    private var requests = 0
    private var cacheHits = 0

    val candidates get() = checked.values.mapNotNull { (it.check as? FuelCheck.Verified)?.candidate }
    val hasMore get() = projections.any { checked[it.key]?.check.let { check -> check == null || check is FuelCheck.Deferred } }

    fun automatic(constraints: FuelConstraints, required: Double, now: Instant): List<FuelSearchChoice> {
        val range = constraints.capacity - constraints.reserve
        val budget = 6 + 3 * ceil(required / range).toInt().coerceIn(1, 4)
        // Keep directly reachable stations early for the first feasible plan.
        val reachable = projections.filter { it.roadFuel <= constraints.initial - constraints.reserve + 1e-7 || it.roadKm < 0.01 }
        val opening = FuelCorridor.shortlist(reachable, range, now, 2)
        val mixed = FuelCorridor.shortlist(projections, range, now, budget)
        return (opening + mixed).distinctBy { it.key }.take(budget).map {
            FuelSearchChoice(it, potential(it, constraints, required, null, now))
        }
    }

    fun extension(constraints: FuelConstraints, required: Double, current: FuelPlan?, now: Instant): List<FuelSearchChoice> {
        val fresh = projections.filter { it.key !in checked }
        val retry = checked.values.filter { (it.check as? FuelCheck.Deferred)?.retryAt?.let { at -> at <= now } == true }
            .map { it.choice.projection }
        fun choice(projection: FuelProjection) = FuelSearchChoice(projection, potential(projection, constraints, required, current, now))
        val ranked = fresh.map(::choice).sortedWith(compareByDescending<FuelSearchChoice> { it.potential ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.projection.straightKm }.thenBy { it.projection.key })
        val range = constraints.capacity - constraints.reserve
        val bands = ranked.groupBy { (it.projection.roadFuel / maxOf(2.0, range / 3)).toInt() }.values
        val distinctBands = bands.map { it.first() }.sortedByDescending { it.potential ?: Double.NEGATIVE_INFINITY }
        val economic = (ranked.take(3) + distinctBands + ranked).distinctBy { it.projection.key }
        val retries = retry.map(::choice).sortedByDescending { it.potential ?: Double.NEGATIVE_INFINITY }
        val nearby = FuelCorridor.shortlist(fresh, range, now, 8).map(::choice)
        // Reserve slots for usable retries and route coverage. Most slots go to likely savings.
        return (economic.take(2) + retries.take(1) + economic.take(5) +
            nearby + retries + economic).distinctBy { it.projection.key }.take(8)
    }

    private fun potential(
        projection: FuelProjection, c: FuelConstraints, required: Double, current: FuelPlan?, now: Instant,
    ): Double? {
        val quote = projection.offer.price ?: return null
        val stops = current?.stops.orEmpty()
        val comparisonStop = stops.minByOrNull { abs(it.candidate.projection.roadFuel - projection.roadFuel) }
        // Estimate a substitution without assuming that the replaced stop has already filled the tank.
        val prior = stops.lastOrNull { it != comparisonStop && it.candidate.rejoinKm < projection.roadKm }
        val available = if (prior == null) c.initial - projection.roadFuel
        else prior.departure - prior.candidate.backFuel - (projection.roadFuel - prior.candidate.rejoinFuel)
        val reference = comparisonStop?.candidate?.offer?.price ?: corridorPrices?.median ?: return null
        val expectedBuy = comparisonStop?.purchased ?: (required + c.reserve - c.initial)
        val headroom = (c.capacity - available.coerceAtLeast(c.reserve)).coerceAtLeast(0.0)
        val remainingDemand = (required - projection.roadFuel + c.reserve - available).coerceAtLeast(0.0)
        val litres = minOf(headroom, remainingDemand, expectedBuy.coerceAtLeast(0.0))
        val adjustedPrice = quote + if (projection.offer.fresh(now)) 0.0 else 0.30
        // Lateral distance and estimated reserve are ranking signals. The router and optimizer decide feasibility.
        val distanceCost = projection.straightKm * 2 * required / routeKm.coerceAtLeast(1.0) * reference
        val reachPenalty = (c.reserve - available).coerceAtLeast(0.0) * 0.20
        val positionPenalty = comparisonStop?.let { abs(it.candidate.projection.roadFuel - projection.roadFuel) * 0.02 } ?: 0.0
        return litres * (reference - adjustedPrice) - distanceCost - reachPenalty - positionPenalty
    }

    suspend fun check(
        batch: List<FuelSearchChoice>, budget: FuelProbeBudget, now: () -> Instant,
        verify: suspend (FuelProjection) -> FuelCheck,
        progress: (FuelExtensionProgress) -> Unit = {},
    ): FuelExtensionProgress {
        batch.forEach { queued[it.projection.key] = it }
        val retries = batch.count { checked[it.projection.key]?.check is FuelCheck.Deferred }
        var update = FuelExtensionProgress(batch.size, retries, 0, 0, 0)
        progress(update)
        for (choice in batch) {
            val key = choice.projection.key
            val previous = checked[key]?.check
            if (budget.exhausted) break
            if (previous != null && (previous !is FuelCheck.Deferred || previous.retryAt > now())) continue
            val calls = budget.requests
            val hits = budget.hits
            val outcome = try {
                verify(choice.projection)
            } finally {
                requests += budget.requests - calls
                cacheHits += budget.hits - hits
            }
            if (outcome is FuelCheck.Deferred && outcome.reason == FuelCheckReason.THROTTLED &&
                budget.requests == calls && budget.hits == hits) break
            checked[key] = FuelSearchRow(choice, outcome)
            update = update.copy(
                examined = update.examined + if (previous == null) 1 else 0,
                accepted = update.accepted + if (outcome is FuelCheck.Verified) 1 else 0,
                retried = update.retried + if (previous != null) 1 else 0,
            )
            progress(update)
            if (outcome is FuelCheck.Deferred && outcome.reason == FuelCheckReason.THROTTLED) break
        }
        return update
    }

    fun rebased(road: RouteData, fuel: DoubleArray, prediction: FuelConsumption, limit: Double): FuelStationSearch {
        fun fuelAt(km: Double): Double {
            val found = road.km.binarySearch(km)
            if (found >= 0) return fuel[found]
            val hi = (-found - 1).coerceIn(1, fuel.lastIndex)
            val distance = road.km[hi] - road.km[hi - 1]
            return if (distance <= 0) fuel[hi] else fuel[hi - 1] + (fuel[hi] - fuel[hi - 1]) * (km - road.km[hi - 1]) / distance
        }
        val next = FuelStationSearch(projections.map { it.copy(roadFuel = fuelAt(it.roadKm)) }, routeKm)
        val updated = next.projections.associateBy { it.key }
        for ((key, row) in checked) {
            val projection = updated.getValue(key)
            val old = row.check
            val outcome = if (old is FuelCheck.Verified) {
                val c = old.candidate
                val access = PlanApi.FuelAccess(c.outKm, c.backKm, c.outMin, c.backMin, c.shape)
                val entry = c.entry?.let { it.copy(fuel = fuelAt(it.km)) }
                val exit = c.rejoin?.let { it.copy(fuel = fuelAt(it.km)) }
                val refreshed = FuelCandidate.verified(projection, access, limit, prediction, exit, entry)
                if (refreshed != null) FuelCheck.Verified(refreshed) else FuelCheck.Rejected(FuelCheckReason.LIMIT, c.detourKm)
            } else old
            next.checked[key] = FuelSearchRow(row.choice.copy(projection = projection), outcome)
        }
        next.queued.putAll(queued.mapValues { (key, choice) -> choice.copy(projection = updated.getValue(key)) })
        next.requests = requests
        next.cacheHits = cacheHits
        return next
    }

    fun summary(now: Instant): FuelSearchSummary {
        val verified = candidates
        val quotes = FuelQuoteStats.of(verified.mapNotNull { it.offer.price })
        val rows = checked.values.toList()
        return FuelSearchSummary(corridorSize, verified.size, rows.size,
            verified.count { it.offer.price != null && it.offer.fresh(now) }, hasMore,
            corridorPrices, FuelQuoteStats.of(queued.values.mapNotNull { it.projection.offer.price }), quotes,
            projections.size - rows.size, requests, cacheHits, rows = rows)
    }
}

internal fun betterFuelPlans(previous: Map<FuelStrategy, FuelPlan>, revised: Map<FuelStrategy, FuelPlan>): Map<FuelStrategy, FuelPlan> =
    previous + revised.filter { (strategy, plan) -> plan.score < (previous[strategy]?.score ?: Double.POSITIVE_INFINITY) - 1e-7 }
