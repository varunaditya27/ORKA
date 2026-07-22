package com.orka.feature.taskdetail

import com.google.common.truth.Truth.assertThat
import com.orka.core.model.InteractionType
import com.orka.core.model.TaskStatus
import com.orka.core.testing.FakeAlarmRegistrar
import com.orka.core.testing.FakeBehaviorProfileRepository
import com.orka.core.testing.FakeSettingsRepository
import com.orka.core.testing.FakeTaskRepository
import com.orka.core.testing.FakeTaskRescheduleAdvisor
import com.orka.core.testing.FakeRlTrainer
import com.orka.core.testing.MainDispatcherRule
import com.orka.core.testing.TestFixtures
import com.orka.data.scheduler.AdaptiveSchedulerPolicy
import com.orka.data.scheduler.PreEventSchedulerPolicy
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
class TaskDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        taskRepository: FakeTaskRepository,
        taskRescheduleAdvisor: FakeTaskRescheduleAdvisor = FakeTaskRescheduleAdvisor(),
    ): TaskDetailViewModel = TaskDetailViewModel(
        taskRepository = taskRepository,
        behaviorProfileRepository = FakeBehaviorProfileRepository(),
        schedulerOrchestrator = SchedulerOrchestrator(
            ruleBased = RuleBasedSchedulerPolicy(),
            adaptive = AdaptiveSchedulerPolicy(),
            preEvent = PreEventSchedulerPolicy(),
            alarmRegistrar = FakeAlarmRegistrar(),
            taskRepository = taskRepository,
            settingsRepository = FakeSettingsRepository(),
            rlTrainer = FakeRlTrainer(),
        ),
        taskRescheduleAdvisor = taskRescheduleAdvisor,
    )

    @Test
    fun markDoneCompletesTaskAndClearsSchedule() = runTest(mainDispatcherRule.dispatcher) {
        val task = TestFixtures.task(id = "task-detail", deadline = TestFixtures.now.plus(Duration.ofHours(10)))
        val reminder = TestFixtures.reminder(id = "reminder-detail", taskId = task.id)
        val taskRepository = FakeTaskRepository(tasks = listOf(task), reminders = listOf(reminder))
        val viewModel = viewModel(taskRepository)

        viewModel.load(task.id)
        advanceUntilIdle()
        viewModel.uiState.first { it.task != null }
        viewModel.markDone()
        advanceUntilIdle()

        assertThat(taskRepository.getTask(task.id)?.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(taskRepository.observeReminders(task.id).first()).isEmpty()
        assertThat(taskRepository.observeInteractions(task.id).first().map { it.type }).containsExactly(InteractionType.MARK_DONE)
    }

    @Test
    fun dismissTaskRecordsDismissedStatusAndInteraction() = runTest(mainDispatcherRule.dispatcher) {
        val task = TestFixtures.task(id = "task-detail-dismiss", deadline = TestFixtures.now.plus(Duration.ofHours(10)))
        val taskRepository = FakeTaskRepository(tasks = listOf(task))
        val viewModel = viewModel(taskRepository)

        viewModel.load(task.id)
        advanceUntilIdle()
        viewModel.uiState.first { it.task != null }
        viewModel.dismissTask()
        advanceUntilIdle()

        assertThat(taskRepository.getTask(task.id)?.status).isEqualTo(TaskStatus.DISMISSED)
        assertThat(taskRepository.observeInteractions(task.id).first().map { it.type }).containsExactly(InteractionType.DISMISS_TASK)
    }

    @Test
    fun markDoneIgnoresReentrantCallWhileFirstCallIsStillInFlight() = runTest(mainDispatcherRule.dispatcher) {
        val task = TestFixtures.task(id = "task-detail-reentrant", deadline = TestFixtures.now.plus(Duration.ofHours(10)))
        val taskRepository = FakeTaskRepository(tasks = listOf(task))
        val viewModel = viewModel(taskRepository)

        viewModel.load(task.id)
        advanceUntilIdle()
        viewModel.uiState.first { it.task != null }

        // Simulates a rapid double-tap: both calls happen before either coroutine has run.
        viewModel.markDone()
        viewModel.markDone()
        advanceUntilIdle()

        assertThat(taskRepository.observeInteractions(task.id).first().map { it.type }).containsExactly(InteractionType.MARK_DONE)
    }

    @Test
    fun rescheduleIgnoresReentrantCallWhileFirstCallIsStillInFlight() = runTest(mainDispatcherRule.dispatcher) {
        val task = TestFixtures.task(id = "task-detail-reschedule-reentrant", deadline = TestFixtures.now.plus(Duration.ofHours(10)))
        val taskRepository = FakeTaskRepository(tasks = listOf(task))
        val viewModel = viewModel(taskRepository)

        viewModel.load(task.id)
        advanceUntilIdle()
        viewModel.uiState.first { it.task != null }

        viewModel.reschedule()
        viewModel.reschedule()
        advanceUntilIdle()

        assertThat(taskRepository.observeInteractions(task.id).first().map { it.type }).containsExactly(InteractionType.RESCHEDULE)
    }
}
