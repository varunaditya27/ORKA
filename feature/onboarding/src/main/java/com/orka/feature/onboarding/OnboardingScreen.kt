package com.orka.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.orka.core.model.ActionEmphasis
import com.orka.core.model.AlarmCapabilities
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
    val capabilities: AlarmCapabilities = AlarmCapabilities(exactAlarmsGranted = false, notificationsGranted = false),
    val modelAvailability: ModelAvailability = ModelAvailability.NOT_INSTALLED,
    val modelMessage: String = "Push the model once via adb, then ORKA will detect it automatically.",
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
            capabilities = diagnostics.capabilities,
            modelAvailability = modelState.availability,
            modelMessage = modelState.message ?: when {
                modelState.modelPath != null -> "Model is ready."
                else -> "Push the model once via adb, then ORKA will detect it automatically."
            },
            onboardingComplete = settings.onboardingCompleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    fun refreshDiagnostics() {
        viewModelScope.launch {
            diagnosticsRepository.refreshNow()
        }
    }

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
    onOpenFullScreenIntentSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val capabilities = state.capabilities

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // areNotificationsEnabled() reflects the grant immediately; refresh so the UI
        // updates without waiting for the next unrelated recomposition.
        viewModel.refreshDiagnostics()
    }

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

            Text("1. Notifications", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (capabilities.notificationsGranted) {
                    "Notifications are enabled."
                } else {
                    "Enable notifications so ORKA can alert you when a reminder fires."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!capabilities.notificationsGranted) {
                OrkaActionButton(
                    text = "Grant Notifications",
                    emphasis = ActionEmphasis.PRIMARY,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            // Pre-33 has no runtime permission for this; notifications are on by
                            // default there, so reaching this branch means the user disabled them
                            // manually in system settings — send them to the app's own screen.
                            onOpenNotificationSettings()
                        }
                    },
                )
            }

            Text("2. Exact alarms", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (capabilities.exactAlarmsGranted) {
                    "Exact alarm capability looks good."
                } else {
                    "Enable exact alarms so reminders can fire on time."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!capabilities.exactAlarmsGranted) {
                OrkaActionButton(text = "Grant Permission", emphasis = ActionEmphasis.PRIMARY, onClick = onGrantExactAlarmPermission)
            }

            if (!capabilities.fullScreenIntentGranted) {
                Text("3. Full-screen alarm display", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Android 14+ requires explicit approval to show ORKA's alarm screen over the lock screen. " +
                        "Without it, reminders arrive as a normal notification instead of the full-screen interrupt.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                OrkaActionButton(
                    text = "Allow Full-Screen Alarms",
                    emphasis = ActionEmphasis.PRIMARY,
                    onClick = onOpenFullScreenIntentSettings,
                )
            }

            Text("4. Battery optimization", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (capabilities.batteryOptimizationIgnored) {
                    "ORKA is already unrestricted."
                } else {
                    "Allow ORKA to stay unrestricted so alarms remain reliable while the phone is idle."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!capabilities.batteryOptimizationIgnored) {
                OrkaActionButton(text = "Open Battery Settings", emphasis = ActionEmphasis.SECONDARY, onClick = onOpenBatterySettings)
            }

            Text("5. OEM-specific setup", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (capabilities.oemActionNeeded) {
                    "If you use OnePlus (OxygenOS), Xiaomi, Realme, or OPPO, open OEM settings and enable auto-launch/background activity, then set battery usage to Unrestricted for ORKA."
                } else {
                    "No known OEM-specific restrictions detected for this device."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (capabilities.oemActionNeeded) {
                OrkaActionButton(text = "Open OEM Settings", emphasis = ActionEmphasis.SECONDARY, onClick = onOpenOemSettings)
            }

            Text("6. On-device model", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Push the Gemma model to this device once with " +
                    "\"adb push gemma-4-E4B-it.litertlm /data/local/tmp/\" — ORKA detects it there directly, " +
                    "so app updates stay lightweight and don't need to re-send the model.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(state.modelMessage, style = MaterialTheme.typography.bodyLarge)
            if (state.modelAvailability != ModelAvailability.READY) {
                OrkaActionButton(
                    text = "Retry Model Provisioning",
                    emphasis = ActionEmphasis.SECONDARY,
                    onClick = viewModel::retryBundledModelProvisioning,
                )
            }

            OrkaActionButton(text = "Finish Setup", emphasis = ActionEmphasis.PRIMARY) {
                viewModel.completeOnboarding()
                onFinish()
            }
        }
    }
}
