package com.orka.data.execution

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orka.core.model.AlarmCapabilityState
import com.orka.core.model.DiagnosticsRepository
import com.orka.core.model.DiagnosticsSnapshot
import com.orka.core.model.ModelInstaller
import com.orka.core.model.RlReadiness
import com.orka.core.model.RlTrainer
import com.orka.core.model.SchedulerMode
import com.orka.core.model.SettingsRepository
import com.orka.core.model.TaskRepository
import com.orka.core.model.UserSettings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.userSettingsStore by preferencesDataStore(name = "orka_settings")

private object SettingsKeys {
    val darkModeOverride = stringPreferencesKey("dark_mode_override")
    val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
    val diagnosticsEnabled = booleanPreferencesKey("diagnostics_enabled")
    val adaptiveSchedulingEnabled = booleanPreferencesKey("adaptive_scheduling_enabled")
    val rlSchedulingEnabled = booleanPreferencesKey("rl_scheduling_enabled")
    val importedModelPath = stringPreferencesKey("imported_model_path")
}

@Singleton
class PreferencesSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {
    override fun observeSettings(): Flow<UserSettings> = context.userSettingsStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map(::preferencesToSettings)

    override suspend fun current(): UserSettings = observeSettings().first()

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.userSettingsStore.edit { prefs ->
            val updated = transform(preferencesToSettings(prefs))
            prefs[SettingsKeys.darkModeOverride] = when (updated.darkModeOverride) {
                true -> "dark"
                false -> "light"
                null -> "system"
            }
            prefs[SettingsKeys.onboardingCompleted] = updated.onboardingCompleted
            prefs[SettingsKeys.diagnosticsEnabled] = updated.diagnosticsEnabled
            prefs[SettingsKeys.adaptiveSchedulingEnabled] = updated.adaptiveSchedulingEnabled
            prefs[SettingsKeys.rlSchedulingEnabled] = updated.rlSchedulingEnabled
            updated.importedModelPath?.let { prefs[SettingsKeys.importedModelPath] = it } ?: prefs.remove(SettingsKeys.importedModelPath)
        }
    }

    private fun preferencesToSettings(preferences: Preferences): UserSettings = UserSettings(
        darkModeOverride = when (preferences[SettingsKeys.darkModeOverride]) {
            "dark" -> true
            "light" -> false
            else -> null
        },
        onboardingCompleted = preferences[SettingsKeys.onboardingCompleted] ?: false,
        diagnosticsEnabled = preferences[SettingsKeys.diagnosticsEnabled] ?: true,
        adaptiveSchedulingEnabled = preferences[SettingsKeys.adaptiveSchedulingEnabled] ?: true,
        rlSchedulingEnabled = preferences[SettingsKeys.rlSchedulingEnabled] ?: true,
        importedModelPath = preferences[SettingsKeys.importedModelPath],
    )
}

@Singleton
class DefaultDiagnosticsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager,
    private val notificationManager: NotificationManager,
    private val taskRepository: TaskRepository,
    private val settingsRepository: SettingsRepository,
    private val modelInstaller: ModelInstaller,
    private val rlTrainer: RlTrainer,
) : DiagnosticsRepository {
    override fun observeSnapshot(): Flow<DiagnosticsSnapshot> = combine(
        taskRepository.observeNextReminder(),
        taskRepository.observeLastTriggeredReminder(),
        settingsRepository.observeSettings(),
        modelInstaller.observeState(),
        rlTrainer.observeReadiness(),
    ) { nextReminder, lastTriggeredReminder, settings, modelState, readiness ->
        val capabilityState = capabilityState(context, alarmManager, notificationManager)
        DiagnosticsSnapshot(
            capabilityState = capabilityState,
            nextReminder = nextReminder,
            lastFiredReminder = lastTriggeredReminder,
            modelInstallState = modelState,
            activeSchedulerMode = resolveActiveSchedulerMode(settings, readiness),
            rlReadiness = readiness,
            warnings = buildDiagnosticsWarnings(capabilityState, modelState.availability, readiness),
        )
    }

    override suspend fun refreshNow(): DiagnosticsSnapshot = observeSnapshot().first()
}

internal fun resolveActiveSchedulerMode(
    settings: UserSettings,
    readiness: RlReadiness,
): SchedulerMode = when {
    settings.rlSchedulingEnabled && readiness is RlReadiness.Ready -> SchedulerMode.RL
    settings.adaptiveSchedulingEnabled -> SchedulerMode.ADAPTIVE
    else -> SchedulerMode.RULE_BASED
}

internal fun buildDiagnosticsWarnings(
    capabilityState: AlarmCapabilityState,
    modelAvailability: com.orka.core.model.ModelAvailability,
    readiness: RlReadiness,
): List<String> = buildList {
    if (capabilityState != AlarmCapabilityState.READY) add("Alarm delivery requires additional device setup.")
    if (modelAvailability != com.orka.core.model.ModelAvailability.READY) add("Gemma model kit is not installed. ORKA will use fallback parsing.")
    if (readiness is RlReadiness.NotReady) add(readiness.reason)
}

internal fun requiresOemAction(manufacturer: String): Boolean {
    val normalized = manufacturer.trim().lowercase()
    return normalized.contains("xiaomi") ||
        normalized.contains("realme") ||
        normalized.contains("oppo") ||
        normalized.contains("oneplus") ||
        normalized.contains("oplus")
}

private fun capabilityState(
    context: Context,
    alarmManager: AlarmManager,
    notificationManager: NotificationManager,
): AlarmCapabilityState {
    if (!alarmManager.canScheduleExactAlarms()) return AlarmCapabilityState.EXACT_ALARM_DENIED
    if (!notificationManager.areNotificationsEnabled()) return AlarmCapabilityState.NOTIFICATION_BLOCKED
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) return AlarmCapabilityState.BATTERY_OPTIMIZATION_ENABLED
    return if (requiresOemAction(android.os.Build.MANUFACTURER)) {
        AlarmCapabilityState.OEM_ACTION_REQUIRED
    } else {
        AlarmCapabilityState.READY
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PlatformServicesModule {
    @Provides
    fun provideAlarmManager(@ApplicationContext context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Provides
    fun provideNotificationManager(@ApplicationContext context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ExecutionBindingsModule {
    @Binds
    abstract fun bindSettingsRepository(impl: PreferencesSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindDiagnosticsRepository(impl: DefaultDiagnosticsRepository): DiagnosticsRepository
}
