package com.orka.feature.diagnostics

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
import com.orka.core.designsystem.OrkaActionButton
import com.orka.core.designsystem.OrkaScreenContainer
import com.orka.core.designsystem.OrkaSurface
import com.orka.core.model.DiagnosticsRepository
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.orka.core.model.DiagnosticsSnapshot())

    fun refresh() {
        viewModelScope.launch { diagnosticsRepository.refreshNow() }
    }
}

@Composable
fun DiagnosticsRoute(
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()

    OrkaSurface {
        OrkaScreenContainer(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text("Diagnostics", style = MaterialTheme.typography.headlineLarge)
            Text("Capability: ${snapshot.capabilityState}", style = MaterialTheme.typography.bodyLarge)
            Text("Next reminder: ${snapshot.nextReminder?.scheduledTime ?: "None"}", style = MaterialTheme.typography.bodyLarge)
            Text("Last fired reminder: ${snapshot.lastFiredReminder?.scheduledTime ?: "None"}", style = MaterialTheme.typography.bodyLarge)
            Text("Model: ${snapshot.modelInstallState.availability}", style = MaterialTheme.typography.bodyLarge)
            Text("Scheduler mode: ${snapshot.activeSchedulerMode}", style = MaterialTheme.typography.bodyLarge)
            Text("RL readiness: ${snapshot.rlReadiness}", style = MaterialTheme.typography.bodyLarge)
            snapshot.warnings.forEach { warning ->
                Text(warning, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            }
            OrkaActionButton(text = "Refresh", emphasis = com.orka.core.model.ActionEmphasis.PRIMARY, onClick = viewModel::refresh)
        }
    }
}
