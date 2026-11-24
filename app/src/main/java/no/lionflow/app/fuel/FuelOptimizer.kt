package no.lionflow.app.fuel

import java.time.Instant
import no.lionflow.app.plan.PlanApi
import kotlin.math.floor

internal enum class FuelStrategy(val label: String) {
    ECONOMY("Economy"), BALANCED("Balanced"), FAST("Fast");
}

internal data class FuelConstraints(
    val capacity: Double, val initial: Double, val reserve: Double, val strategy: FuelStrategy,
) {
    init {
        require(capacity.isFinite() && capacity > 0 && initial in 0.0..capacity && reserve >= 0 && reserve < capacity)
    }
}

internal data class FuelCandidate(
    val projection: FuelProjection,
    val outKm: Double,
    val backKm: Double,
    val outMin: Double,
    val backMin: Double,
    val outFuel: Double,
    val backFuel: Double,
    val shape: String = "",
) {
    val key get() = projection.key
    val offer get() = projection.offer
    val station get() = projection.station
    val detourKm get() = outKm + backKm
    val detourMin get() = outMin + backMin
    val accessFuel get() = projection.roadFuel + outFuel

    companion object {
        fun verified(candidate: FuelProjection, access: PlanApi.FuelAccess, maxDetour: Double, consumption: FuelConsumption): FuelCandidate? {
            val distance = access.outKm + access.backKm
            if (distance > maxDetour) return null
            // Reject a snap that unexpectedly jumps onto the nearby through road.
            if (distance + 0.03 < candidate.straightKm * 1.5) return null
            fun estimate(km: Double, minutes: Double) = consumption.amount(km, if (minutes > 0) km * 60 / minutes else 45.0)
            return FuelCandidate(candidate, access.outKm, access.backKm, access.outMin, access.backMin,
                estimate(access.outKm, access.outMin), estimate(access.backKm, access.backMin), access.shape)
        }
    }
}

internal data class FuelStop(
    val candidate: FuelCandidate,
    val arrival: Double,
    val purchased: Double,
    val atMinute: Double,
) {
    val departure get() = arrival + purchased
    val cost get() = candidate.offer.price?.times(purchased)
    val serviceMin get() = serviceTime(purchased)
}

internal data class FuelStopEdit(val amount: Double? = null)

internal data class FuelPlan(
    val used: Double,
    val remaining: Double,
    val stops: List<FuelStop>,
    val driveMin: Double,
    val totalKm: Double,
    val usedCost: Double?,
) {
    val detourKm get() = stops.sumOf { it.candidate.detourKm }
    val extraMin get() = stops.sumOf { it.candidate.detourMin + it.serviceMin }
    val totalMin get() = driveMin + stops.sumOf { it.serviceMin }
    val purchased get() = stops.sumOf { it.purchased }
    val purchaseCost get() = if (stops.all { it.cost != null }) stops.sumOf { it.cost!! } else null
}

private fun serviceTime(amount: Double) = 5.0 + amount / 8
private const val EPS = 1e-7

/** Forward search over verified stops. Labels carry an exact fuel balance and are pruned in small fuel bins. */
internal object FuelOptimizer {
    private data class Label(
        val remaining: Double, val score: Double, val stop: FuelStop? = null, val prev: Label? = null, val extraMin: Double = 0.0,
    )

    fun plan(
        roadFuel: Double, roadKm: Double, roadMin: Double, constraints: FuelConstraints,
        candidates: List<FuelCandidate>, now: Instant, referencePrice: Double?,
        locked: Map<String, FuelStopEdit> = emptyMap(), excluded: Set<String> = emptySet(),
    ): FuelPlan? {
        val (capacity, initial, reserve, strategy) = constraints
        if (initial - roadFuel >= reserve - EPS && locked.isEmpty()) {
            return FuelPlan(roadFuel, initial - roadFuel, emptyList(), roadMin, roadKm,
                if (roadFuel == 0.0) 0.0 else referencePrice?.times(roadFuel))
        }
        val nodes = candidates.filter { it.key !in excluded && it.projection.roadFuel <= roadFuel + EPS }
            .distinctBy { it.key }.sortedWith(compareBy<FuelCandidate> { it.projection.roadKm }.thenBy { it.key })
        if (!nodes.map { it.key }.containsAll(locked.keys)) return null
        val terminal = nodes.size
        val labels = Array(terminal) { HashMap<Int, Label>() }
        val bin = (capacity - reserve) / 180
        var best: Label? = null

        fun record(index: Int, label: Label) {
            if (index == terminal) {
                if (best == null || label.score < best!!.score - EPS) best = label
                return
            }
            val bucket = floor((label.remaining - reserve).coerceAtLeast(0.0) / bin + EPS).toInt()
            val previous = labels[index][bucket]
            if (previous == null || label.score < previous.score - EPS ||
                (label.score <= previous.score + EPS && label.remaining > previous.remaining)) {
                labels[index][bucket] = label
            }
        }

        fun access(index: Int) = nodes.getOrNull(index)?.accessFuel ?: roadFuel
        fun nextLock(after: Int): Int = (after + 1 until terminal).firstOrNull { nodes[it].key in locked } ?: terminal
        for (next in 0..nextLock(-1)) {
            val fuel = initial - access(next)
            if (fuel >= reserve - EPS || (next != terminal && access(next) <= EPS)) {
                record(next, Label(fuel.coerceAtLeast(0.0), 0.0))
            }
        }

        for ((index, node) in nodes.withIndex()) {
            val active = labels[index].values.sortedWith(compareByDescending<Label> { it.remaining }.thenBy { it.score })
            val last = nextLock(index)
            val uses = (index + 1..last).map { next -> next to (access(next) - node.projection.roadFuel + node.backFuel) }
                .filter { (_, needed) -> needed + reserve <= capacity + EPS }
            // Amounts that reach some later location at reserve, plus a full tank. The search can skip
            // intermediate purchases or carry cheaper fuel across a more expensive stop.
            val targets = (uses.map { (_, needed) -> (reserve + needed).coerceAtMost(capacity) } + capacity).distinct().sorted()
            val edit = locked[node.key]
            var bestScore = Double.POSITIVE_INFINITY
            val fixedQuantities = locked.values.any { it.amount != null }
            for (arrival in active) {
                if (!fixedQuantities && arrival.score >= bestScore - EPS) continue
                bestScore = minOf(bestScore, arrival.score)
                val departures = edit?.amount?.let { listOf(arrival.remaining + it) } ?: targets
                for (departure in departures) {
                    val amount = departure - arrival.remaining
                    if (amount < 0.01 - EPS || departure > capacity + EPS) continue
                    val stop = FuelStop(node, arrival.remaining, amount, node.projection.roadMin + arrival.extraMin + node.outMin)
                    val extra = node.detourMin + stop.serviceMin
                    val score = arrival.score + cost(strategy, stop, referencePrice, now)
                    for ((next, needed) in uses) {
                        if (departure - needed >= reserve - EPS) {
                            record(next, Label((departure - needed).coerceAtLeast(reserve), score, stop, arrival, arrival.extraMin + extra))
                        }
                    }
                }
            }
        }
        val result = best ?: return null
        val stops = generateSequence(result) { it.prev }.mapNotNull { it.stop }.toList().asReversed()
        val used = roadFuel + stops.sumOf { it.candidate.outFuel + it.candidate.backFuel }
        return FuelPlan(used, result.remaining, stops, roadMin + stops.sumOf { it.candidate.detourMin },
            roadKm + stops.sumOf { it.candidate.detourKm }, consumedCost(used, initial, referencePrice, stops))
    }

    private fun cost(strategy: FuelStrategy, stop: FuelStop, referencePrice: Double?, now: Instant): Double {
        val node = stop.candidate
        val minutes = node.detourMin + stop.serviceMin
        // Unquoted fuel stays usable for feasibility. This internal penalty never becomes a displayed price.
        val purchase = stop.cost ?: (referencePrice?.times(2) ?: 5.0) * stop.purchased
        val stale = if (node.offer.price != null && !node.offer.fresh(now)) 0.30 * stop.purchased else 0.0
        val detour = (node.outFuel + node.backFuel) * (referencePrice ?: 1.0)
        return when (strategy) {
            FuelStrategy.ECONOMY -> purchase + stale + detour + 0.01 * minutes
            FuelStrategy.BALANCED -> purchase + stale + detour + 0.40 * minutes + 2
            FuelStrategy.FAST -> minutes + node.detourKm * 0.12 + (purchase + stale) * 0.001
        }
    }

    private fun consumedCost(amount: Double, initial: Double, reference: Double?, stops: List<FuelStop>): Double? {
        var outstanding = amount
        var value = 0.0
        for ((units, price) in listOf(initial to reference) + stops.map { it.purchased to it.candidate.offer.price }) {
            val used = minOf(outstanding, units)
            if (used > EPS) value += used * (price ?: return null)
            outstanding -= used
        }
        return value.takeIf { outstanding <= EPS }
    }
}
