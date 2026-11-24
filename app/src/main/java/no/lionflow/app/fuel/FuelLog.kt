package no.lionflow.app.fuel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.Vehicle
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.plan.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

internal data class Refuel(
    val id: String = UUID.randomUUID().toString(),
    val at: Long,
    val grade: FuelGrade,
    val amount: Double?,
    val total: Double? = null,
    val currency: String = "EUR",
    val stationId: String? = null,
    val stationName: String? = null,
    val odometer: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val before: Double? = null,
    val after: Double? = null,
    val rangeBefore: Double? = null,
    val rangeAfter: Double? = null,
    val full: Boolean? = null,
    val notes: String = "",
    val source: RefuelSource = RefuelSource.MANUAL,
    val estimatedPrice: Double? = null,
    val quantityEstimated: Boolean = false,
) {
    val unitPrice get() = if (amount != null && !quantityEstimated) total?.div(amount) else null
    val confirmed get() = source != RefuelSource.DETECTED

    internal fun json() = JSONObject().put("id", id).put("at", at).put("grade", grade.name)
        .put("amount", amount).put("total", total).put("currency", currency).put("station", stationId).put("name", stationName)
        .put("odometer", odometer).put("lat", lat).put("lon", lon).put("before", before).put("after", after)
        .put("rangeBefore", rangeBefore).put("rangeAfter", rangeAfter).put("full", full).put("notes", notes)
        .put("source", source.name).put("estimatedPrice", estimatedPrice).put("estimatedAmount", quantityEstimated)

    companion object {
        fun parse(j: JSONObject) = Refuel(
            id = j.getString("id"), at = j.getLong("at"), grade = FuelGrade.valueOf(j.getString("grade")),
            amount = j.finite("amount")?.takeIf { it > 0 }, total = j.finite("total")?.takeIf { it >= 0 },
            currency = j.optString("currency", "EUR"), stationId = j.nonBlank("station"), stationName = j.nonBlank("name"),
            odometer = j.finite("odometer"), lat = j.finite("lat"), lon = j.finite("lon"), before = j.finite("before"),
            after = j.finite("after"), rangeBefore = j.finite("rangeBefore"), rangeAfter = j.finite("rangeAfter"),
            full = j.optBoolean("full").takeUnless { j.isNull("full") }, notes = j.optString("notes"),
            source = RefuelSource.valueOf(j.optString("source", "MANUAL")), estimatedPrice = j.finite("estimatedPrice"),
            quantityEstimated = j.optBoolean("estimatedAmount"),
        )
    }
}

internal enum class RefuelSource(val label: String) {
    MANUAL("Manual entry"), DETECTED("Estimated refuel · review"), CONFIRMED("Confirmed detection"),
}

internal fun JSONObject.finite(key: String) = optDouble(key).takeIf(Double::isFinite)
internal fun JSONObject.nonBlank(key: String) = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

internal data class FuelGauge(
    val at: Long, val pct: Double, val odometer: Double, val moving: Boolean,
    val range: Double? = null, val lat: Double? = null, val lon: Double? = null,
) {
    fun json() = JSONObject().put("at", at).put("pct", pct).put("odo", odometer).put("moving", moving)
        .put("range", range).put("lat", lat).put("lon", lon)

    companion object {
        fun parse(j: JSONObject) = FuelGauge(j.getLong("at"), j.getDouble("pct"), j.getDouble("odo"), j.getBoolean("moving"),
            j.finite("range"), j.finite("lat"), j.finite("lon"))
    }
}

internal data class RefuelDetection(val baseline: FuelGauge, val rise: FuelGauge? = null)
internal data class RefuelObservation(val state: RefuelDetection, val event: Refuel? = null)

internal fun observeFuel(
    previous: RefuelDetection?, reading: FuelGauge, grade: FuelGrade, capacity: Double?,
): RefuelObservation {
    val base = previous?.baseline ?: return RefuelObservation(RefuelDetection(reading))
    val rise = previous.rise
    if (reading.at <= (rise ?: base).at) return RefuelObservation(previous)
    val elapsed = reading.at - base.at
    val km = reading.odometer - base.odometer
    val change = reading.pct - base.pct
    val compatible = elapsed in 60_000L..36 * 3_600_000L && km in 0.0..4.0 && !reading.moving &&
        change >= 8.0 && (capacity == null || capacity * change / 100 >= 3.0)
    if (!compatible) return RefuelObservation(RefuelDetection(reading))
    if (rise == null) return RefuelObservation(previous.copy(rise = reading))
    if (reading.at - rise.at < 60_000L) return RefuelObservation(previous)
    if (abs(reading.pct - rise.pct) > 3.0 || reading.odometer - rise.odometer !in 0.0..2.0) {
        return RefuelObservation(previous.copy(rise = reading))
    }
    val event = Refuel(id = "gauge-${rise.at}", at = rise.at, grade = grade, amount = capacity?.times((rise.pct - base.pct) / 100),
        odometer = rise.odometer, lat = rise.lat, lon = rise.lon, before = base.pct, after = rise.pct, rangeBefore = base.range,
        rangeAfter = rise.range, full = true.takeIf { rise.pct >= 99 }, source = RefuelSource.DETECTED, quantityEstimated = true)
    return RefuelObservation(RefuelDetection(reading), event)
}

internal fun Refuel.associate(index: StationIndex?, now: Instant): Refuel {
    val latitude = lat ?: return this
    val longitude = lon ?: return this
    val candidates = index?.within(latitude - .002, longitude - .003, latitude + .002, longitude + .003)
        ?.filter { station -> station.offers.any { it.grade == grade } }
        ?.map { it to Geo.km(latitude, longitude, it.lat, it.lon) }?.filter { it.second <= 0.15 }
        ?.sortedBy { it.second }?.take(2)?.toList().orEmpty()
    val (station, distance) = candidates.firstOrNull() ?: return this
    if (distance > .08 || candidates.getOrNull(1)?.let { it.second - distance < .08 } == true) return this
    val offer = station.offer(grade, FuelServiceFilter.ANY, now)
    val price = offer?.price?.takeIf {
        offer.reportedAt?.let { stamp -> stamp <= Instant.ofEpochMilli(at).plusSeconds(3600) && offer.fresh(Instant.ofEpochMilli(at)) } == true
    }
    return copy(stationId = station.id, stationName = station.title, estimatedPrice = price)
}

internal class FuelLog(private val context: Context) {
    private val prefs = context.getSharedPreferences("fuel_log", Context.MODE_PRIVATE)
    // Samples are transient evidence. Restore entries without reusing readings from the previous device.
    private val observations = android.util.AtomicFile(File(context.noBackupFilesDir, "fuel-gauges.json"))
    private val revisions = MutableStateFlow(0)
    val changes = revisions.asStateFlow()

    @Synchronized
    fun entries(vin: String): List<Refuel> {
        val json = runCatching { JSONArray(prefs.getString("entries.v1.$vin", "[]")) }.getOrNull() ?: return emptyList()
        return (0 until json.length()).mapNotNull { index -> runCatching { Refuel.parse(json.getJSONObject(index)) }.getOrNull() }
            .sortedByDescending { it.at }
    }

    @Synchronized
    fun save(vin: String, entry: Refuel) {
        if (vin.isBlank()) return
        write(vin, entries(vin).filterNot { it.id == entry.id } + entry)
    }

    @Synchronized
    fun remove(vin: String, id: String) = write(vin, entries(vin).filterNot { it.id == id })

    @Synchronized
    fun observe(vin: String, grade: FuelGrade, gauge: FuelGauge, tank: Double?, index: StationIndex?) {
        if (vin.isBlank() || gauge.pct !in 0.0..100.0 || !gauge.odometer.isFinite() || gauge.odometer < 0) return
        val all = gauges()
        val key = "$vin.${grade.name}"
        val previous = all.optJSONObject(key)?.let { row ->
            runCatching {
                RefuelDetection(FuelGauge.parse(row.getJSONObject("baseline")), row.optJSONObject("rise")?.let(FuelGauge::parse))
            }.getOrNull()
        }
        val observation = observeFuel(previous, gauge, grade, tank)
        if (observation.state == previous) return
        val event = observation.event
        if (event != null && entries(vin).none {
                it.id == event.id || (it.grade == event.grade && abs(it.at - event.at) <= 45 * 60_000L)
            }) {
            save(vin, event.associate(index, Instant.ofEpochMilli(gauge.at)))
        }
        val row = JSONObject().put("baseline", observation.state.baseline.json()).put("rise", observation.state.rise?.json())
        val bytes = all.put(key, row).toString().encodeToByteArray()
        val stream = observations.startWrite()
        try {
            stream.write(bytes)
            observations.finishWrite(stream)
        } catch (e: Exception) {
            observations.failWrite(stream)
            throw e
        }
    }

    private fun gauges() = runCatching { JSONObject(observations.readFully().decodeToString()) }.getOrElse { JSONObject() }

    private fun write(vin: String, rows: List<Refuel>) {
        val array = JSONArray()
        rows.forEach { array.put(it.json()) }
        prefs.edit().putString("entries.v1.$vin", array.toString()).apply()
        revisions.value++
    }

    fun export(vin: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "lionflow-refuels.csv")
        fun field(value: Any?): String {
            var text = value?.toString().orEmpty()
            if (value is String && text.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) text = "'$text"
            return "\"" + text.replace("\"", "\"\"") + "\""
        }
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("date,grade,unit,quantity,quantity_estimated,unit_price,confirmed_total,currency,station_id,station,latitude,longitude,odometer_km,level_before_pct,level_after_pct,range_before_km,range_after_km,full,source,mimit_estimate,notes")
            entries(vin).forEach { row ->
                val values = listOf(Instant.ofEpochMilli(row.at), row.grade, row.grade.unit, row.amount, row.quantityEstimated,
                    row.unitPrice, row.total.takeIf { row.confirmed }, row.currency, row.stationId, row.stationName, row.lat, row.lon,
                    row.odometer, row.before, row.after, row.rangeBefore, row.rangeAfter, row.full, row.source, row.estimatedPrice, row.notes)
                out.appendLine(values.joinToString(",") { field(it) })
            }
        }
        return file
    }
}

internal data class FuelEconomics(
    val spend: Double?, val quantity: Double?, val averagePrice: Double?, val distance: Double,
    val fuelPer100: Double?, val fuelUsed: Double?, val byStation: Map<String, Int>, val estimated: Int,
) {
    fun consumptionCost(unitPrice: Double?) = fuelUsed?.let { used -> unitPrice?.times(used) }
    fun costPerKm(unitPrice: Double?) = consumptionCost(unitPrice)?.takeIf { distance > 0 }?.div(distance)
}

internal fun fuelEconomics(
    entries: List<Refuel>, trips: List<Trip>, from: Long, until: Long, grade: FuelGrade, currency: String = "EUR",
): FuelEconomics {
    val period = entries.filter { it.grade == grade && it.at in from until until }
    val confirmed = period.filter { it.confirmed && it.currency == currency }
    val priced = confirmed.filter { it.total != null }
    val quantities = period.filter { it.confirmed && !it.quantityEstimated }.mapNotNull { it.amount }
    val matched = priced.filter { it.amount != null && !it.quantityEstimated }
    val avg = matched.sumOf { it.amount ?: 0.0 }.takeIf { it > 0 }?.let { matched.sumOf { row -> row.total ?: 0.0 } / it }
    val selectedTrips = trips.filter { it.startMillis?.let { at -> at in from until until } == true && it.distanceKm.isFinite() && it.distanceKm > 0 }
        .distinctBy { it.id }
    val distance = selectedTrips.sumOf { it.distanceKm }
    val credible = if (grade.unit == "L") selectedTrips.filter { it.calibrationLPer100 != null } else emptyList()
    val measuredKm = credible.sumOf { it.distanceKm }
    val liters = credible.sumOf { requireNotNull(it.calibrationLPer100) * it.distanceKm / 100 }.takeIf { measuredKm > 0 }
    return FuelEconomics(priced.takeIf { it.isNotEmpty() }?.sumOf { it.total ?: 0.0 }, quantities.takeIf { it.isNotEmpty() }?.sum(),
        avg, distance, liters?.let { it * 100 / measuredKm }, liters?.takeIf { measuredKm == distance },
        period.filter { it.confirmed }.groupingBy { it.stationName.orEmpty() }.eachCount(),
        period.count { !it.confirmed || it.quantityEstimated })
}

/** Cost basis after a known empty-tank purchase and subsequent purchases with gauge data. */
internal fun tankUnitCost(entries: List<Refuel>, grade: FuelGrade, capacity: Double, asOf: Long): Double? {
    var unit: Double? = null
    var previousAfter: Double? = null
    for (row in entries.filter { it.grade == grade && it.at <= asOf }.sortedBy { it.at }) {
        val bought = row.amount
        val after = row.after?.takeIf { it in 0.0..100.0 }?.times(capacity / 100)
        val before = row.before?.takeIf { it in 0.0..100.0 }?.times(capacity / 100)
            ?: after?.let { if (bought == null) null else (it - bought).coerceAtLeast(0.0) }
        val price = row.unitPrice
        val tolerance = capacity * 0.04
        unit = when {
            !row.confirmed || price == null || row.currency != "EUR" || bought == null || before == null || after == null ||
                abs(after - before - bought) > tolerance || bought > capacity + tolerance -> null
            before <= capacity * 0.02 -> price
            unit != null && previousAfter != null && before <= previousAfter + tolerance -> (before * unit + bought * price) / (before + bought)
            else -> null
        }
        previousAfter = after
    }
    return unit
}

internal suspend fun LionFlowApp.observeRefuel(vehicle: Vehicle, status: VehicleStatus) {
    val pct = status.fuelPct ?: return
    val odometer = status.odometerKm ?: return
    val time = status.fuelAtMillis ?: status.updatedAtMillis ?: return
    val now = System.currentTimeMillis()
    if (time > now + 60_000 || time < now - 48 * 3_600_000L) return
    val grade = FuelGrade.from(vehicle.motorization)
        ?: FuelGrade.PETROL.takeIf { vehicle.specifiedPetrolTankLiters != null }
        ?: settings.raw("fuel.grade.${vehicle.vin}").let { name -> FuelGrade.entries.find { it.name == name } }
        ?: return
    val capacity = settings.raw("fuel.capacity.${grade.name}.${vehicle.vin}").replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 1.0..300.0 } ?: defaultTankCapacity(vehicle, grade)
    val latitude = status.lat
    val longitude = status.lon
    val hasPosition = status.positionAtMillis?.let { abs(it - time) <= 15 * 60_000L } == true &&
        latitude != null && latitude in -90.0..90.0 && longitude != null && longitude in -180.0..180.0 &&
        (latitude != 0.0 || longitude != 0.0)
    val gauge = FuelGauge(time, pct, odometer, status.moving, status.fuelRangeKm,
        latitude.takeIf { hasPosition }, longitude.takeIf { hasPosition })
    withContext(Dispatchers.IO) {
        try {
            fuelLog.observe(vehicle.vin, grade, gauge, capacity, FuelCatalog.get(this@observeRefuel).state.value.snapshot?.index)
        } catch (e: java.io.IOException) {
            android.util.Log.w("FuelLog", "Could not store the fuel gauge observation", e)
        }
    }
}
