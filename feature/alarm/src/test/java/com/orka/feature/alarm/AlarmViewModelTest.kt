package com.orka.feature.alarm

import com.google.common.truth.Truth.assertThat
import com.orka.core.model.InteractionType
import com.orka.core.model.TaskStatus
import com.orka.core.testing.FakeAlarmActionResolver
import com.orka.core.testing.FakeAlarmRegistrar
import com.orka.core.testing.FakeBehaviorProfileRepository
import com.orka.core.testing.FakeSettingsRepository
import com.orka.core.testing.FakeTaskRepository
import com.orka.core.testing.FakeTaskRescheduleAdvisor
import com.orka.core.testing.FakeTaskSnoozeAdvisor
import com.orka.core.testing.FakeTaskSplitter
import com.orka.core.testing.FakeRlTrainer
import com.orka.core.testing.MainDispatcherRule
import com.orka.core.testing.TestFixtures
import com.orka.data.execution.DefaultAlarmActionResolver
import com.orka.data.scheduler.AdaptiveSchedulerPolicy
import com.orka.data.scheduler.PreEventSchedulerPolicy
import com.orka.data.scheduler.RuleBasedSchedulerPolicy
import com.orka.data.scheduler.SchedulerOrchestrator
import java.time.Duration
import java.time.Instant
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
            taskSplitter = FakeTaskSplitter(),
            taskRescheduleAdvisor = FakeTaskRescheduleAdvisor(),
            taskSnoozeAdvisor = FakeTaskSnoozeAdvisor(),
            schedulerOrchestrator = SchedulerOrchestrator(
                ruleBased = RuleBasedSchedulerPolicy(),
                adaptive = AdaptiveSchedulerPolicy(),
                preEvent = PreEventSchedulerPolicy(),
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
    fun splitTaskRetiresOriginalAndCreatesTwoLinkedFollowUps() = runTest(mainDispatcherRule.dispatcher) {
        val task = TestFixtures.task(
            id = "task-split",
            deadline = TestFixtures.now.plus(Duration.ofHours(100)),
            estimatedEffortMinutes = 180,
        )
        val reminder = TestFixtures.reminder(id = "reminder-split", taskId = task.id)
        val taskRepository = FakeTaskRepository(tasks = listOf(task), reminders = listOf(reminder))
        val alarmRegistrar = FakeAlarmRegistrar()
        val viewModel = AlarmViewModel(
            taskRepository = taskRepository,
            behaviorProfileRepository = FakeBehaviorProfileRepository(),
            actionResolver = DefaultAlarmActionResolver(),
            taskSplitter = FakeTaskSplitter(),
            taskRescheduleAdvisor = FakeTaskRescheduleAdvisor(),
            taskSnoozeAdvisor = FakeTaskSnoozeAdvisor(),
            schedulerOrchestrator = SchedulerOrchestrator(
                ruleBased = RuleBasedSchedulerPolicy(),
                adaptive = AdaptiveSchedulerPolicy(),
                preEvent = PreEventSchedulerPolicy(),
                alarmRegistrar = alarmRegistrar,
                taskRepository = taskRepository,
                settingsRepository = FakeSettingsRepository(),
                rlTrainer = FakeRlTrainer(),
            ),
        )

        viewModel.load(reminder.id)
        advanceUntilIdle()
        viewModel.handleAction(InteractionType.SPLIT_TASK) {}
        advanceUntilIdle()

        assertThat(taskRepository.getTask(task.id)?.status).isEqualTo(TaskStatus.DISMISSED)

        val activeTasks = taskRepository.observeActiveTasks().first()
        val parts = activeTasks.filter { it.linkedEntityId != null && it.id != task.id }
        assertThat(parts).hasSize(2)
        assertThat(parts.map { it.status }).containsExactly(TaskStatus.PENDING, TaskStatus.PENDING)
        assertThat(parts[0].linkedEntityId).isEqualTo(parts[1].linkedEntityId)
        assertThat(parts.sumOf { it.estimatedEffortMinutes }).isEqualTo(task.estimatedEffortMinutes)
        assertThat(parts.map { it.deadline }).contains(task.deadline)
        assertThat(parts.all { it.deadline <= task.deadline }).isTrue()

        assertThat(taskRepository.observeInteractions(task.id).first().map { it.type }).contains(InteractionType.SPLIT_TASK)
        assertThat(alarmRegistrar.cancelForTaskCalls).contains(task.id)
    }

    @Test
    fun splitTaskUsesGemmaSuggestionTitlesWhenAvailable() = runTest(mainDispatcherRule.dispatcher) {
        val task = TestFixtures.task(
            id = "task-split-gemma",
            deadline = TestFixtures.now.plus(Duration.ofHours(100)),
            estimatedEffortMinutes = 180,
        )
        val reminder = TestFixtures.reminder(id = "reminder-split-gemma", taskId = task.id)
        val taskRepository = FakeTaskRepository(tasks = listOf(task), reminders = listOf(reminder))
        val taskSplitter = FakeTaskSplitter(
            nextSuggestion = com.orka.core.model.TaskSplitSuggestion(
                firstTitle = "Gather data and outline report",
                firstEffortMinutes = 90,
                secondTitle = "Write and format final report",
                secondEffortMinutes = 90,
            ),
        )
        val viewModel = AlarmViewModel(
            taskRepository = taskRepository,
            behaviorProfileRepository = FakeBehaviorProfileRepository(),
            actionResolver = DefaultAlarmActionResolver(),
            taskSplitter = taskSplitter,
            taskRescheduleAdvisor = FakeTaskRescheduleAdvisor(),
            taskSnoozeAdvisor = FakeTaskSnoozeAdvisor(),
            schedulerOrchestrator = SchedulerOrchestrator(
                ruleBased = RuleBasedSchedulerPolicy(),
                adaptive = AdaptiveSchedulerPolicy(),
                preEvent = PreEventSchedulerPolicy(),
                alarmRegistrar = FakeAlarmRegistrar(),
                taskRepository = taskRepository,
                settingsRepository = FakeSettingsRepository(),
                rlTrainer = FakeRlTrainer(),
            ),
        )

        viewModel.load(reminder.id)
        advanceUntilIdle()
        viewModel.handleAction(InteractionType.SPLIT_TASK) {}
        advanceUntilIdle()

        val parts = taskRepository.observeActiveTasks().first().filter { it.id != task.id }
        assertThat(parts.map { it.title }).containsExactly(
            "Gather data and outline report",
            "Write and format final report",
        )
        assertThat(parts.map { it.estimatedEffortMinutes }).containsExactly(90, 90)
    }

    @Test
    fun rescheduleUsesGemmaSuggestionWhenAvailableAndKeepsEventStartTimeInSync() = runTest(mainDispatcherRule.dispatcher) {
        val originalDeadline = TestFixtures.now.minusSeconds(3600)
        val task = TestFixtures.task(
            id = "task-reschedule-gemma",
            deadline = originalDeadline,
            primitiveType = com.orka.core.model.PrimitiveType.EVENT,
            eventStartTime = originalDeadline,
        )
        val reminder = TestFixtures.reminder(id = "reminder-reschedule-gemma", taskId = task.id)
        val taskRepository = FakeTaskRepository(tasks = listOf(task), reminders = listOf(reminder))
        val suggestedDeadline = TestFixtures.now.plusSeconds(3600 * 30)
        val rescheduleAdvisor = FakeTaskRescheduleAdvisor(
            nextSuggestion = com.orka.core.model.RescheduleSuggestion(
                newDeadline = suggestedDeadline,
                reason = "Fits your evening productive hours",
            ),
        )
        val viewModel = AlarmViewModel(
            taskRepository = taskRepository,
            behaviorProfileRepository = FakeBehaviorProfileRepository(),
            actionResolver = DefaultAlarmActionResolver(),
            taskSplitter = FakeTaskSplitter(),
            taskRescheduleAdvisor = rescheduleAdvisor,
            taskSnoozeAdvisor = FakeTaskSnoozeAdvisor(),
            schedulerOrchestrator = SchedulerOrchestrator(
                ruleBased = RuleBasedSchedulerPolicy(),
                adaptive = AdaptiveSchedulerPolicy(),
                preEvent = PreEventSchedulerPolicy(),
                alarmRegistrar = FakeAlarmRegistrar(),
                taskRepository = taskRepository,
                settingsRepository = FakeSettingsRepository(),
                rlTrainer = FakeRlTrainer(),
            ),
        )

        viewModel.load(reminder.id)
        advanceUntilIdle()
        viewModel.handleAction(InteractionType.RESCHEDULE) {}
        advanceUntilIdle()

        val updated = taskRepository.getTask(task.id)
        assertThat(updated?.deadline).isEqualTo(suggestedDeadline)
        assertThat(updated?.eventStartTime).isEqualTo(suggestedDeadline)
        assertThat(updated?.status).isEqualTo(TaskStatus.PENDING)
    }

    @Test
    fun rescheduleFallsBackToPlusOneDayWhenGemmaSuggestionUnavailable() = runTest(mainDispatcherRule.dispatcher) {
        val originalDeadline = TestFixtures.now.minusSeconds(3600)
        val task = TestFixtures.task(id = "task-reschedule-fallback", deadline = originalDeadline)
        val reminder = TestFixtures.reminder(id = "reminder-reschedule-fallback", taskId = task.id)
        val taskRepository = FakeTaskRepository(tasks = listOf(task), reminders = listOf(reminder))
        val viewModel = AlarmViewModel(
            taskRepository = taskRepository,
            behaviorProfileRepository = FakeBehaviorProfileRepository(),
            actionResolver = DefaultAlarmActionResolver(),
            taskSplitter = FakeTaskSplitter(),
            taskRescheduleAdvisor = FakeTaskRescheduleAdvisor(nextSuggestion = null),
            taskSnoozeAdvisor = FakeTaskSnoozeAdvisor(),
            schedulerOrchestrator = SchedulerOrchestrator(
                ruleBased = RuleBasedSchedulerPolicy(),
                adaptive = AdaptiveSchedulerPolicy(),
                preEvent = PreEventSchedulerPolicy(),
                alarmRegistrar = FakeAlarmRegistrar(),
                taskRepository = taskRepository,
                settingsRepository = FakeSettingsRepository(),
                rlTrainer = FakeRlTrainer(),
            ),
        )

        viewModel.load(reminder.id)
        advanceUntilIdle()
        viewModel.handleAction(InteractionType.RESCHEDULE) {}
        advanceUntilIdle()

        assertThat(taskRepository.getTask(task.id)?.deadline).isEqualTo(originalDeadline.plus(Duration.ofDays(1)))
    }

    @Test
    fun loadAppliesGemmaSuggestedSnoozeDurationToLabelAndSchedule() = runTest(mainDispatcherRule.dispatcher) {
        val task = TestFixtures.task(id = "task-snooze", deadline = TestFixtures.now.plus(Duration.ofHours(5)))
        val reminder = TestFixtures.reminder(id = "reminder-snooze", taskId = task.id)
        val taskRepository = FakeTaskRepository(tasks = listOf(task), reminders = listOf(reminder))
        val snoozeAdvisor = FakeTaskSnoozeAdvisor(nextSuggestedMinutes = 45)
        // A deterministic resolver, not DefaultAlarmActionResolver: its contextAction depends on
        // real wall-clock Instant.now() vs. task.deadline, which is unrelated to what this test
        // is actually verifying (that a Gemma suggestion updates the snooze label/schedule).
        val actionResolver = FakeAlarmActionResolver(
            listOf(TestFixtures.alarmAction(type = InteractionType.SNOOZE_SHORT, label = "Snooze 30 min")),
        )
        val viewModel = AlarmViewModel(
            taskRepository = taskRepository,
            behaviorProfileRepository = FakeBehaviorProfileRepository(),
            actionResolver = actionResolver,
            taskSplitter = FakeTaskSplitter(),
            taskRescheduleAdvisor = FakeTaskRescheduleAdvisor(),
            taskSnoozeAdvisor = snoozeAdvisor,
            schedulerOrchestrator = SchedulerOrchestrator(
                ruleBased = RuleBasedSchedulerPolicy(),
                adaptive = AdaptiveSchedulerPolicy(),
                preEvent = PreEventSchedulerPolicy(),
                alarmRegistrar = FakeAlarmRegistrar(),
                taskRepository = taskRepository,
                settingsRepository = FakeSettingsRepository(),
                rlTrainer = FakeRlTrainer(),
            ),
        )

        viewModel.load(reminder.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.snoozeDurations[InteractionType.SNOOZE_SHORT]).isEqualTo(Duration.ofMinutes(45))
        val snoozeAction = state.actions.first { it.type == InteractionType.SNOOZE_SHORT }
        assertThat(snoozeAction.label).isEqualTo("Snooze 45m")

        val before = Instant.now()
        viewModel.handleAction(InteractionType.SNOOZE_SHORT) {}
        advanceUntilIdle()
        val after = Instant.now()

        val scheduled = taskRepository.observeReminders(task.id).first().single()
        assertThat(scheduled.scheduledTime).isAtLeast(before.plus(Duration.ofMinutes(45)))
        assertThat(scheduled.scheduledTime).isAtMost(after.plus(Duration.ofMinutes(45)))
    }

    @Test
    fun loadKeepsFixedSnoozeDurationWhenGemmaSuggestionUnavailable() = runTest(mainDispatcherRule.dispatcher) {
        val task = TestFixtures.task(id = "task-snooze-fallback", deadline = TestFixtures.now.plus(Duration.ofHours(5)))
        val reminder = TestFixtures.reminder(id = "reminder-snooze-fallback", taskId = task.id)
        val taskRepository = FakeTaskRepository(tasks = listOf(task), reminders = listOf(reminder))
        val actionResolver = FakeAlarmActionResolver(
            listOf(TestFixtures.alarmAction(type = InteractionType.SNOOZE_SHORT, label = "Snooze 30 min")),
        )
        val viewModel = AlarmViewModel(
            taskRepository = taskRepository,
            behaviorProfileRepository = FakeBehaviorProfileRepository(),
            actionResolver = actionResolver,
            taskSplitter = FakeTaskSplitter(),
            taskRescheduleAdvisor = FakeTaskRescheduleAdvisor(),
            taskSnoozeAdvisor = FakeTaskSnoozeAdvisor(nextSuggestedMinutes = null),
            schedulerOrchestrator = SchedulerOrchestrator(
                ruleBased = RuleBasedSchedulerPolicy(),
                adaptive = AdaptiveSchedulerPolicy(),
                preEvent = PreEventSchedulerPolicy(),
                alarmRegistrar = FakeAlarmRegistrar(),
                taskRepository = taskRepository,
                settingsRepository = FakeSettingsRepository(),
                rlTrainer = FakeRlTrainer(),
            ),
        )

        viewModel.load(reminder.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.snoozeDurations[InteractionType.SNOOZE_SHORT]).isEqualTo(Duration.ofMinutes(30))
        val snoozeAction = state.actions.first { it.type == InteractionType.SNOOZE_SHORT }
        assertThat(snoozeAction.label).isEqualTo("Snooze 30m")
    }

    @Test
    fun loadExposesResolvedActionSurface() = runTest(mainDispatcherRule.dispatcher) {
        val task = TestFixtures.task(id = "task-actions", deadline = TestFixtures.now.plus(Duration.ofHours(5)))
        val reminder = TestFixtures.reminder(id = "reminder-actions", taskId = task.id)
        val taskRepository = FakeTaskRepository(tasks = listOf(task), reminders = listOf(reminder))
        val expectedActions = listOf(
            TestFixtures.alarmAction(type = InteractionType.START_TASK, label = "Start Now"),
            // AlarmViewModel rewrites SNOOZE_* labels to reflect the actual duration that will
            // be used ("Snooze 30m", from the default map) regardless of what the resolver
            // originally labeled it — this is intentional; see the loadKeepsFixed/loadApplies
            // snooze tests for that behavior specifically.
            TestFixtures.alarmAction(type = InteractionType.SNOOZE_SHORT, label = "Snooze 30 min"),
            TestFixtures.alarmAction(type = InteractionType.MARK_DONE, label = "Done"),
        )
        val expectedActionsAfterSnoozeLabelRewrite = expectedActions.map {
            if (it.type == InteractionType.SNOOZE_SHORT) it.copy(label = "Snooze 30m") else it
        }
        val viewModel = AlarmViewModel(
            taskRepository = taskRepository,
            behaviorProfileRepository = FakeBehaviorProfileRepository(),
            actionResolver = FakeAlarmActionResolver(expectedActions),
            taskSplitter = FakeTaskSplitter(),
            taskRescheduleAdvisor = FakeTaskRescheduleAdvisor(),
            taskSnoozeAdvisor = FakeTaskSnoozeAdvisor(),
            schedulerOrchestrator = SchedulerOrchestrator(
                ruleBased = RuleBasedSchedulerPolicy(),
                adaptive = AdaptiveSchedulerPolicy(),
                preEvent = PreEventSchedulerPolicy(),
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
        assertThat(state.actions).containsExactlyElementsIn(expectedActionsAfterSnoozeLabelRewrite).inOrder()
    }
}
