package com.orka.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orka.core.designsystem.OrkaActionButton
import com.orka.core.designsystem.OrkaEyebrow
import com.orka.core.designsystem.OrkaScreenContainer
import com.orka.core.designsystem.OrkaSpacing
import com.orka.core.designsystem.OrkaSurface
import com.orka.core.model.ModelAvailability
import com.orka.core.model.ModelInstaller
import com.orka.core.model.SettingsRepository
import com.orka.core.model.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val modelAvailability: ModelAvailability = ModelAvailability.NOT_INSTALLED,
    val modelMessage: String = "Model not detected",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val modelInstaller: ModelInstaller,
) : ViewModel() {
    val uiState = combine(
        settingsRepository.observeSettings(),
        modelInstaller.observeState(),
    ) { settings, modelState ->
        SettingsUiState(
            settings = settings,
            modelAvailability = modelState.availability,
            modelMessage = modelState.message ?: modelState.modelPath ?: "Model not detected",
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun toggleAdaptive(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(adaptiveSchedulingEnabled = enabled) }
        }
    }

    fun toggleRl(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(rlSchedulingEnabled = enabled) }
        }
    }

    fun toggleDarkMode(enabled: Boolean?) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(darkModeOverride = enabled) }
        }
    }

    fun resetOnboarding() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(onboardingCompleted = false) }
        }
    }

    fun retryBundledModelProvisioning() {
        viewModelScope.launch {
            modelInstaller.installBundledModelIfAvailable()
        }
    }

    fun resetModel() {
        viewModelScope.launch { modelInstaller.reset() }
    }
}

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    OrkaSurface {
        OrkaScreenContainer(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            OrkaEyebrow("Settings")
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
            SettingToggle("Adaptive scheduling", state.settings.adaptiveSchedulingEnabled, viewModel::toggleAdaptive)
            SettingToggle("RL scheduling", state.settings.rlSchedulingEnabled, viewModel::toggleRl)
            Text("Theme", style = MaterialTheme.typography.headlineMedium)
            Column(
                verticalArrangement = Arrangement.spacedBy(OrkaSpacing.sm),
            ) {
                val darkModeOverride = state.settings.darkModeOverride
                OrkaActionButton(
                    text = "System",
                    emphasis = if (darkModeOverride == null) com.orka.core.model.ActionEmphasis.PRIMARY else com.orka.core.model.ActionEmphasis.SECONDARY,
                ) { viewModel.toggleDarkMode(null) }
                OrkaActionButton(
                    text = "Dark",
                    emphasis = if (darkModeOverride == true) com.orka.core.model.ActionEmphasis.PRIMARY else com.orka.core.model.ActionEmphasis.SECONDARY,
                ) { viewModel.toggleDarkMode(true) }
                OrkaActionButton(
                    text = "Light",
                    emphasis = if (darkModeOverride == false) com.orka.core.model.ActionEmphasis.PRIMARY else com.orka.core.model.ActionEmphasis.SECONDARY,
                ) { viewModel.toggleDarkMode(false) }
            }

            Text("On-device model", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Push the model once via \"adb push gemma-4-E4B-it.litertlm /data/local/tmp/\" — ORKA detects it there directly, no need to resend it after app updates.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(state.modelMessage, style = MaterialTheme.typography.bodyLarge)
            if (state.modelAvailability != ModelAvailability.READY) {
                OrkaActionButton(
                    text = "Retry Model Provisioning",
                    emphasis = com.orka.core.model.ActionEmphasis.SECONDARY,
                    onClick = viewModel::retryBundledModelProvisioning,
                )
            }
            OrkaActionButton(text = "Reset Model", emphasis = com.orka.core.model.ActionEmphasis.TERTIARY, onClick = viewModel::resetModel)
            OrkaActionButton(text = "Rerun Onboarding", emphasis = com.orka.core.model.ActionEmphasis.TERTIARY, onClick = viewModel::resetOnboarding)
        }
    }
}

@Composable
private fun SettingToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
