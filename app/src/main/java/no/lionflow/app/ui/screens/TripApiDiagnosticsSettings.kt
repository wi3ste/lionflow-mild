package no.lionflow.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.TripInvestigationUi
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.GroupCard
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.components.SectionTitle
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type

@Composable
internal fun TripApiDiagnosticsSettings(vm: AppViewModel, ui: UiState) {
    val state by vm.tripInvestigation.collectAsStateWithLifecycle()
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    TripApiDiagnosticsPanel(
        state = state,
        canRun = !vm.isDemo && ui.vehicle != null,
        onRun = vm::runTripInvestigation,
        onExport = {
            scope.launch {
                try {
                    vm.exportTripInvestigation()?.let { platform.share(it, "text/plain", "Export diagnostic report") }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    vm.showTripInvestigationExportError()
                }
            }
        },
    )
}

@Composable
internal fun TripApiDiagnosticsPanel(
    state: TripInvestigationUi,
    canRun: Boolean,
    onRun: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = LocalLf.current
    val report = state.report
    SectionTitle("Trip API Diagnostics")
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val status = when {
                state.test != null -> "Running ${state.test.title} · request ${state.page}"
                report != null -> "Diagnostics finished"
                !canRun -> "Sign in and select a vehicle to run diagnostics."
                else -> "Ready to run"
            }
            Text(status, style = Type.small, color = colors.muted)
            PrimaryButton("Run diagnostics", Modifier.fillMaxWidth(), enabled = canRun && state.test == null, onClick = onRun)
            if (report != null) {
                SecondaryButton("Export diagnostic report", Modifier.fillMaxWidth(), onClick = onExport)
                report.results.forEach { result ->
                    Divider()
                    Text(result.test.title, style = Type.label, color = colors.text)
                    Text(result.summary(), style = Type.small, color = colors.muted)
                }
            }
        }
    }
}
