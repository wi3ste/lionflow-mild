package no.lionflow.app.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import no.lionflow.app.model.Trip
import okhttp3.HttpUrl
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

internal fun tripPageUrl(endpoint: HttpUrl, from: String, to: String?, next: String?): HttpUrl {
    val first = endpoint.newBuilder()
        .addQueryParameter("pageSize", "60")
        .addQueryParameter("timestamps", "$from/${to.orEmpty()}")
        .build()
    if (next == null) return first
    val page = first.resolve(next)?.takeIf {
        it.scheme == first.scheme && it.host == first.host && it.port == first.port &&
            it.username.isEmpty() && it.password.isEmpty()
    } ?: throw ApiException(0, "Invalid trip page link")
    return page.newBuilder().apply {
        for (name in first.queryParameterNames - page.queryParameterNames) {
            first.queryParameterValues(name).forEach { addQueryParameter(name, it) }
        }
    }.build()
}

/** Read the existing seven-day UTC windows, following the server's next links. */
internal suspend fun loadWeeklyTrips(
    fromMillis: Long,
    nowMillis: Long,
    fetch: suspend (from: String, to: String?, next: String?) -> JSONObject,
): StellantisApi.TripsResult = coroutineScope {
    if (fromMillis > nowMillis) return@coroutineScope StellantisApi.TripsResult(emptyList(), false)
    val week = 7 * 86_400_000L
    val windows = buildList {
        var end: Long? = null
        var start = nowMillis - week
        while (true) {
            add(maxOf(start, fromMillis) to end)
            if (start <= fromMillis) break
            end = start
            start -= week
        }
    }
    fun iso(millis: Long) = Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString()
    val gate = Semaphore(4)
    val results = windows.map { (from, to) ->
        async {
            gate.withPermit {
                val rows = mutableListOf<JSONObject>()
                val seenLinks = HashSet<String>()
                val start = iso(from)
                val end = to?.let(::iso)
                var next: String? = null
                var pages = 0
                var noneFound = false
                do {
                    val page = try {
                        fetch(start, end, next)
                    } catch (e: ApiException) {
                        if (e.code != 404) throw e
                        noneFound = pages == 0
                        break
                    }
                    rows += page.obj("_embedded")?.optJSONArray("trips")?.objects().orEmpty()
                    next = page.obj("_links", "next")?.str("href")
                        ?.takeIf { it.isNotBlank() && seenLinks.add(it) }
                    pages++
                } while (next != null && pages < 20)
                rows to noneFound
            }
        }
    }.awaitAll()
    val trips = results.asSequence()
        .flatMap { it.first.asSequence() }
        .mapNotNull { row -> Trip.startOf(row)?.takeIf { it in fromMillis..nowMillis }?.let { it to row } }
        .distinctBy { (_, row) -> Trip.idOf(row) }
        .sortedByDescending { (start, _) -> start }
        .map { (_, row) -> row }
        .toList()
    StellantisApi.TripsResult(trips, results.all { it.second })
}
