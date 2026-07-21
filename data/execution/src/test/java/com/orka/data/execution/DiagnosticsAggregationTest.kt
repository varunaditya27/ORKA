package com.orka.data.execution

import com.google.common.truth.Truth.assertThat
import com.orka.core.model.AlarmCapabilities
import com.orka.core.model.AlarmCapabilityState
import com.orka.core.model.ModelAvailability
import com.orka.core.model.RlReadiness
import com.orka.core.model.SchedulerMode
import com.orka.core.model.UserSettings
import org.junit.Test

class DiagnosticsAggregationTest {
    @Test
    fun resolvesRlModeWhenRlEnabledAndReady() {
        val mode = resolveActiveSchedulerMode(
            settings = UserSettings(rlSchedulingEnabled = true, adaptiveSchedulingEnabled = true),
            readiness = RlReadiness.Ready,
        )

        assertThat(mode).isEqualTo(SchedulerMode.RL)
    }

    @Test
    fun fallsBackToAdaptiveWhenRlNotReady() {
        val mode = resolveActiveSchedulerMode(
            settings = UserSettings(rlSchedulingEnabled = true, adaptiveSchedulingEnabled = true),
            readiness = RlReadiness.NotReady("Need more data"),
        )

        assertThat(mode).isEqualTo(SchedulerMode.ADAPTIVE)
    }

    @Test
    fun fallsBackToRuleBasedWhenAdaptiveDisabled() {
        val mode = resolveActiveSchedulerMode(
            settings = UserSettings(rlSchedulingEnabled = false, adaptiveSchedulingEnabled = false),
            readiness = RlReadiness.NotReady("Need more data"),
        )

        assertThat(mode).isEqualTo(SchedulerMode.RULE_BASED)
    }

    @Test
    fun buildsWarningsFromCapabilityModelAndReadiness() {
        val warnings = buildDiagnosticsWarnings(
            capabilityState = AlarmCapabilityState.BATTERY_OPTIMIZATION_ENABLED,
            modelAvailability = ModelAvailability.NOT_INSTALLED,
            readiness = RlReadiness.NotReady("Needs more interactions"),
        )

        assertThat(warnings).contains("Alarm delivery requires additional device setup.")
        assertThat(warnings).contains("Gemma model kit is not installed. ORKA will use fallback parsing.")
        assertThat(warnings).contains("Needs more interactions")
    }

    @Test
    fun returnsNoWarningsWhenEverythingIsReady() {
        val warnings = buildDiagnosticsWarnings(
            capabilityState = AlarmCapabilityState.READY,
            modelAvailability = ModelAvailability.READY,
            readiness = RlReadiness.Ready,
        )

        assertThat(warnings).isEmpty()
    }

    @Test
    fun marksOnePlusAsOemActionRequiredManufacturer() {
        assertThat(requiresOemAction("OnePlus")).isTrue()
        assertThat(requiresOemAction("OPLUS")).isTrue()
    }

    @Test
    fun ignoresPixelManufacturerForOemActionRequirement() {
        assertThat(requiresOemAction("Google")).isFalse()
    }

    @Test
    fun derivesReadyWhenAllCapabilitiesGranted() {
        val state = deriveCapabilityState(
            AlarmCapabilities(
                exactAlarmsGranted = true,
                notificationsGranted = true,
                fullScreenIntentGranted = true,
                batteryOptimizationIgnored = true,
                oemActionNeeded = false,
            ),
        )

        assertThat(state).isEqualTo(AlarmCapabilityState.READY)
    }

    @Test
    fun derivesExactAlarmDeniedWithHighestPriority() {
        val state = deriveCapabilityState(
            AlarmCapabilities(
                exactAlarmsGranted = false,
                notificationsGranted = false,
                fullScreenIntentGranted = false,
                batteryOptimizationIgnored = false,
                oemActionNeeded = true,
            ),
        )

        assertThat(state).isEqualTo(AlarmCapabilityState.EXACT_ALARM_DENIED)
    }

    @Test
    fun derivesNotificationBlockedWhenExactAlarmsGrantedButNotificationsAreNot() {
        val state = deriveCapabilityState(
            AlarmCapabilities(exactAlarmsGranted = true, notificationsGranted = false),
        )

        assertThat(state).isEqualTo(AlarmCapabilityState.NOTIFICATION_BLOCKED)
    }

    @Test
    fun derivesFullScreenIntentDeniedWhenOnlyThatCheckFails() {
        val state = deriveCapabilityState(
            AlarmCapabilities(
                exactAlarmsGranted = true,
                notificationsGranted = true,
                fullScreenIntentGranted = false,
                batteryOptimizationIgnored = true,
            ),
        )

        assertThat(state).isEqualTo(AlarmCapabilityState.FULL_SCREEN_INTENT_DENIED)
    }

    @Test
    fun derivesOemActionRequiredOnlyAfterAllOtherChecksPass() {
        val state = deriveCapabilityState(
            AlarmCapabilities(
                exactAlarmsGranted = true,
                notificationsGranted = true,
                fullScreenIntentGranted = true,
                batteryOptimizationIgnored = true,
                oemActionNeeded = true,
            ),
        )

        assertThat(state).isEqualTo(AlarmCapabilityState.OEM_ACTION_REQUIRED)
    }
}
