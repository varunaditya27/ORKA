package com.orka.feature.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orka.core.designsystem.OrkaActionButton
import com.orka.core.designsystem.OrkaEyebrow
import com.orka.core.designsystem.OrkaScreenContainer
import com.orka.core.designsystem.OrkaSurface
import com.orka.core.designsystem.OrkaWordmark
import com.orka.core.model.AlarmCapabilityState
import com.orka.core.model.DiagnosticsRepository
import com.orka.core.model.ModelAvailability
import com.orka.core.model.ModelInstaller
import com.orka.core.model.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val capabilityState: AlarmCapabilityState = AlarmCapabilityState.EXACT_ALARM_DENIED,
    val modelAvailability: ModelAvailability = ModelAvailability.NOT_INSTALLED,
    val modelMessage: String = "Bundled model will be prepared automatically.",
    val onboardingComplete: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val modelInstaller: ModelInstaller,
) : ViewModel() {
    val uiState = combine(
        settingsRepository.observeSettings(),
        diagnosticsRepository.observeSnapshot(),
        modelInstaller.observeState(),
    ) { settings, diagnostics, modelState ->
        OnboardingUiState(
            capabilityState = diagnostics.capabilityState,
            modelAvailability = modelState.availability,
            modelMessage = modelState.message ?: when {
                modelState.modelPath != null -> "Bundled model is ready."
                else -> "Bundled model will be prepared automatically on first launch."
            },
            onboardingComplete = settings.onboardingCompleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    fun retryBundledModelProvisioning() {
        viewModelScope.launch {
            modelInstaller.installBundledModelIfAvailable()
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(onboardingCompleted = true) }
        }
    }
}

@Composable
fun OnboardingRoute(
    modifier: Modifier = Modifier,
    onGrantExactAlarmPermission: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenOemSettings: () -> Unit,
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    OrkaSurface {
        OrkaScreenContainer(
            modifier = modifier
                .padding(top = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OrkaEyebrow("Onboarding")
            OrkaWordmark(modifier = Modifier.fillMaxWidth())
            Text("Execution, not intention.", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Complete the reliability setup before ORKA starts managing deadlines.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("1. Exact alarms", style = MaterialTheme.typography.headlineMedium)
            Text(
                when (state.capabilityState) {
                    AlarmCapabilityState.EXACT_ALARM_DENIED -> "Enable exact alarms so reminders can fire on time."
                    else -> "Exact alarm capability looks good."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            OrkaActionButton(text = "Grant Permission", emphasis = com.orka.core.model.ActionEmphasis.PRIMARY, onClick = onGrantExactAlarmPermission)

            Text("2. Battery optimization", style = MaterialTheme.typography.headlineMedium)
            Text("Allow ORKA to stay unrestricted so alarms remain reliable while the phone is idle.", style = MaterialTheme.typography.bodyLarge)
            OrkaActionButton(text = "Open Battery Settings", emphasis = com.orka.core.model.ActionEmphasis.SECONDARY, onClick = onOpenBatterySettings)

            Text("3. OEM-specific setup", style = MaterialTheme.typography.headlineMedium)
            Text(
                "If you use OnePlus (OxygenOS), Xiaomi, Realme, or OPPO, open OEM settings and enable auto-launch/background activity, then set battery usage to Unrestricted for ORKA.",
                style = MaterialTheme.typography.bodyLarge,
            )
            OrkaActionButton(text = "Open OEM Settings", emphasis = com.orka.core.model.ActionEmphasis.SECONDARY, onClick = onOpenOemSettings)

            Text("4. Bundled model", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Gemma is bundled into the install artifact for this personal ADB workflow. ORKA auto-prepares it in app storage.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(state.modelMessage, style = MaterialTheme.typography.bodyLarge)
            if (state.modelAvailability != ModelAvailability.READY) {
                OrkaActionButton(
                    text = "Retry Model Provisioning",
                    emphasis = com.orka.core.model.ActionEmphasis.SECONDARY,
                    onClick = viewModel::retryBundledModelProvisioning,
                )
            }

            OrkaActionButton(text = "Finish Setup", emphasis = com.orka.core.model.ActionEmphasis.PRIMARY) {
                viewModel.completeOnboarding()
                onFinish()
            }
        }
    }
}
