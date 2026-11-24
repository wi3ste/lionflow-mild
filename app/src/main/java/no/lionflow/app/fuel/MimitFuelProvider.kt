package no.lionflow.app.fuel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.lionflow.app.api.Http
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class MimitFuelProvider : FuelStationProvider {
    override val source = SOURCE
    override val countries = setOf("IT")
    private val client = Http.client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun load(): FuelDataset = withContext(Dispatchers.IO) {
        val registry = download("anagrafica_impianti_attivi.csv")
        val prices = download("prezzo_alle_8.csv")
        parse(registry, prices).also {
            if (it.stations.size < 5_000 || it.quotedStations < 5_000) {
                throw IOException("Incomplete MIMIT export")
            }
        }
    }

    private fun download(file: String): ByteArray {
        val request = Request.Builder().url("https://www.mimit.gov.it/images/exportCSV/$file")
            .header("User-Agent", Http.CONTACT_AGENT).build()
        return client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) throw IOException("MIMIT HTTP ${response.code}")
            val source = body.source()
            if (source.request(MAX_BYTES + 1)) throw IOException("MIMIT export exceeds size limit")
            source.readByteArray()
        }
    }

    companion object {
        const val SOURCE = "MIMIT"
        const val PAGE = "https://www.mimit.gov.it/it/open-data/elenco-dataset/carburanti-prezzi-praticati-e-anagrafica-degli-impianti"
        private const val MAX_BYTES = 32L * 1024 * 1024
        internal val italy: ZoneId = ZoneId.of("Europe/Rome")
        private val communicatedAt = DateTimeFormatter.ofPattern("d/M/uuuu H:mm:ss", Locale.ITALY)
            .withResolverStyle(ResolverStyle.STRICT)
        private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")

        fun parse(registryBytes: ByteArray, priceBytes: ByteArray): FuelDataset {
            val registry = Csv(registryBytes, setOf("idimpianto", "latitudine", "longitudine"))
            val prices = Csv(priceBytes, setOf("idimpianto", "desccarburante", "prezzo", "isself", "dtcomu"))
            val stations = linkedMapOf<String, FuelStation>()
            registry.forEach { row ->
                val id = row["idimpianto"].toLongOrNull()?.takeIf { it > 0 }?.toString() ?: return@forEach
                val lat = number(row["latitudine"])?.takeIf { it in 35.0..48.0 } ?: return@forEach
                val lon = number(row["longitudine"])?.takeIf { it in 6.0..19.0 } ?: return@forEach
                val station = FuelStation("mimit:$id", row["nomeimpianto"], row["bandiera"], row["gestore"],
                    row["indirizzo"], row["comune"], row["provincia"], "IT", lat, lon, row["tipoimpianto"], SOURCE, emptyList())
                val current = stations[id]
                if (current == null || (current.title.isBlank() && station.title.isNotBlank())) stations[id] = station
            }
            val joined = HashMap<String, MutableMap<Pair<FuelGrade, FuelService?>, FuelOffer>>()
            prices.forEach { row ->
                val id = row["idimpianto"].toLongOrNull()?.toString() ?: return@forEach
                if (id !in stations) return@forEach
                val grade = FuelGrade.from(row["desccarburante"]) ?: return@forEach
                val service = when (normalized(row["isself"])) {
                    "1", "true", "self", "selfservice" -> FuelService.SELF
                    "0", "false", "servito" -> FuelService.SERVED
                    else -> null
                }
                val value = number(row["prezzo"])?.takeIf { it in 0.2..6.0 }
                val whenReported = runCatching { LocalDateTime.parse(row["dtcomu"], communicatedAt).atZone(italy).toInstant() }.getOrNull()
                    ?: runCatching { Instant.parse(row["dtcomu"]) }.getOrNull()
                val key = grade to service
                val byMode = joined.getOrPut(id) { linkedMapOf() }
                val old = byMode[key]
                if (old == null || newer(whenReported, old.reportedAt) ||
                    (whenReported == old.reportedAt && old.price == null && value != null)) {
                    byMode[key] = FuelOffer(grade, service, value, "EUR", whenReported)
                }
            }
            return FuelDataset(stations.map { (id, station) -> station.copy(offers = joined[id]?.values?.toList().orEmpty()) }, prices.date)
        }

        private fun newer(a: Instant?, b: Instant?) = a != null && (b == null || a > b)
        private fun number(text: String) = text.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)

        private fun decode(bytes: ByteArray): String = try {
            val charset = when {
                bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            bytes.toString(charset("windows-1252"))
        }

        private class Csv(bytes: ByteArray, required: Set<String>) {
            private val rows = decode(bytes).lineSequence().iterator()
            private var delimiter = '|'
            private lateinit var columns: Map<String, Int>
            var date: LocalDate? = null
                private set

            init {
                repeat(12) {
                    if (!::columns.isInitialized && rows.hasNext()) {
                        val line = rows.next()
                        if (line.startsWith("Estrazione", ignoreCase = true)) {
                            date = datePattern.find(line)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        }
                        for (separator in listOf('|', ';', '\t', ',')) {
                            val names = fields(line, separator).map(::normalized)
                            if (names.containsAll(required)) {
                                delimiter = separator
                                columns = names.withIndex().associate { it.value to it.index }
                                break
                            }
                        }
                    }
                }
                if (!::columns.isInitialized) throw IOException("Unsupported MIMIT export header")
            }

            fun forEach(action: (Row) -> Unit) {
                rows.forEach { line -> if (line.isNotBlank()) action(Row(fields(line, delimiter), columns)) }
            }
        }

        private class Row(private val fields: List<String>, private val columns: Map<String, Int>) {
            operator fun get(name: String): String = columns[name]?.let { fields.getOrNull(it) }?.trim().orEmpty()
        }

        private fun fields(line: String, separator: Char): List<String> {
            if ('"' !in line) return line.split(separator)
            val fields = ArrayList<String>()
            val field = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i++]
                when {
                    quoted && c == '"' && line.getOrNull(i) == '"' -> { field.append('"'); i++ }
                    quoted && c == '"' -> quoted = false
                    c == '"' && field.isBlank() -> quoted = true
                    !quoted && c == separator -> { fields.add(field.toString()); field.clear() }
                    else -> field.append(c)
                }
            }
            fields.add(field.toString())
            return fields
        }
    }
}
