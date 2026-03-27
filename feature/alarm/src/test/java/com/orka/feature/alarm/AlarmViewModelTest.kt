package com.orka.feature.alarm

import com.google.common.truth.Truth.assertThat
import com.orka.core.model.InteractionType
import com.orka.core.model.TaskStatus
import com.orka.core.testing.FakeAlarmActionResolver
import com.orka.core.testing.FakeAlarmRegistrar
import com.orka.core.testing.FakeBehaviorProfileRepository
import com.orka.core.testing.FakeSettingsRepository
import com.orka.core.testing.FakeTaskRepository
import com.orka.core.testing.FakeRlTrainer
import com.orka.core.testing.MainDispatcherRule
import com.orka.core.testing.TestFixtures
import com.orka.data.execution.DefaultAlarmActionResolver
import com.orka.data.scheduler.AdaptiveSchedulerPolicy
import com.orka.data.scheduler.RuleBasedSchedulerPolicy
import com.orka.data.scheduler.SchedulerOrchestrator
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun markDoneCompletesTaskAndClearsSchedule() = runTest(mainDispatcherRule.dispatcher) {
        val task = TestFixtures.task(id = "task-alarm", deadline = TestFixtures.now.plus(Duration.ofHours(10)))
        val reminder = TestFixtures.reminder(id = "reminder-alarm", taskId = task.id)
        val taskRepository = FakeTaskRepository(tasks = listOf(task), reminders = listOf(reminder))
        val alarmRegistrar = FakeAlarmRegistrar()
        val viewModel = AlarmViewModel(
            taskRepository = taskRepository,
            behaviorProfileRepository = FakeBehaviorProfileRepository(),
            actionResolver = DefaultAlarmActionResolver(),
            schedulerOrchestrator = SchedulerOrchestrator(
                ruleBased = RuleBasedSchedulerPolicy(),
                adaptive = AdaptiveSchedulerPolicy(),
                alarmRegistrar = alarmRegistrar,
                taskRepository = taskRepository,
                settingsRepository = FakeSettingsRepository(),
                rlTrainer = FakeRlTrainer(),
            ),
        )

        viewModel.load(reminder.id)
        advanceUntilIdle()
        viewModel.handleAction(InteractionType.MARK_DONE) {}
        advanceUntilIdle()

        assertThat(taskRepository.getTask(task.id)?.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(taskRepository.observeReminders(task.id).first()).isEmpty()
        assertThat(taskRepository.observeInteractions(task.id).first().map { it.type }).contains(InteractionType.MARK_DONE)
        assertThat(alarmRegistrar.cancelForTaskCalls).contains(task.id)
    }

    @Test
    fun loadExposesResolvedActionSurface() = runTest(mainDispatcherRule.dispatcher) {
        val task = TestFixtures.task(id = "task-actions", deadline = TestFixtures.now.plus(Duration.ofHours(5)))
        val reminder = TestFixtures.reminder(id = "reminder-actions", taskId = task.id)
        val taskRepository = FakeTaskRepository(tasks = listOf(task), reminders = listOf(reminder))
        val expectedActions = listOf(
            TestFixtures.alarmAction(type = InteractionType.START_TASK, label = "Start Now"),
            TestFixtures.alarmAction(type = InteractionType.SNOOZE_SHORT, label = "Snooze 30 min"),
            TestFixtures.alarmAction(type = InteractionType.MARK_DONE, label = "Done"),
        )
        val viewModel = AlarmViewModel(
            taskRepository = taskRepository,
            behaviorProfileRepository = FakeBehaviorProfileRepository(),
            actionResolver = FakeAlarmActionResolver(expectedActions),
            schedulerOrchestrator = SchedulerOrchestrator(
                ruleBased = RuleBasedSchedulerPolicy(),
                adaptive = AdaptiveSchedulerPolicy(),
                alarmRegistrar = FakeAlarmRegistrar(),
                taskRepository = taskRepository,
                settingsRepository = FakeSettingsRepository(),
                rlTrainer = FakeRlTrainer(),
            ),
        )

        viewModel.load(reminder.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.task?.id).isEqualTo(task.id)
        assertThat(state.actions).containsExactlyElementsIn(expectedActions).inOrder()
    }
}
