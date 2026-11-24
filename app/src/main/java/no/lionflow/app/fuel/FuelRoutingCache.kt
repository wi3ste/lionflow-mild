package no.lionflow.app.fuel

import no.lionflow.app.plan.Ferry
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.PlanApi
import no.lionflow.app.plan.RouteData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Reusable router replies. Prices, tank settings and decisions stay outside this cache. */
internal class FuelRoutingCache(
    directory: File,
    maxRouteBytes: Long = 24L * 1024 * 1024,
    maxAccessBytes: Long = 8L * 1024 * 1024,
) {
    private val routes = DiskEntries(File(directory, "routes"), maxRouteBytes, 16)
    private val accesses = DiskEntries(File(directory, "access"), maxAccessBytes, 2048)

    fun route(key: String, now: Instant): RouteData? = routes.read(key, now, ROUTE_TTL) {
        val size = readInt()
        check(size in 2..300_000)
        fun column() = DoubleArray(size) { readDouble().also { check(it.isFinite()) } }
        val route = RouteData(column(), column(), column(), column(), column())
        check(route.lat.all { it in -90.0..90.0 } && route.lon.all { it in -180.0..180.0 })
        check(route.km.ordered() && route.min.ordered() && route.totalKm > 0)
        check(route.speed.all { it >= 0 })
        route.ferries = List(readInt().also { check(it in 0..size) }) {
            Ferry(readUTF(), readDouble(), readDouble(), readDouble(), readDouble(), readDouble(), readInt(), readInt())
                .also { check(it.idx in 0 until size && (it.endIdx == -1 || it.endIdx in it.idx until size)) }
        }
        route
    }

    fun saveRoute(key: String, data: RouteData, now: Instant) = routes.write(key, now, ROUTE_TTL) {
        writeInt(data.size)
        for (column in listOf(data.lat, data.lon, data.km, data.min, data.speed)) column.forEach(::writeDouble)
        writeInt(data.ferries.size)
        for (ferry in data.ferries) {
            writeUTF(ferry.name)
            writeDouble(ferry.aLat)
            writeDouble(ferry.aLon)
            writeDouble(ferry.bLat)
            writeDouble(ferry.bLon)
            writeDouble(ferry.minutes)
            writeInt(ferry.idx)
            writeInt(ferry.endIdx)
        }
    }

    fun access(key: String, now: Instant): PlanApi.FuelAccessResult? = accesses.read(key, now, ACCESS_TTL) { remainingAge ->
        when (readUnsignedByte()) {
            0 -> PlanApi.FuelAccessResult.NoRoute.takeIf { remainingAge < NEGATIVE_TTL }
            1 -> {
                fun metric() = readDouble().also { check(it.isFinite() && it >= 0) }
                val outKm = metric()
                val backKm = metric()
                val outMin = metric()
                val backMin = metric()
                val shape = ByteArray(readInt().also { check(it in 1..MAX_SHAPE) }).also(::readFully)
                check(shape.all { it.toInt() in 63..126 })
                PlanApi.FuelAccessResult.Routed(PlanApi.FuelAccess(outKm, backKm, outMin, backMin, shape.decodeToString()))
            }
            else -> error("Unknown fuel routing reply")
        }
    }

    fun saveAccess(key: String, answer: PlanApi.FuelAccessResult, now: Instant) {
        when (answer) {
            PlanApi.FuelAccessResult.NoRoute -> accesses.write(key, now, ACCESS_TTL) { writeByte(0) }
            is PlanApi.FuelAccessResult.Routed -> {
                val access = answer.access
                val shape = access.shape.encodeToByteArray()
                if (shape.size !in 1..MAX_SHAPE) return
                accesses.write(key, now, ACCESS_TTL) {
                    writeByte(1)
                    listOf(access.outKm, access.backKm, access.outMin, access.backMin).forEach(::writeDouble)
                    writeInt(shape.size)
                    write(shape)
                }
            }
            else -> Unit
        }
    }

    private class DiskEntries(private val dir: File, private val maxBytes: Long, private val maxEntries: Int) {
        private var files: MutableMap<String, File>? = null
        private var bytes = 0L
        private var nextSweep = Instant.MIN

        private fun files(): MutableMap<String, File> = files ?: dir.listFiles().orEmpty()
            .filter { it.isFile }.mapNotNull { file ->
                if (file.name.matches(Regex("[a-f0-9]{64}\\.gz"))) file.name to file
                else {
                    if (file.name.endsWith(".tmp")) file.delete()
                    null
                }
            }.toMap(linkedMapOf()).also { loaded ->
                bytes = loaded.values.sumOf { it.length() }
                files = loaded
            }

        @Synchronized
        fun <T> read(key: String, now: Instant, ttl: Long, decode: DataInputStream.(Long) -> T?): T? {
            sweep(now, ttl)
            val file = files()[name(key)] ?: return null
            return runCatching {
                val value = input(file).use {
                    check(it.readInt() == VERSION)
                    val age = now.epochSecond - it.readLong()
                    val decoded = if (age in 0 until ttl) it.decode(age) else null
                    if (decoded != null) check(it.read() == -1)
                    decoded
                }
                if (value == null) remove(file) else file.setLastModified(now.toEpochMilli())
                value
            }.getOrElse {
                remove(file)
                null
            }
        }

        @Synchronized
        fun write(key: String, now: Instant, ttl: Long, encode: DataOutputStream.() -> Unit) {
            sweep(now, ttl)
            val entries = files()
            if (!dir.isDirectory && !dir.mkdirs()) return
            runCatching {
                val tmp = File.createTempFile("fuel-", ".tmp", dir)
                try {
                    DataOutputStream(GZIPOutputStream(tmp.outputStream())).use {
                        it.writeInt(VERSION)
                        it.writeLong(now.epochSecond)
                        it.encode()
                    }
                    if (tmp.length() > maxBytes) return
                    val dest = File(dir, name(key))
                    val oldBytes = dest.length()
                    Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    dest.setLastModified(now.toEpochMilli())
                    bytes += dest.length() - oldBytes
                    entries[dest.name] = dest
                    trim()
                } finally {
                    tmp.delete()
                }
            }
        }

        private fun sweep(now: Instant, ttl: Long) {
            if (now < nextSweep) return
            nextSweep = now.plusSeconds(3600)
            for (file in files().values.toList()) {
                val valid = runCatching {
                    input(file).use { it.readInt() == VERSION && now.epochSecond - it.readLong() in 0 until ttl }
                }.getOrDefault(false)
                if (!valid) remove(file)
            }
            trim()
        }

        private fun trim() {
            if (bytes <= maxBytes && files().size <= maxEntries) return
            for (file in files().values.sortedBy { it.lastModified() }) {
                if (bytes <= maxBytes && files().size <= maxEntries) break
                remove(file)
            }
        }

        private fun remove(file: File) {
            val size = file.length()
            if (file.delete() || !file.exists()) {
                files().remove(file.name)
                bytes -= size
            }
        }

        private fun input(file: File): DataInputStream {
            if (file.length() > maxBytes) throw IOException("Oversize fuel routing entry")
            return DataInputStream(GZIPInputStream(file.inputStream().buffered()))
        }

        private fun name(key: String) = hexDigest(key) + ".gz"
    }

    companion object {
        private const val VERSION = 1
        private const val ROUTE_TTL = 6 * 3600L
        private const val ACCESS_TTL = 24 * 3600L
        private const val NEGATIVE_TTL = 3 * 3600L
        private const val MAX_SHAPE = 2 * 1024 * 1024

        fun routeKey(places: List<Place>) = "osrm/driving/itinerary/v1/" + places.joinToString(";") { PlanApi.fuelCoordinate(it) }

        fun accessKey(window: FuelAccessWindow, station: FuelStation) =
            "osrm/driving/access/v1/${station.id}/${PlanApi.fuelCoordinate(window.entry.place)}/${window.entry.bearing}/" +
                "${PlanApi.fuelCoordinate(Place("", "", station.lat, station.lon))}/" +
                "${PlanApi.fuelCoordinate(window.exit.place)}/${window.exit.bearing}"

        private fun DoubleArray.ordered() = first() >= 0 && (1 until size).all { this[it] >= this[it - 1] }

        private fun hexDigest(key: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())
            val digits = "0123456789abcdef"
            return buildString {
                for (byte in bytes) {
                    append(digits[(byte.toInt() and 255) shr 4])
                    append(digits[byte.toInt() and 15])
                }
            }
        }
    }
}
