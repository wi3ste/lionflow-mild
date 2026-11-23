package no.lionflow.app.api

import android.util.Log
import no.lionflow.app.BuildConfig
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/** Debug-only aggregate counts. Never include a payload, identifier, cursor, URL or exception message. */
internal object TripDiagnostics {
    val logger: ((String) -> Unit)?
        get() = if (BuildConfig.TRIP_API_DIAGNOSTICS) ({ line -> Log.i("LionFlowTrips", line); Unit }) else null

    fun date(millis: Long?): String =
        millis?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString() } ?: "-"

    fun dateRange(trips: List<Trip>): String {
        val starts = trips.mapNotNull { it.startMillis }
        return "firstUtc=${date(starts.minOrNull())} lastUtc=${date(starts.maxOrNull())}"
    }

    class Page {
        var hasTripArray = false
        var raw = 0
        var objects = 0
        var validStarts = 0
        var invalidDistances = 0
        var validTrips = 0
        var blankIds = 0
        var duplicateIds = 0
        var duplicateAnonymous = 0
        var droppedUnidentifiable = 0
        var missingStartedAt = 0
        var invalidStartedAt = 0
        var invalidCreatedAt = 0
        var invalidStoppedAt = 0
        var recoveredFromCreatedAt = 0
        var beforeRequest = 0
        private var earliest: Long? = null
        private var latest: Long? = null

        fun inspect(row: JSONObject, trip: Trip, cutoff: Long) {
            objects++
            if (trip.id.isEmpty()) blankIds++
            val distance = row.num("distance")
            val validDistance = distance != null && distance.isFinite() && distance >= 0
            if (!validDistance) invalidDistances++
            if (row.str("startedAt") == null) missingStartedAt++
            if (invalid(row, "startedAt")) invalidStartedAt++
            if (invalid(row, "createdAt")) invalidCreatedAt++
            if (invalid(row, "stoppedAt")) invalidStoppedAt++
            val start = trip.startMillis ?: return
            validStarts++
            if (validDistance) validTrips++
            if (VehicleStatus.parseIso(row.str("startedAt")) == null) recoveredFromCreatedAt++
            if (start < cutoff) beforeRequest++
            earliest = minOf(earliest ?: start, start)
            latest = maxOf(latest ?: start, start)
        }

        fun line(pageNumber: Int, hasNext: Boolean): String =
            "page=$pageNumber next=$hasNext tripArray=$hasTripArray raw=$raw objects=$objects nonObjects=${raw - objects} " +
                "validStarts=$validStarts noValidStart=${objects - validStarts} blankIds=$blankIds " +
                "invalidDistances=$invalidDistances validTrips=$validTrips " +
                "duplicateIds=$duplicateIds duplicateAnonymous=$duplicateAnonymous droppedUnidentifiable=$droppedUnidentifiable " +
                "missingStartedAt=$missingStartedAt invalidStartedAt=$invalidStartedAt invalidCreatedAt=$invalidCreatedAt " +
                "invalidStoppedAt=$invalidStoppedAt recoveredFromCreatedAt=$recoveredFromCreatedAt beforeRequest=$beforeRequest " +
                "firstUtc=${date(earliest)} lastUtc=${date(latest)}"

        private fun invalid(row: JSONObject, name: String): Boolean =
            row.str(name)?.let { VehicleStatus.parseIso(it) == null } == true
    }
}
