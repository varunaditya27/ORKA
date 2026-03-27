package com.orka.data.scheduler

import com.google.common.truth.Truth.assertThat
import com.orka.core.model.RlReadiness
import com.orka.core.model.RlRecommendation
import com.orka.core.model.SchedulerMode
import com.orka.core.model.UserSettings
import com.orka.core.testing.FakeAlarmRegistrar
import com.orka.core.testing.FakeRlTrainer
import com.orka.core.testing.FakeSettingsRepository
import com.orka.core.testing.FakeTaskRepository
import com.orka.core.testing.TestFixtures
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SchedulerOrchestratorTest {
    private val task = TestFixtures.task(
        id = "task-orchestrator",
        deadline = TestFixtures.now.plus(Duration.ofDays(3)),
        estimatedEffortMinutes = 120,
    )

    @Test
    fun usesRlRecommendationWhenEnabledAndAvailable() = runBlocking {
        val taskRepository = FakeTaskRepository(tasks = listOf(task))
        val alarmRegistrar = FakeAlarmRegistrar()
        val rlTrainer = FakeRlTrainer(
            initialReadiness = RlReadiness.Ready,
            nextRecommendation = RlRecommendation(nextReminderDelayHours = 2, confidence = 0.7f),
        )
        val orchestrator = SchedulerOrchestrator(
            ruleBased = RuleBasedSchedulerPolicy(),
            adaptive = AdaptiveSchedulerPolicy(),
            alarmRegistrar = alarmRegistrar,
            taskRepository = taskRepository,
            settingsRepository = FakeSettingsRepository(UserSettings(rlSchedulingEnabled = true, adaptiveSchedulingEnabled = true)),
            rlTrainer = rlTrainer,
        )

        val (mode, reminders) = orchestrator.schedule(
            task = task,
            context = com.orka.core.model.SchedulingContext(
                profile = TestFixtures.behaviorProfile(totalInteractions = 24, totalCompletions = 8),
                interactionHistory = emptyList(),
                now = TestFixtures.now,
            ),
        )

        assertThat(mode).isEqualTo(SchedulerMode.RL)
        assertThat(reminders).hasSize(1)
        assertThat(reminders.first().schedulerMode).isEqualTo(SchedulerMode.RL)
        assertThat(reminders.first().scheduledTime).isEqualTo(TestFixtures.now.plus(Duration.ofHours(2)))
    }

    @Test
    fun fallsBackToAdaptiveWhenRlHasNoRecommendationAndProfileHasEnoughData() = runBlocking {
        val taskRepository = FakeTaskRepository(tasks = listOf(task))
        val orchestrator = SchedulerOrchestrator(
            ruleBased = RuleBasedSchedulerPolicy(),
            adaptive = AdaptiveSchedulerPolicy(),
            alarmRegistrar = FakeAlarmRegistrar(),
            taskRepository = taskRepository,
            settingsRepository = FakeSettingsRepository(UserSettings(rlSchedulingEnabled = true, adaptiveSchedulingEnabled = true)),
            rlTrainer = FakeRlTrainer(initialReadiness = RlReadiness.NotReady("Not enough data"), nextRecommendation = null),
        )

        val (mode, reminders) = orchestrator.schedule(
            task = task,
            context = com.orka.core.model.SchedulingContext(
                profile = TestFixtures.behaviorProfile(totalInteractions = 24, totalCompletions = 8),
                interactionHistory = emptyList(),
                now = TestFixtures.now,
            ),
        )

        assertThat(mode).isEqualTo(SchedulerMode.ADAPTIVE)
        assertThat(reminders).isNotEmpty()
        assertThat(reminders.all { it.schedulerMode == SchedulerMode.ADAPTIVE }).isTrue()
    }

    @Test
    fun fallsBackToRuleBasedWhenAdaptiveHasInsufficientBehaviorData() = runBlocking {
        val taskRepository = FakeTaskRepository(tasks = listOf(task))
        val orchestrator = SchedulerOrchestrator(
            ruleBased = RuleBasedSchedulerPolicy(),
            adaptive = AdaptiveSchedulerPolicy(),
            alarmRegistrar = FakeAlarmRegistrar(),
            taskRepository = taskRepository,
            settingsRepository = FakeSettingsRepository(UserSettings(rlSchedulingEnabled = false, adaptiveSchedulingEnabled = true)),
            rlTrainer = FakeRlTrainer(),
        )

        val (mode, reminders) = orchestrator.schedule(
            task = task,
            context = com.orka.core.model.SchedulingContext(
                profile = TestFixtures.behaviorProfile(totalInteractions = 6, totalCompletions = 2),
                interactionHistory = emptyList(),
                now = TestFixtures.now,
            ),
        )

        assertThat(mode).isEqualTo(SchedulerMode.RULE_BASED)
        assertThat(reminders).isNotEmpty()
        assertThat(reminders.all { it.schedulerMode == SchedulerMode.RULE_BASED }).isTrue()
    }

    @Test
    fun persistScheduleCancelsExistingAndRegistersNewReminders() = runBlocking {
        val taskRepository = FakeTaskRepository(tasks = listOf(task))
        val alarmRegistrar = FakeAlarmRegistrar()
        val orchestrator = SchedulerOrchestrator(
            ruleBased = RuleBasedSchedulerPolicy(),
            adaptive = AdaptiveSchedulerPolicy(),
            alarmRegistrar = alarmRegistrar,
            taskRepository = taskRepository,
            settingsRepository = FakeSettingsRepository(),
            rlTrainer = FakeRlTrainer(),
        )
        val newReminders = listOf(
            TestFixtures.reminder(id = "new-r1", taskId = task.id, scheduledTime = TestFixtures.now.plus(Duration.ofHours(3))),
            TestFixtures.reminder(id = "new-r2", taskId = task.id, scheduledTime = TestFixtures.now.plus(Duration.ofHours(6)), sequenceNumber = 2),
        )

        orchestrator.persistSchedule(task.id, newReminders)

        assertThat(alarmRegistrar.cancelForTaskCalls).contains(task.id)
        assertThat(alarmRegistrar.registerCalls).isNotEmpty()
        assertThat(taskRepository.observeReminders(task.id).first()).containsExactlyElementsIn(newReminders).inOrder()
    }
}
