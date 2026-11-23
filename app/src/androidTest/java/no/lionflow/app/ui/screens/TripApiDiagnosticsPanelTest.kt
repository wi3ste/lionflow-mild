package no.lionflow.app.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import no.lionflow.app.api.TripInvestigationReport
import no.lionflow.app.api.TripProbeCase
import no.lionflow.app.api.TripProbeResult
import no.lionflow.app.api.TripProbeStop
import no.lionflow.app.ui.TripInvestigationUi
import no.lionflow.app.ui.theme.LionFlowTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TripApiDiagnosticsPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsProgressAndEnablesReportExportAfterRun() {
        val state = mutableStateOf(TripInvestigationUi())
        var exported = false
        compose.setContent {
            LionFlowTheme("light") {
                TripApiDiagnosticsPanel(
                    state = state.value,
                    canRun = true,
                    onRun = { state.value = TripInvestigationUi(test = TripProbeCase.A, page = 1) },
                    onExport = { exported = true },
                )
            }
        }

        compose.onNodeWithText("Run diagnostics").performClick()
        compose.onNodeWithText("Running A · Open end · request 1").assertIsDisplayed()
        compose.onNodeWithText("Run diagnostics").assertIsNotEnabled()
        compose.onNodeWithText("Export diagnostic report").assertDoesNotExist()

        compose.runOnIdle {
            val a = TripProbeResult(TripProbeCase.A, TripProbeStop.LAST_PAGE, httpCodes = listOf(200), pages = 1)
            state.value = TripInvestigationUi(report = TripInvestigationReport(listOf(a), null))
        }
        compose.onNodeWithText("Diagnostics finished").assertIsDisplayed()
        compose.onNodeWithText("A · Open end").assertIsDisplayed()
        compose.onNodeWithText("Export diagnostic report").performClick()
        compose.runOnIdle { assertTrue(exported) }
    }
}
