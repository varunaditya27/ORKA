package com.orka.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orka.core.designsystem.OrkaActionButton
import com.orka.core.designsystem.OrkaScreenContainer
import com.orka.core.designsystem.OrkaSpacing
import com.orka.core.designsystem.OrkaSurface
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
    val modelMessage: String = "No model imported",
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
        SettingsUiState(settings = settings, modelMessage = modelState.message ?: modelState.modelPath ?: "No model imported")
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

    fun importModel(path: String) {
        viewModelScope.launch {
            modelInstaller.installFromCompanionKit(path)
            settingsRepository.update { it.copy(importedModelPath = path) }
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
    var modelPath by remember(state.settings.importedModelPath) {
        mutableStateOf(state.settings.importedModelPath ?: "model-kit/gemma-2b-int4.gguf")
    }

    OrkaSurface {
        OrkaScreenContainer(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
            SettingToggle("Adaptive scheduling", state.settings.adaptiveSchedulingEnabled, viewModel::toggleAdaptive)
            SettingToggle("RL scheduling", state.settings.rlSchedulingEnabled, viewModel::toggleRl)
            Text("Theme", style = MaterialTheme.typography.headlineMedium)
            Column(
                verticalArrangement = Arrangement.spacedBy(OrkaSpacing.sm),
            ) {
                OrkaActionButton(text = "System", emphasis = com.orka.core.model.ActionEmphasis.SECONDARY) { viewModel.toggleDarkMode(null) }
                OrkaActionButton(text = "Dark", emphasis = com.orka.core.model.ActionEmphasis.SECONDARY) { viewModel.toggleDarkMode(true) }
                OrkaActionButton(text = "Light", emphasis = com.orka.core.model.ActionEmphasis.SECONDARY) { viewModel.toggleDarkMode(false) }
            }

            Text("Companion model", style = MaterialTheme.typography.headlineMedium)
            Text(state.modelMessage, style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = modelPath,
                onValueChange = { modelPath = it },
                label = { Text("Model path") },
            )
            OrkaActionButton(text = "Import Model", emphasis = com.orka.core.model.ActionEmphasis.SECONDARY) {
                viewModel.importModel(modelPath)
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
