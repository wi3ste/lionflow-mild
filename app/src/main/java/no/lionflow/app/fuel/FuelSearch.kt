package no.lionflow.app.fuel

import java.time.Instant

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

/** Run another geographically mixed wave after the first viable result, then extend only on improvement. */
internal suspend fun <T> searchFuelWaves(
    shortlist: List<T>, initialWave: Int, evaluate: suspend (List<T>) -> Map<FuelStrategy, Double>,
) {
    if (shortlist.isEmpty()) {
        evaluate(emptyList())
        return
    }
    var previous = emptyMap<FuelStrategy, Double>()
    var start = 0
    var count = initialWave.coerceAtLeast(1)
    while (start < shortlist.size) {
        val end = minOf(shortlist.size, start + count)
        val scores = evaluate(shortlist.subList(start, end))
        if (scores.size == FuelStrategy.entries.size) {
            val improved = previous.size != scores.size || scores.any { (strategy, score) ->
                (previous[strategy] ?: Double.POSITIVE_INFINITY) - score > 0.20
            }
            if (!improved) return
            previous = scores
        }
        start = end
        count = 4
    }
}

internal fun FuelTripResult.withStrategy(strategy: FuelStrategy): FuelTripResult {
    val chosen = comparisons[strategy] ?: return this
    return copy(request = request.copy(constraints = request.constraints.copy(strategy = strategy)), plan = chosen)
}

internal fun firstFuelWave(required: Double, constraints: FuelConstraints): Int =
    (2 * kotlin.math.ceil(required / (constraints.capacity - constraints.reserve)).toInt() + 2).coerceIn(4, 10)
