package no.lionflow.app.fuel

import java.time.Instant
import no.lionflow.app.plan.PlanApi

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

internal data class FuelRejoin(val km: Double, val minutes: Double, val fuel: Double)

internal data class FuelCandidate(
    val projection: FuelProjection,
    val outKm: Double,
    val backKm: Double,
    val outMin: Double,
    val backMin: Double,
    val outFuel: Double,
    val backFuel: Double,
    val shape: String = "",
    val rejoin: FuelRejoin? = null,
) {
    val key get() = projection.key
    val offer get() = projection.offer
    val station get() = projection.station
    val rejoinKm get() = rejoin?.km ?: projection.roadKm
    val rejoinFuel get() = rejoin?.fuel ?: projection.roadFuel
    val detourKm get() = outKm + backKm - (rejoinKm - projection.roadKm)
    val detourMin get() = outMin + backMin - ((rejoin?.minutes ?: projection.roadMin) - projection.roadMin)
    val detourFuel get() = outFuel + backFuel - (rejoinFuel - projection.roadFuel)
    val accessFuel get() = projection.roadFuel + outFuel

    companion object {
        fun verified(candidate: FuelProjection, access: PlanApi.FuelAccess, maxDetour: Double, consumption: FuelConsumption, rejoin: FuelRejoin? = null): FuelCandidate? {
            val distance = access.outKm + access.backKm
            if (distance - ((rejoin?.km ?: candidate.roadKm) - candidate.roadKm) > maxDetour) return null
            // Reject a snap that unexpectedly jumps onto the nearby through road.
            if (distance + 0.03 < candidate.straightKm * 1.5) return null
            fun estimate(km: Double, minutes: Double) = consumption.amount(km, if (minutes > 0) km * 60 / minutes else 45.0)
            return FuelCandidate(candidate, access.outKm, access.backKm, access.outMin, access.backMin,
                estimate(access.outKm, access.outMin), estimate(access.backKm, access.backMin), access.shape, rejoin)
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
    val score: Double = 0.0,
) {
    val detourKm get() = stops.sumOf { it.candidate.detourKm }
    val extraMin get() = stops.sumOf { it.candidate.detourMin + it.serviceMin }
    val totalMin get() = driveMin + stops.sumOf { it.serviceMin }
    val purchased get() = stops.sumOf { it.purchased }
    val purchaseCost get() = if (stops.all { it.cost != null }) stops.sumOf { it.cost!! } else null
}

private fun serviceTime(amount: Double) = 5.0 + amount / 8
private const val EPS = 1e-7

/** Forward search over verified stops. Labels preserve exact fuel balances and remove dominated arrivals. */
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
        val labels = Array(terminal) { java.util.TreeMap<Double, Label>() }
        var best: Label? = null

        fun record(index: Int, label: Label) {
            if (index == terminal) {
                if (best == null || label.score < best!!.score - EPS) best = label
                return
            }
            val arrivals = labels[index]
            val previous = arrivals[label.remaining]
            if (previous != null && previous.score <= label.score + EPS) return
            if (locked.isEmpty()) {
                val fuller = arrivals.higherEntry(label.remaining)
                if (fuller != null && fuller.value.score <= label.score + EPS) return
                var lower = arrivals.lowerEntry(label.remaining)
                while (lower != null && lower.value.score >= label.score - EPS) {
                    arrivals.remove(lower.key)
                    lower = arrivals.lowerEntry(label.remaining)
                }
            }
            arrivals[label.remaining] = label
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
            val arrivals = labels[index].values.sortedWith(compareByDescending<Label> { it.remaining }.thenBy { it.score })
            val active = if (arrivals.size > 512 && locked.isNotEmpty()) {
                // Preserve both a low-score and a high-fuel arrival in each group for edited trips.
                arrivals.chunked((arrivals.size + 255) / 256).flatMap { group -> listOf(group.first(), group.minBy { it.score }) }.distinct()
            } else arrivals
            val last = nextLock(index)
            val uses = (index + 1..last).filter { next -> next == terminal ||
                (nodes[next].projection.roadKm + EPS >= node.rejoinKm &&
                    (nodes[next].station.id != node.station.id || nodes[next].projection.roadKm - node.projection.roadKm > 0.1))
            }.map { next -> next to (access(next) - node.rejoinFuel + node.backFuel) }
                .filter { (_, needed) -> needed + reserve <= capacity + EPS }
            // Amounts that reach some later location at reserve, plus a full tank. The search can skip
            // intermediate purchases or carry cheaper fuel across a more expensive stop.
            val targets = (uses.map { (_, needed) -> (reserve + needed).coerceAtMost(capacity) } + capacity).distinct().sorted()
            val edit = locked[node.key]
            var bestScore = Double.POSITIVE_INFINITY

            for (arrival in active) {
                if (locked.isEmpty() && arrival.score >= bestScore - EPS) continue
                bestScore = minOf(bestScore, arrival.score)
                val departures = edit?.amount?.let { listOf(arrival.remaining + it) } ?: if (edit != null) {
                    (targets + (arrival.remaining + 0.01)).distinct()
                } else targets
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
        val used = roadFuel + stops.sumOf { it.candidate.detourFuel }
        return FuelPlan(used, result.remaining, stops, roadMin + stops.sumOf { it.candidate.detourMin },
            roadKm + stops.sumOf { it.candidate.detourKm }, consumedCost(used, initial, referencePrice, stops), result.score)
    }

    private fun cost(strategy: FuelStrategy, stop: FuelStop, referencePrice: Double?, now: Instant): Double {
        val node = stop.candidate
        val minutes = node.detourMin + stop.serviceMin
        // Unquoted fuel stays usable for feasibility. This internal penalty never becomes a displayed price.
        val purchase = stop.cost ?: (referencePrice?.times(2) ?: 5.0) * stop.purchased
        val stale = if (node.offer.price != null && !node.offer.fresh(now)) 0.30 * stop.purchased else 0.0
        return when (strategy) {
            FuelStrategy.ECONOMY -> purchase + stale
            FuelStrategy.BALANCED -> purchase + stale + 0.40 * minutes
            FuelStrategy.FAST -> minutes
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
