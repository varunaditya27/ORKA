package com.orka.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orka.core.common.TimeFormatter
import com.orka.core.common.displayName
import com.orka.core.designsystem.OrkaActionButton
import com.orka.core.designsystem.OrkaEyebrow
import com.orka.core.designsystem.OrkaScreenContainer
import com.orka.core.designsystem.OrkaSurface
import com.orka.core.model.ActionEmphasis
import com.orka.core.model.DiagnosticsRepository
import com.orka.core.model.DiagnosticsSnapshot
import com.orka.core.model.RlReadiness
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val diagnosticsRepository: DiagnosticsRepository,
) : ViewModel() {
    val snapshot = diagnosticsRepository.observeSnapshot()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsSnapshot())

    fun refresh() {
        viewModelScope.launch { diagnosticsRepository.refreshNow() }
    }
}

@Composable
fun DiagnosticsRoute(
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val capabilities = snapshot.capabilities

    OrkaSurface {
        OrkaScreenContainer(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            OrkaEyebrow("Diagnostics")
            Text("Diagnostics", style = MaterialTheme.typography.headlineLarge)

            Text("Alarm capability checks", style = MaterialTheme.typography.headlineMedium)
            CapabilityRow("Exact alarms", isOk = capabilities.exactAlarmsGranted)
            CapabilityRow("Notifications", isOk = capabilities.notificationsGranted)
            CapabilityRow("Full-screen alarm display", isOk = capabilities.fullScreenIntentGranted)
            CapabilityRow("Battery unrestricted", isOk = capabilities.batteryOptimizationIgnored)
            CapabilityRow("OEM setup", isOk = !capabilities.oemActionNeeded)

            Text("Schedule", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Next reminder: ${snapshot.nextReminder?.scheduledTime?.let(TimeFormatter::formatInstant) ?: "None"}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Last fired reminder: ${snapshot.lastFiredReminder?.scheduledTime?.let(TimeFormatter::formatInstant) ?: "None"}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("Scheduler mode: ${snapshot.activeSchedulerMode.displayName()}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "RL readiness: ${rlReadinessText(snapshot.rlReadiness)}",
                style = MaterialTheme.typography.bodyLarge,
            )

            Text("Model", style = MaterialTheme.typography.headlineMedium)
            Text("Model: ${snapshot.modelInstallState.availability.displayName()}", style = MaterialTheme.typography.bodyLarge)
            snapshot.modelInstallState.modelPath?.let {
                Text("Path: $it", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (snapshot.warnings.isNotEmpty()) {
                Text("Warnings", style = MaterialTheme.typography.headlineMedium)
                snapshot.warnings.forEach { warning ->
                    Text(warning, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
            }

            OrkaActionButton(text = "Refresh", emphasis = ActionEmphasis.PRIMARY, onClick = viewModel::refresh)
        }
    }
}

// RlReadiness.NotReady's default toString() would render as "NotReady(reason=...)" — a Kotlin
// data class dump, not user-facing text. Extract the field directly instead.
private fun rlReadinessText(readiness: RlReadiness): String = when (readiness) {
    is RlReadiness.Ready -> "Ready"
    is RlReadiness.NotReady -> readiness.reason
}

@Composable
private fun CapabilityRow(label: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (isOk) "OK" else "Action needed",
            style = MaterialTheme.typography.bodyLarge,
            color = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
