package no.lionflow.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.Vehicle
import no.lionflow.app.fuel.FuelHistoryInput
import no.lionflow.app.fuel.FuelPlanner
import no.lionflow.app.fuel.FuelProfile
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.plan.Planner
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.theme.LionFlowTheme
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class FuelTripFormTest {
    @get:Rule val compose = createComposeRule()

    @Test fun formPrefillsFuelAndIdentifiesMissingRequiredFields() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LionFlowApp
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val planner = FuelPlanner(scope, app) { FuelHistoryInput(emptyList(), emptyList(), false) }
        val addresses = Planner(scope, app, { null }, { emptyList() }, { null })
        val form = FuelFormState(planner, "", FuelProfile.MILD)
        val vehicle = Vehicle("id", "vin", "Hybrid", "Peugeot", null, null, "208 Hybrid 100")
        fun status(level: Int) = VehicleStatus.parse(JSONObject("""{"energies":[{"type":"Fuel","level":$level}]}"""), null)
        val ui = mutableStateOf(UiState(vehicle = vehicle, status = status(59), myLat = 41.9, myLon = 12.5))
        try {
            compose.setContent {
                LionFlowTheme("dark") {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        FuelTripForm(planner, addresses, form, ui.value)
                    }
                }
            }

            compose.onNodeWithText(L10n.tr("Required fields marked with *")).assertIsDisplayed()
            compose.onNodeWithText(L10n.tr("Where to? *")).assertIsDisplayed()
            compose.onNodeWithText("44").assertIsDisplayed()
            compose.onNodeWithText(fuelNumber(59.0, 1)).assertIsDisplayed()
            compose.onNodeWithText(L10n.tr("Minimum reserve") + " (%)").assertDoesNotExist()

            compose.onNodeWithText(L10n.tr("Plan fuel trip")).performScrollTo().performClick()
            compose.onNodeWithText(L10n.tr("Select a destination")).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(L10n.tr("Select a departure point")).assertDoesNotExist()
            compose.onNodeWithText(L10n.tr("Show optional trip settings")).performScrollTo().performClick()
            compose.onNodeWithText(L10n.tr("Minimum reserve") + " (%)").performScrollTo().assertIsDisplayed()

            compose.onNodeWithText(fuelNumber(59.0, 1)).performScrollTo().performTextReplacement("25")
            compose.runOnIdle { ui.value = ui.value.copy(status = status(62)) }
            compose.onNodeWithText("25").assertExists()
            compose.onNodeWithText(L10n.tr("Use vehicle fuel level")).performScrollTo().performClick()
            compose.onNodeWithText(fuelNumber(62.0, 1)).assertExists()
        } finally {
            scope.cancel()
        }
    }
}
