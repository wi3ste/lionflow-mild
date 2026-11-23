package no.lionflow.app.api

import no.lionflow.app.model.Trip
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal data class TripHistory(val trips: List<Trip>, val noTrips: Boolean = false) {
    companion object {
        private const val MAX_PAGES = 50

        fun cutoff(nowMillis: Long, days: Int): Long =
            Instant.ofEpochMilli(nowMillis).minus(days.toLong(), ChronoUnit.DAYS).toEpochMilli()

        fun inPeriod(trips: List<Trip>, nowMillis: Long, days: Int): List<Trip> {
            val since = cutoff(nowMillis, days)
            return trips.filter { it.startMillis?.let { start -> start in since..nowMillis } == true }
        }

        suspend fun load(
            sinceMillis: Long,
            audit: ((String) -> Unit)? = null,
            fetch: suspend (sinceIso: String, pageToken: String?) -> JSONObject,
        ): TripHistory {
            val requestCutoff = Instant.ofEpochMilli(sinceMillis).truncatedTo(ChronoUnit.SECONDS)
            val since = requestCutoff.toString()
            audit?.invoke("request sinceUtc=${TripDiagnostics.date(requestCutoff.toEpochMilli())} upperBound=open pageSize=60 minDistanceKm=0.1")
            val trips = linkedMapOf<String, Trip>()
            val seenTokens = mutableSetOf<String>()
            var token: String? = null
            repeat(MAX_PAGES) { index ->
                val response = try {
                    fetch(since, token)
                } catch (e: ApiException) {
                    audit?.invoke("stop=requestFailed page=${index + 1} http=${e.code} retained=${trips.size}")
                    if (index == 0 && e.code == 404) return TripHistory(emptyList(), noTrips = true)
                    throw e
                }

                val rows = response.obj("_embedded")?.optJSONArray("trips")
                val page = audit?.let {
                    TripDiagnostics.Page().apply {
                        hasTripArray = rows != null
                        raw = rows?.length() ?: 0
                    }
                }
                for (row in rows?.objects().orEmpty()) {
                    val parsed = Trip.parse(row)
                    page?.inspect(row, parsed, requestCutoff.toEpochMilli())
                    val trip = identify(parsed)
                    if (trip == null) {
                        if (page != null) page.droppedUnidentifiable++
                        continue
                    }
                    val previous = trips[trip.id]
                    if (previous != null && page != null) {
                        if (parsed.id.isEmpty()) page.duplicateAnonymous++ else page.duplicateIds++
                    }
                    if (previous == null || (previous.startMillis == null && trip.startMillis != null)) {
                        trips[trip.id] = trip
                    }
                }
                val next = response.obj("_links", "next")
                if (audit != null && page != null) audit(page.line(index + 1, next != null))
                if (next == null) {
                    val sorted = trips.values.sortedByDescending { it.startMillis ?: Long.MIN_VALUE }
                    audit?.invoke("stop=lastPage pages=${index + 1} retained=${sorted.size} " +
                        "retainedWithoutDate=${sorted.count { it.startMillis == null }} ${TripDiagnostics.dateRange(sorted)}")
                    return TripHistory(sorted)
                }
                val nextToken = pageToken(next.str("href"))
                if (nextToken == null) {
                    audit?.invoke("stop=invalidCursor page=${index + 1} retained=${trips.size}")
                    throw ApiException(0, "Invalid trips pagination link")
                }
                if (!seenTokens.add(nextToken)) {
                    audit?.invoke("stop=repeatedCursor page=${index + 1} retained=${trips.size}")
                    throw ApiException(0, "Repeated trips pagination token")
                }
                token = nextToken
            }
            audit?.invoke("stop=pageLimit pages=$MAX_PAGES retained=${trips.size}")
            throw ApiException(0, "Trips pagination exceeded $MAX_PAGES pages")
        }

        internal fun pageToken(href: String?): String? {
            if (href.isNullOrBlank()) return null
            return runCatching {
                URI(href).rawQuery?.split('&')?.firstNotNullOfOrNull { parameter ->
                    val (name, value) = parameter.split('=', limit = 2).let { it.first() to it.getOrNull(1) }
                    value?.takeIf { name == "pageToken" && it.isNotBlank() }
                }?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        internal fun identify(trip: Trip): Trip? {
            if (trip.id.isNotEmpty()) return trip
            if (trip.startMillis == null && trip.endMillis == null) return null
            val key = listOf(trip.startMillis, trip.endMillis, trip.distanceKm, trip.durationSec).joinToString("|")
            return trip.copy(id = "anonymous-" + UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)))
        }
    }
}
