package no.lionflow.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reads trips exported from other apps: LionFlow's own CSV, the official MyPeugeot / MyCitroën / MyDS / MyOpel
 * app's CSV export (columns named in the app's language, ";" or "," between them) and its full ".myp" export (JSON).
 * Returns LionFlow's stored form: one JSON object per trip (id, start, end, km, min, kwh, avg, speed) and the tag
 * name, if the file had one.
 */
object TripImport {
    data class Row(val json: JSONObject, val tag: String?)

    private val fuelColumns = listOf("fuel_liters", "avg_l_per_100km", "fuel_units_estimated")
    private val fuelRate = Regex("""\b(?:l|liters?|litres?|litri)\s*/\s*100\s*km\b""")
    private val fuelTotal = Regex("""(?:\b(?:liters?|litres?|litri)\b|\(l\)|\[l]|_liters$)""")

    fun csv(trips: List<Trip>, tagName: (Trip) -> String?): String = buildString {
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        appendLine((listOf("id", "start", "end", "distance_km", "duration_min", "energy_kwh",
            "avg_kwh_per_100km", "avg_speed_kmh", "tag") + fuelColumns).joinToString(","))
        for (trip in trips) {
            appendLine(listOf(
                trip.id,
                trip.startMillis?.let { iso.format(java.util.Date(it)) }.orEmpty(),
                trip.endMillis?.let { iso.format(java.util.Date(it)) }.orEmpty(),
                String.format(Locale.US, "%.1f", trip.distanceKm),
                trip.durationSec?.let { String.format(Locale.US, "%.0f", it / 60) }.orEmpty(),
                trip.kwh?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
                trip.avgKwhPer100?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
                trip.avgSpeedKmh?.let { String.format(Locale.US, "%.0f", it) }.orEmpty(),
                tagName(trip)?.replace(Regex("[,\r\n\"]"), " ").orEmpty(),
                trip.liters?.toString().orEmpty(),
                trip.avgLPer100?.toString().orEmpty(),
                if (trip.liters != null || trip.avgLPer100 != null) trip.fuelUnitsEstimated.toString() else "",
            ).joinToString(","))
        }
    }

    fun restore(o: JSONObject): Trip = Trip(
        id = o.optString("id"),
        startMillis = o.optLong("start").takeIf { it > 0 },
        endMillis = o.optLong("end").takeIf { it > 0 },
        distanceKm = o.optDouble("km", 0.0),
        durationSec = o.optDouble("min", 0.0) * 60,
        kwh = o.optDouble("kwh", 0.0).takeIf { it > 0 },
        avgKwhPer100 = o.optDouble("avg", 0.0).takeIf { it > 0 },
        avgSpeedKmh = o.optDouble("speed", 0.0).takeIf { it > 0 },
        liters = fuel(o.optDouble("liters")),
        avgLPer100 = fuel(o.optDouble("avgL")),
        fuelUnitsEstimated = o.optBoolean("fuelUnitsEstimated"),
    )

    private fun fuel(value: Double?): Double? = value?.takeIf { it.isFinite() && it >= 0 }

    fun merge(reported: List<Trip>, imported: List<Trip>): List<Trip> {
        val byId = imported.associateBy { it.id }
        val uniqueReported = reported.distinctBy { it.id }
        val knownIds = uniqueReported.mapTo(HashSet()) { it.id }
        val matchedImports = HashSet<String>()
        val merged = uniqueReported.map { trip ->
            val saved = byId[trip.id] ?: imported.singleOrNull { candidate ->
                candidate.id !in knownIds && candidate.id !in matchedImports &&
                    sameDrive(trip, candidate) && uniqueReported.count { sameDrive(it, candidate) } == 1
            }
            if (saved == null) return@map trip
            matchedImports += saved.id
            if (trip.liters != null || trip.avgLPer100 != null) trip
            else trip.copy(liters = saved.liters, avgLPer100 = saved.avgLPer100,
                fuelUnitsEstimated = saved.fuelUnitsEstimated)
        }
        return merged + imported.filterNot { it.id in matchedImports }.distinctBy { it.id }
    }

    // CSV exports can round the times to minutes and the distance to tenths of a kilometer.
    private fun sameDrive(first: Trip, second: Trip): Boolean {
        val start = first.startMillis ?: return false
        val end = first.endMillis ?: return false
        return start / 60_000 == second.startMillis?.div(60_000) &&
            end / 60_000 == second.endMillis?.div(60_000) &&
            kotlin.math.abs(first.distanceKm - second.distanceKm) <= 0.051
    }

    fun parse(text: String): List<Row> {
        val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return if (t.startsWith("[") || t.startsWith("{")) parseMyp(t) else parseCsv(t)
    }

    // ---- .myp (JSON): [{ "vin": …, "trips": [ { "id", "startDateTime" (s), "endDateTime", "startMileage", "endMileage", … } ] }]
    private fun parseMyp(t: String): List<Row> {
        val root = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
        val out = ArrayList<Row>()
        for (i in 0 until root.length()) {
            val car = root.optJSONObject(i) ?: continue
            val trips = car.optJSONArray("trips") ?: continue
            val vin = car.optString("vin").takeLast(6)
            for (k in 0 until trips.length()) {
                val o = trips.optJSONObject(k) ?: continue
                val start = secOrMs(o, "startDateTime", "startDate", "start") ?: continue
                val km = o.optDouble("distance", Double.NaN).takeIf { !it.isNaN() }
                    ?: (o.optDouble("endMileage", Double.NaN) - o.optDouble("startMileage", Double.NaN)).takeIf { !it.isNaN() && it >= 0 }
                    ?: continue
                val end = secOrMs(o, "endDateTime", "endDate", "end")
                    ?: o.optDouble("duration", Double.NaN).takeIf { !it.isNaN() }?.let { start + (it * 1000).toLong() }
                    ?: start
                val kwh = listOf("electricConsumption", "consumptionElectric", "energyConsumption").firstNotNullOfOrNull { key -> o.optDouble(key, Double.NaN).takeIf { !it.isNaN() } } ?: 0.0
                out += Row(store("myp-$vin-${o.opt("id") ?: start}", start, end, km, kwh), null)
            }
        }
        return out
    }

    private fun secOrMs(o: JSONObject, vararg keys: String): Long? {
        for (k in keys) {
            if (!o.has(k)) continue
            val v = o.opt(k)
            val n = (v as? Number)?.toLong() ?: v?.toString()?.toLongOrNull()
            if (n != null && n > 0) return if (n < 10_000_000_000L) n * 1000 else n
            v?.toString()?.let { date(it) }?.let { return it }
        }
        return null
    }

    // ---- CSV
    private fun parseCsv(t: String): List<Row> {
        val lines = t.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val head = lines[0]
        val sep = listOf(';', '\t', ',').maxByOrNull { c -> head.count { it == c } } ?: ','
        val cols = split(head, sep).map { norm(it) }
        // LionFlow's own export.
        if (cols.firstOrNull() == "id" && cols.getOrNull(1) == "start") {
            val fuelIndices = fuelColumns.map(cols::indexOf)
            return lines.drop(1).mapNotNull { own(split(it, sep), fuelIndices) }
        }

        fun find(vararg words: String, not: List<String> = emptyList()) = cols.indexOfFirst { c -> words.any { c.contains(it) } && not.none { c.contains(it) } }
        val iStart = find("start", "debut", "départ", "depart", "beginn", "inicio", "partenza", "begin", "dato", "date", not = listOf("km", "mileage", "kilom", "odom", "lieu", "adresse", "address", "posit", "place", "lat", "lon"))
        val iEnd = find("end", "fin", "arrivee", "arrivée", "ende", "llegada", "arrivo", "slutt", not = listOf("km", "mileage", "kilom", "odom", "lieu", "adresse", "address", "posit", "place", "lat", "lon"))
        val iKm = find("distance", "km", "kilom", "afstand", "strecke", "lengde",
            not = listOf("100", "kwh", "consum", "speed", "vitesse"))
        val iDur = find("duree", "durée", "duration", "dauer", "duracion", "durata", "varighet", "tid", "time")
        val iKwh = cols.indexOfFirst { column ->
            listOf("kwh", "energ", "electri").any(column::contains) &&
                listOf("/100", "/ 100", "100 km", "100km", "moy", "avg", "average").none(column::contains) &&
                !fuelRate.containsMatchIn(column) && !fuelTotal.containsMatchIn(column)
        }
        val iAvg = cols.indexOfFirst { "kwh" in it && ("100" in it || "avg" in it) }
        val iFuelAvg = cols.indexOfFirst { fuelRate.containsMatchIn(it) }
        val iLiters = cols.indexOfFirst { fuelTotal.containsMatchIn(it) && !fuelRate.containsMatchIn(it) }
        if (iStart < 0 || iKm < 0) return emptyList()
        val out = ArrayList<Row>()
        for (line in lines.drop(1)) {
            val c = split(line, sep)
            val start = c.getOrNull(iStart)?.let { date(it) } ?: continue
            val km = num(c.getOrNull(iKm)) ?: continue
            val dur = c.getOrNull(iDur)?.let { minutes(it) }
            val end = c.getOrNull(iEnd)?.let { date(it) } ?: dur?.let { start + (it * 60_000).toLong() } ?: start
            val kwh = num(c.getOrNull(iKwh)) ?: num(c.getOrNull(iAvg))?.let { it * km / 100 } ?: 0.0
            val trip = store("csv-$start-${(km * 10).toLong()}", start, end, km, kwh)
            fuel(num(c.getOrNull(iLiters)))?.let { trip.put("liters", it) }
            fuel(num(c.getOrNull(iFuelAvg)))?.let { trip.put("avgL", it) }
            out += Row(trip, null)
        }
        return out
    }

    private fun own(c: List<String>, fuelIndices: List<Int>): Row? {
        if (c.size < 4) return null
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val start = c.getOrNull(1)?.let { try { iso.parse(it)?.time } catch (_: Exception) { null } } ?: 0L
        val end = c.getOrNull(2)?.let { try { iso.parse(it)?.time } catch (_: Exception) { null } } ?: 0L
        val j = JSONObject().put("id", c[0].ifEmpty { "imp-" + System.nanoTime() }).put("start", start).put("end", end)
            .put("km", c.getOrNull(3)?.toDoubleOrNull() ?: 0.0).put("min", c.getOrNull(4)?.toDoubleOrNull() ?: 0.0)
            .put("kwh", c.getOrNull(5)?.toDoubleOrNull() ?: 0.0).put("avg", c.getOrNull(6)?.toDoubleOrNull() ?: 0.0)
            .put("speed", c.getOrNull(7)?.toDoubleOrNull() ?: 0.0)
        fuel(c.getOrNull(fuelIndices[0])?.toDoubleOrNull())?.let { j.put("liters", it) }
        fuel(c.getOrNull(fuelIndices[1])?.toDoubleOrNull())?.let { j.put("avgL", it) }
        if (c.getOrNull(fuelIndices[2]).equals("true", ignoreCase = true)) j.put("fuelUnitsEstimated", true)
        return Row(j, c.getOrNull(8)?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun store(id: String, start: Long, end: Long, km: Double, kwh: Double): JSONObject {
        val min = ((end - start) / 60_000.0).coerceAtLeast(0.0)
        return JSONObject().put("id", id).put("start", start).put("end", end).put("km", km).put("min", min)
            .put("kwh", kwh).put("avg", if (km > 0 && kwh > 0) kwh / km * 100 else 0.0)
            .put("speed", if (min > 0) km / (min / 60) else 0.0)
    }

    private fun split(line: String, sep: Char): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) {
            when {
                ch == '"' -> q = !q
                ch == sep && !q -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(ch)
            }
        }
        out += sb.toString().trim()
        return out
    }

    private fun norm(s: String) = s.trim().trim('"').lowercase(Locale.ROOT)

    /** "12,5" / "12.5" / "12.5 km" → 12.5 */
    private fun num(s: String?): Double? = s?.replace(Regex("[^0-9,.\\-]"), "")?.let {
        val t = if (it.count { c -> c == ',' } == 1 && !it.contains('.')) it.replace(',', '.') else it.replace(",", "")
        t.toDoubleOrNull()
    }

    /** "1:05", "01:05:30", "65", "65 min", "1h05" → minutes */
    private fun minutes(s: String): Double? {
        val t = s.trim()
        Regex("^(\\d+):(\\d{1,2})(?::(\\d{1,2}))?$").find(t)?.let { m ->
            val (a, b, c) = m.destructured
            return if (c.isNotEmpty()) a.toDouble() * 60 + b.toDouble() + c.toDouble() / 60 else a.toDouble() * 60 + b.toDouble()
        }
        Regex("(\\d+)\\s*h\\s*(\\d+)?").find(t)?.let { m -> return m.groupValues[1].toDouble() * 60 + (m.groupValues[2].toDoubleOrNull() ?: 0.0) }
        return num(t)
    }

    private val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm",
        "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy HH:mm",
        "MM/dd/yyyy hh:mm a", "dd/MM/yyyy", "dd.MM.yyyy", "yyyy-MM-dd",
    )

    fun date(s: String): Long? {
        val t = s.trim().trim('"').replace(" - ", " ").replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        t.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000 else it }
        try { return java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (_: Exception) { }
        for (f in formats) {
            try {
                val p = java.text.ParsePosition(0)
                val d = SimpleDateFormat(f, Locale.US).apply { isLenient = false }.parse(t, p)
                if (d != null && p.index >= t.length - 1) return d.time
            } catch (_: Exception) { }
        }
        return null
    }
}
