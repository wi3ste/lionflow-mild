package no.lionflow.app.fuel

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.lionflow.app.model.Trip
import no.lionflow.app.plan.Place
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

internal data class SavedFuelStop(
    val stationId: String, val name: String, val positionKm: Double,
    val lat: Double, val lon: Double, val amount: Double, val price: Double?,
    val locked: Boolean, val fixedAmount: Double?,
    val accessAtKm: Double,
) {
    fun json() = JSONObject().put("station", stationId).put("name", name).put("km", positionKm)
        .put("lat", lat).put("lon", lon).put("amount", amount).put("price", price).put("locked", locked).put("fixed", fixedAmount)
        .put("accessAtKm", accessAtKm)

    companion object {
        fun parse(j: JSONObject) = SavedFuelStop(j.getString("station"), j.getString("name"), j.getDouble("km"),
            j.getDouble("lat"), j.getDouble("lon"), j.getDouble("amount"), j.finite("price"), j.getBoolean("locked"), j.finite("fixed"),
            j.finite("accessAtKm") ?: j.getDouble("km"))
    }
}

internal data class SavedFuelTrip(
    val id: String,
    val request: FuelTripRequest,
    val calculated: Instant,
    val km: Double,
    val driveMin: Double,
    val totalMin: Double,
    val expected: Double,
    val expectedCost: Double?,
    val pumpCost: Double?,
    val stops: List<SavedFuelStop>,
    val expectedArrivalFuel: Double,
) {
    val title get() = request.from.name + (if (request.round) " ⇄ " else " → ") + request.to.name

    fun json() = JSONObject().put("id", id).put("request", request.json()).put("at", calculated.toString())
        .put("km", km).put("drive", driveMin).put("min", totalMin).put("expected", expected).put("value", expectedCost)
        .put("pump", pumpCost).put("stops", JSONArray(stops.map(SavedFuelStop::json))).put("arrivalFuel", expectedArrivalFuel)

    fun navigationUrl(stop: SavedFuelStop? = null): String {
        val destination = stop?.let { "${it.lat},${it.lon}" } ?: if (request.round) request.from.coords() else request.to.coords()
        val points = if (stop == null && request.round) listOf(request.to.coords()) else emptyList()
        return "https://www.google.com/maps/dir/?api=1&travelmode=driving&destination=$destination" +
            if (points.isEmpty()) "" else "&waypoints=${points.joinToString("%7C")}"
    }

    companion object {
        fun capture(result: FuelTripResult, locked: Map<String, FuelStopEdit>, id: String = UUID.randomUUID().toString()): SavedFuelTrip? {
            val plan = result.plan ?: return null
            val stops = plan.stops.mapIndexed { index, stop ->
                val station = stop.candidate.station
                val previousExtra = plan.stops.take(index).sumOf { it.candidate.detourKm }
                SavedFuelStop(station.id, station.title, stop.candidate.projection.roadKm, station.lat, station.lon,
                    stop.purchased, stop.candidate.offer.price, stop.candidate.key in locked, locked[stop.candidate.key]?.amount,
                    stop.candidate.entryKm + stop.candidate.outKm + previousExtra)
            }
            return SavedFuelTrip(id, result.request, result.priceTime, plan.totalKm, plan.driveMin, plan.totalMin,
                result.expectedUsed(plan), result.expectedCost(plan), plan.purchaseCost, stops, plan.remaining)
        }

        fun parse(j: JSONObject) = SavedFuelTrip(j.getString("id"), parseRequest(j.getJSONObject("request")),
            Instant.parse(j.getString("at")), j.getDouble("km"), j.getDouble("drive"), j.getDouble("min"), j.getDouble("expected"),
            j.finite("value"), j.finite("pump"), j.getJSONArray("stops").let { a -> List(a.length()) { SavedFuelStop.parse(a.getJSONObject(it)) } },
            j.getDouble("arrivalFuel"))
    }
}

private fun Place.coords() = "$lat,$lon"

private fun Place.json() = JSONObject().put("name", name).put("detail", detail).put("lat", lat).put("lon", lon).put("country", country)
private fun place(j: JSONObject) = Place(j.getString("name"), j.getString("detail"), j.getDouble("lat"), j.getDouble("lon"), j.optString("country"))

private fun FuelTripRequest.json() = JSONObject().put("from", from.json()).put("to", to.json()).put("via", JSONArray(via.map(Place::json)))
    .put("round", round).put("departure", departure.toString()).put("grade", grade.name).put("service", service.name)
    .put("profile", profile.name).put("manual", manualConsumption).put("safety", safety)
    .put("capacity", constraints.capacity).put("initial", constraints.initial).put("reserve", constraints.reserve)
    .put("strategy", constraints.strategy.name).put("detour", maxDetour)

private fun parseRequest(j: JSONObject) = FuelTripRequest(place(j.getJSONObject("from")), place(j.getJSONObject("to")),
    j.getJSONArray("via").let { a -> List(a.length()) { place(a.getJSONObject(it)) } }, j.getBoolean("round"), Instant.parse(j.getString("departure")),
    FuelGrade.valueOf(j.getString("grade")), FuelServiceFilter.valueOf(j.getString("service")), FuelProfile.valueOf(j.getString("profile")),
    j.finite("manual"), j.getDouble("safety"), FuelConstraints(j.getDouble("capacity"), j.getDouble("initial"), j.getDouble("reserve"),
        FuelStrategy.valueOf(j.getString("strategy"))), j.getDouble("detour"))

internal data class ActiveFuelTrip(
    val plan: SavedFuelTrip,
    val startedAt: Long,
    val startOdometer: Double?,
    val endedAt: Long? = null,
    val actualTripIds: Set<String> = emptySet(),
    val completedStops: Int = 0,
) {
    fun progress(odometer: Double?): Double? = startOdometer?.let { start ->
        odometer?.minus(start)?.takeIf { it.isFinite() && it >= 0 }
    }

    fun links(trips: List<Trip>): List<Trip> = trips.filter {
        val start = it.startMillis
        val end = it.endMillis
        start != null && end != null && start >= startedAt - 10 * 60_000L && end >= start &&
            end <= (endedAt ?: Long.MAX_VALUE).let { time -> if (time == Long.MAX_VALUE) time else time + 10 * 60_000L }
    }.distinctBy { it.id }.sortedBy { it.startMillis }

    fun json() = JSONObject().put("plan", plan.json()).put("start", startedAt).put("odo", startOdometer)
        .put("end", endedAt).put("actual", JSONArray(actualTripIds.toList())).put("completedStops", completedStops)

    companion object {
        fun parse(j: JSONObject) = ActiveFuelTrip(SavedFuelTrip.parse(j.getJSONObject("plan")), j.getLong("start"),
            j.finite("odo"), j.optLong("end").takeUnless { j.isNull("end") },
            j.optJSONArray("actual")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.toSet() }.orEmpty(),
            j.optInt("completedStops").coerceAtLeast(0))
    }
}

internal data class FuelActualComparison(val distance: Double, val durationMinutes: Double?, val liters: Double?)
internal fun actualFuelTrip(trips: List<Trip>, grade: FuelGrade): FuelActualComparison? {
    if (trips.isEmpty()) return null
    val distance = trips.sumOf { it.distanceKm }
    val duration = trips.takeIf { it.all { trip -> trip.durationSec?.let { sec -> sec.isFinite() && sec >= 0 } == true } }
        ?.sumOf { (it.durationSec ?: 0.0) / 60 }
    val liters = trips.takeIf { grade.unit == "L" && it.all { trip -> trip.calibrationLPer100 != null } }
        ?.sumOf { requireNotNull(it.calibrationLPer100) * it.distanceKm / 100 }
    return FuelActualComparison(distance, duration, liters)
}

internal class SavedFuelTrips(context: Context) {
    private val prefs = context.getSharedPreferences("fuel_trips", Context.MODE_PRIVATE)
    private val revision = MutableStateFlow(0)
    val changes = revision.asStateFlow()

    @Synchronized
    fun list(vin: String): List<SavedFuelTrip> {
        val a = runCatching { JSONArray(prefs.getString("plans.v1.$vin", "[]")) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { runCatching { SavedFuelTrip.parse(a.getJSONObject(it)) }.getOrNull() }
            .sortedByDescending { it.calculated }
    }

    @Synchronized
    fun save(vin: String, trip: SavedFuelTrip) = write(vin, list(vin).filterNot { it.id == trip.id } + trip)

    @Synchronized
    fun remove(vin: String, id: String) = write(vin, list(vin).filterNot { it.id == id })

    private fun write(vin: String, trips: List<SavedFuelTrip>) {
        if (vin.isBlank()) return
        prefs.edit().putString("plans.v1.$vin", JSONArray(trips.map(SavedFuelTrip::json)).toString()).apply()
        revision.value++
    }

    @Synchronized
    fun active(vin: String): ActiveFuelTrip? = prefs.getString("active.v1.$vin", null)?.let { value ->
        runCatching { ActiveFuelTrip.parse(JSONObject(value)) }.getOrNull()
    }

    @Synchronized
    fun setActive(vin: String, active: ActiveFuelTrip?) {
        if (vin.isBlank()) return
        prefs.edit().putString("active.v1.$vin", active?.json()?.toString()).apply()
        revision.value++
    }
}
