package no.lionflow.app.ui.screens

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import no.lionflow.app.LionFlowApp
import no.lionflow.app.Platform
import no.lionflow.app.fuel.*
import no.lionflow.app.model.Backup
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.Planner
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.theme.LionFlowTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile

class FuelManagementScreenTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as LionFlowApp

    @Test fun refuelHistoryShowsConfirmedBalanceAndKeepsVehiclesSeparate() {
        val vin = UUID.randomUUID().toString()
        val refuel = Refuel(at = System.currentTimeMillis(), grade = FuelGrade.PETROL, amount = 24.0, total = 42.0, stationName = "Stazione Test")
        app.fuelLog.save(vin, refuel)
        try {
            assertEquals(refuel, FuelLog(app).entries(vin).single())
            assertTrue(FuelLog(app).entries("other-$vin").isEmpty())
            compose.setContent {
                LionFlowTheme("light") {
                    CompositionLocalProvider(LocalPlatform provides platform) {
                        FuelLogScreen(app.fuelLog, vin, FuelGrade.PETROL, emptyList(), false, {}) {}
                    }
                }
            }
            compose.onNodeWithText("Stazione Test").assertIsDisplayed()
            capture("fuel-log-light")
            compose.onNodeWithText(L10n.tr("Fuel statistics")).performClick()
            compose.onNodeWithText(L10n.tr("Confirmed purchases")).assertIsDisplayed()
            capture("fuel-statistics-light")
            assertTrue(app.fuelLog.export(vin).readText().contains("Stazione Test"))
        } finally {
            app.fuelLog.remove(vin, refuel.id)
        }
    }

    @Test fun favoritesRemainAvailableWhenTheCatalogIsMissing() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val planner = FuelPlanner(scope, app) { FuelHistoryInput(emptyList(), emptyList(), false) }
        val addresses = Planner(scope, app, { null }, { emptyList() }, { null })
        val station = FuelStation("test:${UUID.randomUUID()}", "Favorite fixture", "Brand", "", "", "", "", "IT",
            42.0, 12.0, "Stradale", "test", emptyList())
        planner.favorites.toggle(station)
        try {
            assertEquals(station.id, FuelFavorites(app).stations.value[station.id]?.id)
            compose.setContent {
                LionFlowTheme("dark") {
                    CompositionLocalProvider(LocalPlatform provides platform) {
                        FuelNearbyScreen(planner, addresses, UiState(myLat = 42.0, myLon = 12.0), null, 6.0, Instant.now(), {}) { _, _, _ -> }
                    }
                }
            }
            compose.onNodeWithText(L10n.tr("Find fuel nearby")).assertIsDisplayed()
            compose.onNodeWithText(L10n.tr("Favorites")).performClick()
            compose.onNodeWithText("Favorite fixture").assertIsDisplayed()
            compose.onNodeWithText(L10n.tr("Unavailable in the current catalog")).assertIsDisplayed()
            capture("fuel-favorites-dark")
            compose.onNodeWithText(L10n.tr("Remove favorite")).performClick()
            assertFalse(station.id in planner.favorites.stations.value)
        } finally {
            planner.favorites.remove(station.id)
            scope.cancel()
        }
    }

    @Test fun backupIncludesFuelDataAndActiveTripSurvivesRepositoryReopening() {
        val vin = UUID.randomUUID().toString()
        val now = Instant.now().let { Instant.ofEpochMilli(it.toEpochMilli()) }
        val from = Place("Roma", "", 41.9, 12.5)
        val to = Place("Orvieto", "", 42.7, 12.1)
        val request = FuelTripRequest(from, to, emptyList(), false, now, FuelGrade.PETROL, FuelServiceFilter.ANY,
            FuelProfile.MILD, 6.0, 5.0, FuelConstraints(44.0, 24.0, 3.0, FuelStrategy.BALANCED), 8.0)
        val plan = SavedFuelTrip("trip-$vin", request, now, 120.0, 80.0, 80.0, 7.2, 12.0, 0.0, emptyList(), 16.8)
        val active = ActiveFuelTrip(plan, now.toEpochMilli(), 1500.0)
        app.savedFuelTrips.save(vin, plan)
        app.savedFuelTrips.setActive(vin, active)
        val refuel = Refuel(at = now.toEpochMilli(), grade = FuelGrade.PETROL, amount = 14.0)
        app.fuelLog.save(vin, refuel)
        val station = FuelStation("test:$vin", "Backup fixture", "", "", "", "", "", "IT", 42.0, 12.0, "", "test", emptyList())
        app.fuelFavorites.toggle(station)
        try {
            assertEquals(plan, SavedFuelTrips(app).list(vin).single())
            assertEquals(active, SavedFuelTrips(app).active(vin))
            assertNull(SavedFuelTrips(app).active("other-$vin"))
            // A synchronous write to the same preference files waits for previous apply() writes.
            for (name in listOf("fuel_log", "fuel_favorites", "fuel_trips")) {
                assertTrue(app.getSharedPreferences(name, 0).edit().putBoolean("testFlush", true).commit())
            }
            ZipFile(Backup.export(app)).use { zip ->
                for (name in listOf("fuel_log", "fuel_favorites", "fuel_trips")) {
                    assertNotNull(zip.getEntry("shared_prefs/$name.xml"))
                }
                assertNull(zip.getEntry("shared_prefs/lf_secure.xml"))
                assertFalse(zip.entries().asSequence().any { "fuel-routing" in it.name || "fuel-gauges" in it.name })
            }
        } finally {
            app.savedFuelTrips.remove(vin, plan.id)
            app.savedFuelTrips.setActive(vin, null)
            app.fuelLog.remove(vin, refuel.id)
            app.fuelFavorites.remove(station.id)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = app.contentResolver
                val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
                    put(MediaStore.Downloads.MIME_TYPE, "image/png")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/LionFlowFuelReview")
                }))
                checkNotNull(resolver.openOutputStream(uri)).use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            } else {
                val directory = File(app.filesDir, "fuel-review-shots").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private val platform = object : Platform {
        override val biometricAvailable = false
        override fun authenticate(title: String, subtitle: String, onSuccess: () -> Unit, onFail: (String) -> Unit) = Unit
        override fun openSignIn(url: String) = Unit
        override fun requestLocation() = Unit
        override fun share(file: File, mime: String) = Unit
        override fun openUrl(url: String) = Unit
        override fun openInstallPermission() = Unit
        override fun setAlarm(hour: Int, minute: Int, message: String) = Unit
    }
}
