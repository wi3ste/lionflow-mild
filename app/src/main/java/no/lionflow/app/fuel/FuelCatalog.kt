package no.lionflow.app.fuel

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal data class FuelSnapshot(val dataset: FuelDataset, val syncedAt: Instant) {
    val index = StationIndex(dataset.stations)
}

internal data class FuelCatalogState(
    val snapshot: FuelSnapshot? = null,
    val refreshing: Boolean = false,
    val failed: Boolean = false,
    val syncMillis: Long? = null,
)

internal class FuelCache(private val file: File) {
    fun read(): FuelSnapshot? {
        if (!file.isFile) return null
        return DataInputStream(GZIPInputStream(file.inputStream())).use { input ->
            check(input.readInt() == 1)
            val synced = Instant.ofEpochMilli(input.readLong())
            val date = input.readUTF().takeIf(String::isNotEmpty)?.let(LocalDate::parse)
            val stations = List(input.readInt().also { check(it in 1..100_000) }) {
                val labels = List(10) { input.readUTF() }
                val lat = input.readDouble()
                val lon = input.readDouble()
                val offers = List(input.readUnsignedByte()) {
                    val grade = FuelGrade.valueOf(input.readUTF())
                    val service = input.readUTF().takeIf(String::isNotEmpty)?.let(FuelService::valueOf)
                    val price = input.readDouble().takeIf(Double::isFinite)
                    val currency = input.readUTF()
                    val at = input.readLong().takeUnless { it == Long.MIN_VALUE }?.let(Instant::ofEpochMilli)
                    FuelOffer(grade, service, price, currency, at)
                }
                FuelStation(labels[0], labels[1], labels[2], labels[3], labels[4], labels[5], labels[6],
                    labels[7], lat, lon, labels[8], labels[9], offers)
            }
            check(input.read() == -1)
            FuelSnapshot(FuelDataset(stations, date), synced)
        }
    }

    fun write(snapshot: FuelSnapshot) {
        val temporary = File.createTempFile("fuel-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { raw ->
                GZIPOutputStream(raw).use { gzip ->
                    val out = DataOutputStream(gzip)
                    out.writeInt(1)
                    out.writeLong(snapshot.syncedAt.toEpochMilli())
                    out.writeUTF(snapshot.dataset.priceDate?.toString().orEmpty())
                    out.writeInt(snapshot.dataset.stations.size)
                    for (station in snapshot.dataset.stations) {
                        listOf(station.id, station.name, station.brand, station.operator, station.address, station.town,
                            station.province, station.country, station.kind, station.source).forEach(out::writeUTF)
                        out.writeDouble(station.lat)
                        out.writeDouble(station.lon)
                        out.writeByte(station.offers.size)
                        for (offer in station.offers) {
                            out.writeUTF(offer.grade.name)
                            out.writeUTF(offer.service?.name.orEmpty())
                            out.writeDouble(offer.price ?: Double.NaN)
                            out.writeUTF(offer.currency)
                            out.writeLong(offer.reportedAt?.toEpochMilli() ?: Long.MIN_VALUE)
                        }
                    }
                    gzip.finish()
                    raw.fd.sync()
                }
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}

internal class FuelCatalog(
    private val provider: FuelStationProvider,
    private val cache: FuelCache,
    private val now: () -> Instant = Instant::now,
    private val isCurrent: (FuelSnapshot, Instant) -> Boolean = { snapshot, time ->
        snapshot.syncedAt.plusSeconds(86400).isAfter(time)
    },
) {
    private val lock = Mutex()
    private val current = MutableStateFlow(FuelCatalogState())
    val state = current.asStateFlow()
    private var opened = false
    private var attemptedAt: Instant? = null

    suspend fun refresh(force: Boolean = false): FuelCatalogState = withContext(Dispatchers.IO) {
        val requested = now()
        lock.withLock {
            if (!opened) {
                current.value = FuelCatalogState(runCatching { cache.read() }.getOrNull())
                opened = true
            }
            val snapshot = current.value.snapshot
            val time = now()
            val last = attemptedAt
            if (last != null && (last > requested || (!force && last.plusSeconds(900).isAfter(time)))) return@withLock current.value
            if (!force && snapshot != null && isCurrent(snapshot, time)) return@withLock current.value

            attemptedAt = time
            current.value = FuelCatalogState(snapshot, refreshing = true)
            val started = System.nanoTime()
            try {
                val dataset = provider.load()
                val previous = snapshot?.dataset
                val truncated = previous != null && (dataset.stations.size * 2 < previous.stations.size ||
                    dataset.quotedStations * 2 < previous.quotedStations)
                val previousDate = previous?.priceDate
                if (dataset.stations.isEmpty() || truncated ||
                    (previousDate != null && (dataset.priceDate == null || dataset.priceDate < previousDate))) {
                    throw IOException("Incomplete or older fuel dataset")
                }
                val next = FuelSnapshot(dataset, now())
                cache.write(next)
                current.value = FuelCatalogState(next, syncMillis = (System.nanoTime() - started) / 1_000_000)
            } catch (e: CancellationException) {
                attemptedAt = null
                current.value = FuelCatalogState(snapshot)
                throw e
            } catch (_: Exception) {
                current.value = FuelCatalogState(snapshot, failed = true, syncMillis = (System.nanoTime() - started) / 1_000_000)
            }
            current.value
        }
    }

    companion object {
        @Volatile private var catalog: FuelCatalog? = null

        fun get(context: Context): FuelCatalog = catalog ?: synchronized(this) {
            catalog ?: FuelCatalog(MimitFuelProvider(), FuelCache(File(context.filesDir, "fuel-mimit-v1.gz")),
                isCurrent = { snapshot, time ->
                    val local = time.atZone(MimitFuelProvider.italy)
                    val publication = if (local.toLocalTime() < LocalTime.of(8, 30)) local.toLocalDate().minusDays(1)
                    else local.toLocalDate()
                    val publicationTime = publication.atTime(8, 30).atZone(MimitFuelProvider.italy).toInstant()
                    !snapshot.syncedAt.isBefore(publicationTime) &&
                        snapshot.dataset.priceDate?.isBefore(publication.minusDays(1)) != true
                }).also { catalog = it }
        }

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<FuelSyncWorker>(12, TimeUnit.HOURS)
                .setInitialDelay(12, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("fuel-mimit", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class FuelSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (FuelCatalog.get(applicationContext).refresh().failed) Result.retry() else Result.success()
}
