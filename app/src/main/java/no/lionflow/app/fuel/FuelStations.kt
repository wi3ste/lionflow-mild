package no.lionflow.app.fuel

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor

internal enum class FuelGrade(val label: String, val unit: String) {
    PETROL("Petrol", "L"), DIESEL("Diesel", "L"), LPG("LPG", "L"), CNG("CNG", "kg");

    companion object {
        fun from(raw: String): FuelGrade? = when (normalized(raw)) {
            "benzina", "petrol", "gasoline", "unleaded", "benzinasenzapiombo" -> PETROL
            "gasolio", "diesel", "dieselstandard", "gasoliostandard" -> DIESEL
            "gpl", "lpg", "autogas" -> LPG
            "metano", "metanoauto", "metanogassoso", "cng", "gnc" -> CNG
            else -> null
        }
    }
}

internal fun normalized(text: String): String =
    Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).filter { it in 'a'..'z' || it in '0'..'9' }

internal enum class FuelService(val label: String) { SELF("Self-service"), SERVED("Attended") }
internal enum class FuelServiceFilter(val label: String) {
    ANY("Any service"), SELF("Self-service"), SERVED("Attended");

    fun accepts(service: FuelService?) = when (this) {
        ANY -> true
        SELF -> service == FuelService.SELF
        SERVED -> service == FuelService.SERVED
    }
}

internal data class FuelOffer(
    val grade: FuelGrade,
    val service: FuelService?,
    val price: Double?,
    val currency: String,
    val reportedAt: Instant?,
) {
    fun fresh(now: Instant): Boolean =
        reportedAt?.let { !it.isBefore(now.minusSeconds(7 * 86400L)) && !it.isAfter(now.plusSeconds(3600)) } == true
}

internal data class FuelStation(
    val id: String,
    val name: String,
    val brand: String,
    val operator: String,
    val address: String,
    val town: String,
    val province: String,
    val country: String,
    val lat: Double,
    val lon: Double,
    val kind: String,
    val source: String,
    val offers: List<FuelOffer>,
) {
    val title: String get() = name.ifBlank { brand.ifBlank { operator } }
    val location: String get() = listOf(address, town, province).filter(String::isNotBlank).joinToString(", ")

    fun offer(grade: FuelGrade, filter: FuelServiceFilter, now: Instant): FuelOffer? =
        offers.asSequence().filter { it.grade == grade && filter.accepts(it.service) }
            .minWithOrNull(compareBy<FuelOffer> { it.price == null }
                .thenByDescending { it.fresh(now) }.thenBy { it.price ?: Double.MAX_VALUE }.thenBy { it.service })
}

internal data class FuelDataset(val stations: List<FuelStation>, val priceDate: LocalDate?) {
    val quotedStations = stations.count { station -> station.offers.any { it.price != null } }
}

internal interface FuelStationProvider {
    val source: String
    val countries: Set<String>
    suspend fun load(): FuelDataset
}

internal class StationIndex(val stations: List<FuelStation>) {
    private val cells = stations.groupBy { cell(it.lat) to cell(it.lon) }
    private val southBound = stations.minOfOrNull { it.lat } ?: 90.0
    private val northBound = stations.maxOfOrNull { it.lat } ?: -90.0

    fun within(south: Double, west: Double, north: Double, east: Double): Sequence<FuelStation> {
        if (south > northBound || north < southBound) return emptySequence()
        if (east < west) return within(south, west, north, 180.0) + within(south, -180.0, north, east)
        val ys = cell(south)..cell(north)
        val xs = cell(west)..cell(east)
        val buckets = if (ys.count().toLong() * xs.count() > cells.size) {
            cells.asSequence().filter { (k, _) -> k.first in ys && k.second in xs }.flatMap { it.value.asSequence() }
        } else {
            ys.asSequence().flatMap { y -> xs.asSequence().flatMap { x -> cells[y to x].orEmpty().asSequence() } }
        }
        return buckets.filter { it.lat in south..north && it.lon in west..east }
    }

    private fun cell(value: Double) = floor(value * 10).toInt()
}
