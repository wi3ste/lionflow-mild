package no.lionflow.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import no.lionflow.app.LionFlowApp
import no.lionflow.app.Platform
import no.lionflow.app.data.AppSettings
import no.lionflow.app.fuel.FuelCandidate
import no.lionflow.app.fuel.FuelCatalogState
import no.lionflow.app.fuel.FuelConstraints
import no.lionflow.app.fuel.FuelConsumption
import no.lionflow.app.fuel.FuelGrade
import no.lionflow.app.fuel.FuelHistoryInput
import no.lionflow.app.fuel.FuelOffer
import no.lionflow.app.fuel.FuelOptimizer
import no.lionflow.app.fuel.FuelPlanner
import no.lionflow.app.fuel.FuelProfile
import no.lionflow.app.fuel.FuelProjection
import no.lionflow.app.fuel.FuelSearchSummary
import no.lionflow.app.fuel.FuelService
import no.lionflow.app.fuel.FuelServiceFilter
import no.lionflow.app.fuel.FuelStation
import no.lionflow.app.fuel.FuelStrategy
import no.lionflow.app.fuel.FuelTripRequest
import no.lionflow.app.fuel.FuelTripResult
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.RouteData
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.theme.LionFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

class FuelTripResultTest {
    @get:Rule val compose = createComposeRule()

    @Test fun summaryAndStopsDiscloseDetailsIndependently() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LionFlowApp
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val planner = FuelPlanner(scope, app) { FuelHistoryInput(emptyList(), emptyList(), false) }
        val result = sampleTrip()
        val station = result.plan!!.stops.first().candidate.station
        var shown: FuelStation? = null
        try {
            compose.setContent {
                CompositionLocalProvider(LocalPlatform provides TestPlatform) {
                    LionFlowTheme("dark") {
                        Column(Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
                            FuelTripResultPanel(planner, result, AppSettings(), FuelCatalogState(), newHistory = false) { shown = it }
                        }
                    }
                }
            }

            fun label(text: String) = compose.onNodeWithText(L10n.tr(text))
            label("Total distance").assertIsDisplayed()
            label("Estimated total time").assertIsDisplayed()
            label("Estimated spend on refuelling").assertIsDisplayed()
            label("Fuel stops").assertIsDisplayed()
            label("Expected average consumption").assertDoesNotExist()
            label("Show diagnostics").assertDoesNotExist()
            label("Check more stations").assertDoesNotExist()
            label("Edit fuel stop").assertDoesNotExist()

            label("Show trip details").performScrollTo().performClick()
            label("Expected average consumption").performScrollTo().assertIsDisplayed()
            label("Check more stations").performScrollTo().assertIsDisplayed()
            label("Candidate verification details").assertDoesNotExist()
            label("Hide trip details").performScrollTo().performClick()
            label("Net fuel used by detours").assertDoesNotExist()

            val first = compose.onNodeWithText(stationName(station))
            first.performScrollTo().performClick()
            label("Net fuel used by this detour").performScrollTo().assertIsDisplayed()
            label("Show station on map").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(station, shown) }
            assertEquals(1, compose.onAllNodes(androidx.compose.ui.test.hasText(L10n.tr("Edit fuel stop"))).fetchSemanticsNodes().size)
            first.performScrollTo().performClick()
            label("Net fuel used by this detour").assertDoesNotExist()
        } finally {
            scope.cancel()
        }
    }

    @Test fun valuesRoundedToZeroDoNotShowAMinusSign() {
        assertEquals(fuelNumber(0.0, 2), fuelNumber(-0.0001, 2))
        assertEquals(fuelNumber(0.0, 2), fuelNumber(-0.0, 2))
        assertEquals(fuelMoney(0.0), fuelMoney(-0.0001))
        assertNotEquals(fuelNumber(0.02, 2), fuelNumber(-0.02, 2))
        assertNotEquals(fuelMoney(0.02), fuelMoney(-0.02))
    }

    private fun sampleTrip(): FuelTripResult {
        val now = Instant.parse("2026-11-24T18:00:00Z")
        val from = Place("From", "", 42.0, 12.0)
        val to = Place("To", "", 42.0, 13.0)
        val road = RouteData(doubleArrayOf(from.lat, to.lat), doubleArrayOf(from.lon, to.lon),
            doubleArrayOf(0.0, 240.0), doubleArrayOf(0.0, 160.0), doubleArrayOf(0.0, 90.0))
        val grade = FuelGrade.PETROL
        val profile = FuelProfile.MILD
        val prediction = FuelConsumption.estimate(grade, profile, emptyList(), 6.0, 5.0)
        val constraints = FuelConstraints(7.0, 6.0, 1.0, FuelStrategy.BALANCED)
        val request = FuelTripRequest(from, to, emptyList(), false, now, grade, FuelServiceFilter.ANY,
            profile, 6.0, 5.0, constraints, 8.0)
        val candidates = listOf(4.2, 9.9).mapIndexed { index, roadFuel ->
            val place = Place("", "", 42.0, 12.3 + index * 0.4)
            val offer = FuelOffer(grade, FuelService.SELF, 1.75, "EUR", now)
            val station = FuelStation("id-$index", "Station ${index + 1}", "", "", "Via Roma", "Roma", "", "IT",
                place.lat, place.lon, "Stradale", "fixture", listOf(offer))
            val projection = FuelProjection(station, offer, place, 90, roadFuel * 15, roadFuel * 10, roadFuel, 0.2)
            FuelCandidate(projection, 0.5, 0.5, 1.0, 1.0, 0.06, 0.06)
        }
        val plan = checkNotNull(FuelOptimizer.plan(15.0, road.totalKm, road.totalMin, constraints, candidates, now, 1.75))
        assertEquals(2, plan.stops.size)
        return FuelTripResult(request, road, 15.0, candidates, 1.75, now, true, plan, prediction,
            FuelSearchSummary(3, 2, 2, 2, true))
    }

    private object TestPlatform : Platform {
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
