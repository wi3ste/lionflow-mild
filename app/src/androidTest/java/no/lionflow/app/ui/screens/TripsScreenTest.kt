package no.lionflow.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import no.lionflow.app.data.AppSettings
import no.lionflow.app.model.Trip
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.theme.LionFlowTheme
import org.junit.Rule
import org.junit.Test

class TripsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scrollsPastTheFiftiethTripToTheOldestRow() {
        val now = System.currentTimeMillis()
        val settings = AppSettings(distanceUnit = "km")
        val trips = List(65) { i ->
            val start = now - (i + 2) * 60_000L
            Trip("test-trip-$i", start, start + 60_000L, (i + 1).toDouble(), 60.0, null, null, null)
        }
        compose.setContent {
            LionFlowTheme("light") {
                TripsScreen(viewModel(), UiState(now = now, trips = trips), settings, wide = false)
            }
        }
        // The summary is the first lazy item, then the trip rows follow in chronological order.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(trips.size)
        compose.onNodeWithText(fmtDistance(trips.last().distanceKm, settings)).assertIsDisplayed()
    }
}
