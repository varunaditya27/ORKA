package com.orka.feature.capture

import com.google.common.truth.Truth.assertThat
import com.orka.core.model.ClarificationReason
import com.orka.core.model.TaskParseResult
import com.orka.core.testing.FakeAlarmRegistrar
import com.orka.core.testing.FakeBehaviorProfileRepository
import com.orka.core.testing.FakeSettingsRepository
import com.orka.core.testing.FakeTaskClarificationAdvisor
import com.orka.core.testing.FakeTaskDraftValidator
import com.orka.core.testing.FakeTaskParser
import com.orka.core.testing.FakeTaskRepository
import com.orka.core.testing.FakeRlTrainer
import com.orka.core.testing.MainDispatcherRule
import com.orka.core.testing.TestFixtures
import com.orka.data.scheduler.AdaptiveSchedulerPolicy
import com.orka.data.scheduler.PreEventSchedulerPolicy
import com.orka.data.scheduler.RuleBasedSchedulerPolicy
import com.orka.data.scheduler.SchedulerOrchestrator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        parseResult: TaskParseResult,
        clarificationAdvisor: FakeTaskClarificationAdvisor = FakeTaskClarificationAdvisor(),
    ): CaptureViewModel {
        val taskRepository = FakeTaskRepository()
        return CaptureViewModel(
            parser = com.orka.core.testing.FakeTaskParser(parseResult),
            validator = FakeTaskDraftValidator(),
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
            taskClarificationAdvisor = clarificationAdvisor,
        )
    }

    @Test
    fun analyseAppliesGemmaSuggestedDeadlineWhenClarificationNeeded() = runTest(mainDispatcherRule.dispatcher) {
        val vagueDraft = TestFixtures.taskDraft(
            rawInput = "pay bill soon",
            deadline = null,
            clarificationNeeded = true,
            clarificationReason = ClarificationReason.VAGUE_TEMPORAL_EXPRESSION,
        )
        val suggested = TestFixtures.now.plus(java.time.Duration.ofDays(2))
        val viewModel = viewModel(
            parseResult = TestFixtures.taskParseResult(draft = vagueDraft),
            clarificationAdvisor = FakeTaskClarificationAdvisor(nextDeadline = suggested),
        )

        viewModel.updateInput("pay bill soon")
        viewModel.analyse()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.smartClarificationInstant).isEqualTo(suggested)
    }

    @Test
    fun analyseLeavesSmartSuggestionNullWhenAdvisorHasNoAnswer() = runTest(mainDispatcherRule.dispatcher) {
        val vagueDraft = TestFixtures.taskDraft(
            rawInput = "pay bill soon",
            deadline = null,
            clarificationNeeded = true,
            clarificationReason = ClarificationReason.NO_DEADLINE_DETECTED,
        )
        val viewModel = viewModel(
            parseResult = TestFixtures.taskParseResult(draft = vagueDraft),
            clarificationAdvisor = FakeTaskClarificationAdvisor(nextDeadline = null),
        )

        viewModel.updateInput("pay bill soon")
        viewModel.analyse()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.smartClarificationInstant).isNull()
    }

    @Test
    fun confirmDraftIgnoresReentrantCallWhileFirstConfirmIsStillInFlight() = runTest(mainDispatcherRule.dispatcher) {
        val resolvedDraft = TestFixtures.taskDraft(clarificationNeeded = false, clarificationReason = null)
        val taskRepository = com.orka.core.testing.FakeTaskRepository()
        val viewModel = CaptureViewModel(
            parser = FakeTaskParser(TestFixtures.taskParseResult(draft = resolvedDraft)),
            validator = FakeTaskDraftValidator(),
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
            taskClarificationAdvisor = FakeTaskClarificationAdvisor(),
        )

        viewModel.updateInput(resolvedDraft.rawInput)
        viewModel.analyse()
        advanceUntilIdle()

        // Simulates a rapid double-tap: both calls happen before either coroutine has run.
        viewModel.confirmDraft()
        viewModel.confirmDraft()
        advanceUntilIdle()

        assertThat(taskRepository.observeActiveTasks().first()).hasSize(1)
    }

    @Test
    fun analyseDoesNotFetchSmartSuggestionWhenClarificationNotNeeded() = runTest(mainDispatcherRule.dispatcher) {
        val resolvedDraft = TestFixtures.taskDraft(clarificationNeeded = false, clarificationReason = null)
        val advisor = FakeTaskClarificationAdvisor(nextDeadline = TestFixtures.now.plus(java.time.Duration.ofDays(1)))
        val viewModel = viewModel(
            parseResult = TestFixtures.taskParseResult(draft = resolvedDraft),
            clarificationAdvisor = advisor,
        )

        viewModel.updateInput(resolvedDraft.rawInput)
        viewModel.analyse()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.smartClarificationInstant).isNull()
    }

    @Test
    fun updateInputTruncatesPastedTextToKeepThePromptBounded() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(parseResult = TestFixtures.taskParseResult())
        val pasted = "x".repeat(10_000)

        viewModel.updateInput(pasted)

        assertThat(viewModel.uiState.value.input.length).isEqualTo(500)
    }
}
