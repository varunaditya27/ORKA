package com.orka.feature.alarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orka.core.common.TimeFormatter
import com.orka.core.common.UrgencyTier
import com.orka.core.common.UrgencyCalculator
import com.orka.core.common.displayName
import com.orka.core.designsystem.AlarmBackground
import com.orka.core.designsystem.OrkaActionButton
import com.orka.core.designsystem.OrkaEyebrow
import com.orka.core.model.AlarmActionOption
import com.orka.core.model.AlarmActionResolver
import com.orka.core.model.BehaviorProfileRepository
import com.orka.core.model.InteractionEvent
import com.orka.core.model.InteractionType
import com.orka.core.model.Task
import com.orka.core.model.TaskRepository
import com.orka.core.model.TaskRescheduleAdvisor
import com.orka.core.model.TaskSnoozeAdvisor
import com.orka.core.model.TaskSplitter
import com.orka.core.model.TaskStatus
import com.orka.data.scheduler.SchedulerOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val DEFAULT_SNOOZE_DURATIONS = mapOf(
    InteractionType.SNOOZE_SHORT to Duration.ofMinutes(30),
    InteractionType.SNOOZE_LONG to Duration.ofHours(3),
)

data class AlarmUiState(
    val task: Task? = null,
    val reminderId: String? = null,
    val reminderLabel: String? = null,
    val actions: List<AlarmActionOption> = emptyList(),
    val history: List<InteractionEvent> = emptyList(),
    val isProcessingSplit: Boolean = false,
    val isHandlingAction: Boolean = false,
    val snoozeDurations: Map<InteractionType, Duration> = DEFAULT_SNOOZE_DURATIONS,
)

@HiltViewModel
class AlarmViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val behaviorProfileRepository: BehaviorProfileRepository,
    private val actionResolver: AlarmActionResolver,
    private val schedulerOrchestrator: SchedulerOrchestrator,
    private val taskSplitter: TaskSplitter,
    private val taskRescheduleAdvisor: TaskRescheduleAdvisor,
    private val taskSnoozeAdvisor: TaskSnoozeAdvisor,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AlarmUiState())
    val uiState = _uiState.asStateFlow()

    fun load(reminderId: String) {
        viewModelScope.launch {
            val reminder = taskRepository.getReminder(reminderId) ?: return@launch
            val task = taskRepository.getTask(reminder.taskId) ?: return@launch
            val history = taskRepository.observeInteractions(task.id).first()
            val actions = actionResolver.resolve(task, history, Instant.now())
            // Show the full-screen interrupt immediately with fixed default durations — never
            // delay it waiting on the model. If Gemma comes back with a better-fitting duration
            // shortly after, refine the button label and the duration actually used, in place.
            _uiState.value = AlarmUiState(
                task = task,
                reminderId = reminderId,
                reminderLabel = reminder.reminderLabel,
                actions = withSnoozeLabels(actions, DEFAULT_SNOOZE_DURATIONS),
                history = history,
                snoozeDurations = DEFAULT_SNOOZE_DURATIONS,
            )

            actions.forEach { action ->
                val isLongSnooze = when (action.type) {
                    InteractionType.SNOOZE_SHORT -> false
                    InteractionType.SNOOZE_LONG -> true
                    else -> return@forEach
                }
                launch {
                    val suggestedMinutes = runCatching {
                        taskSnoozeAdvisor.suggestSnoozeMinutes(task, isLongSnooze)
                    }.getOrNull() ?: return@launch
                    // AlarmActivity is singleTop: a second alarm can call load() again with a
                    // different reminderId while this suggestion is still in flight. Applying it
                    // to whatever's now in state would corrupt the newer task's UI with the
                    // wrong task's suggestion.
                    if (_uiState.value.reminderId != reminderId) return@launch
                    val updatedDurations = _uiState.value.snoozeDurations +
                        (action.type to Duration.ofMinutes(suggestedMinutes.toLong()))
                    _uiState.value = _uiState.value.copy(
                        actions = withSnoozeLabels(_uiState.value.actions, updatedDurations),
                        snoozeDurations = updatedDurations,
                    )
                }
            }
        }
    }

    private fun withSnoozeLabels(
        actions: List<AlarmActionOption>,
        durations: Map<InteractionType, Duration>,
    ): List<AlarmActionOption> = actions.map { action ->
        durations[action.type]?.let { duration ->
            action.copy(label = "Snooze ${TimeFormatter.humanizeDuration(duration)}")
        } ?: action
    }

    fun handleAction(type: InteractionType, onComplete: () -> Unit) {
        val task = _uiState.value.task ?: return
        // Without this, a rapid double-tap (or tapping two different action buttons before the
        // first's onComplete()/activity-finish takes effect) could re-enter this function while
        // the first call is still mid-flight, double-recording the interaction event and, for
        // MARK_DONE/DISMISS_TASK/RESCHEDULE, double-applying the underlying task mutation —
        // mirrors the guard already applied to Capture's confirmDraft() and TaskDetail's actions.
        if (_uiState.value.isHandlingAction) return
        _uiState.value = _uiState.value.copy(isHandlingAction = true)
        viewModelScope.launch {
            try {
            when (type) {
                InteractionType.START_TASK -> {
                    taskRepository.updateTaskStatus(task.id, TaskStatus.ACTIVE)
                }

                InteractionType.MARK_DONE -> {
                    taskRepository.updateTaskStatus(task.id, TaskStatus.COMPLETED, Instant.now())
                    schedulerOrchestrator.persistSchedule(task.id, emptyList())
                }

                InteractionType.RESCHEDULE -> {
                    val profile = behaviorProfileRepository.getProfile()
                    // Prefer a deadline that respects the user's actual productive hours over
                    // blindly repeating the same time-of-day that was already missed once.
                    val suggestion = runCatching { taskRescheduleAdvisor.suggestReschedule(task, profile) }.getOrNull()
                    val newDeadline = suggestion?.newDeadline ?: task.deadline.plus(Duration.ofDays(1))
                    // Keep eventStartTime in sync with whatever delta was actually applied,
                    // rather than assuming it's always exactly one day.
                    val appliedDelta = Duration.between(task.deadline, newDeadline)
                    val updated = task.copy(
                        deadline = newDeadline,
                        eventStartTime = task.eventStartTime?.plus(appliedDelta),
                        updatedAt = Instant.now(),
                        status = TaskStatus.PENDING,
                        // Must be recomputed — it drives the Tasks list sort order
                        // (`ORDER BY urgencyScore DESC`), and pushing the deadline out
                        // without updating it would leave the task sorted by its old urgency.
                        urgencyScore = UrgencyCalculator.urgencyScore(newDeadline, Instant.now(), task.estimatedEffortMinutes),
                    )
                    taskRepository.upsertTask(updated)
                    val (_, reminders) = schedulerOrchestrator.schedule(
                        updated,
                        com.orka.core.model.SchedulingContext(
                            profile = profile,
                            interactionHistory = _uiState.value.history,
                        ),
                    )
                    schedulerOrchestrator.persistSchedule(updated.id, reminders)
                }

                InteractionType.SNOOZE_SHORT -> scheduleSnooze(
                    task,
                    _uiState.value.snoozeDurations[InteractionType.SNOOZE_SHORT] ?: Duration.ofMinutes(30),
                )
                InteractionType.SNOOZE_LONG -> scheduleSnooze(
                    task,
                    _uiState.value.snoozeDurations[InteractionType.SNOOZE_LONG] ?: Duration.ofHours(3),
                )
                InteractionType.SNOOZE_CUSTOM -> scheduleSnooze(task, Duration.ofHours(6))
                InteractionType.SPLIT_TASK -> {
                    _uiState.value = _uiState.value.copy(isProcessingSplit = true)
                    splitTask(task)
                }
                InteractionType.DISMISS_TASK -> {
                    taskRepository.updateTaskStatus(task.id, TaskStatus.DISMISSED, Instant.now())
                    schedulerOrchestrator.persistSchedule(task.id, emptyList())
                }
                InteractionType.ACKNOWLEDGE,
                InteractionType.IGNORE,
                -> Unit
            }

            val event = InteractionEvent(taskId = task.id, reminderId = _uiState.value.reminderId, type = type)
            taskRepository.recordInteraction(event)
            behaviorProfileRepository.updateFromInteraction(task, event)
            onComplete()
            } finally {
                _uiState.value = _uiState.value.copy(isHandlingAction = false)
            }
        }
    }

    private suspend fun scheduleSnooze(task: Task, offset: Duration) {
        val reminder = com.orka.core.model.ReminderEvent(
            taskId = task.id,
            scheduledTime = Instant.now().plus(offset),
            sequenceNumber = 1,
            alarmManagerId = (task.id.hashCode() * 31) + offset.toMinutes().toInt(),
        )
        schedulerOrchestrator.persistSchedule(task.id, listOf(reminder))
    }

    /**
     * Breaks a large task into two smaller, independently-scheduled follow-ups instead of one
     * intimidating block. Prefers [TaskSplitter] for a breakdown specific to what the task
     * actually is (e.g. "Gather data and outline" / "Write and format final report" rather than
     * generic "Part 1"/"Part 2") and falls back to a mechanical even split — exactly like
     * [com.orka.core.model.TaskParser] falls back to regex parsing — whenever the model isn't
     * ready or its suggestion doesn't parse. Either way: the first half is due at the midpoint
     * between now and the original deadline (its own earlier pressure), the second half keeps
     * the original deadline. The original task is retired (DISMISSED, reminders cancelled) since
     * it's now represented by the two parts, which share [Task.linkedEntityId] so TaskDetail/
     * Archive can still show them as related — reusing the task's existing link id rather than
     * minting a new one if it already had one (e.g. it was itself a DERIVED_TASK_EVENT prep-task
     * half), so splitting doesn't silently sever that relationship.
     */
    private suspend fun splitTask(task: Task) {
        val now = Instant.now()
        val totalEffort = task.estimatedEffortMinutes.coerceAtLeast(2)
        val suggestion = runCatching { taskSplitter.suggestSplit(task) }.getOrNull()

        val firstTitle: String
        val firstEffort: Int
        val secondTitle: String
        val secondEffort: Int
        if (suggestion != null) {
            firstTitle = suggestion.firstTitle
            firstEffort = suggestion.firstEffortMinutes
            secondTitle = suggestion.secondTitle
            secondEffort = suggestion.secondEffortMinutes
        } else {
            firstEffort = (totalEffort / 2).coerceAtLeast(1)
            firstTitle = "${task.title} — part 1"
            secondEffort = totalEffort - firstEffort
            secondTitle = "${task.title} — part 2"
        }

        val remaining = Duration.between(now, task.deadline)
        val midpointOffset = if (remaining.isNegative || remaining.isZero) Duration.ZERO else remaining.dividedBy(2)
        val midpointDeadline = now.plus(midpointOffset).coerceAtMost(task.deadline)

        val linkId = task.linkedEntityId ?: UUID.randomUUID().toString()
        val firstPart = task.copy(
            id = UUID.randomUUID().toString(),
            title = firstTitle,
            deadline = midpointDeadline,
            eventStartTime = null,
            estimatedEffortMinutes = firstEffort,
            status = TaskStatus.PENDING,
            linkedEntityId = linkId,
            urgencyScore = UrgencyCalculator.urgencyScore(midpointDeadline, now, firstEffort),
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )
        val secondPart = task.copy(
            id = UUID.randomUUID().toString(),
            title = secondTitle,
            deadline = task.deadline,
            eventStartTime = null,
            estimatedEffortMinutes = secondEffort,
            status = TaskStatus.PENDING,
            linkedEntityId = linkId,
            urgencyScore = UrgencyCalculator.urgencyScore(task.deadline, now, secondEffort),
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )

        schedulerOrchestrator.persistSchedule(task.id, emptyList())
        taskRepository.updateTaskStatus(task.id, TaskStatus.DISMISSED, now)

        val profile = behaviorProfileRepository.getProfile()
        listOf(firstPart, secondPart).forEach { part ->
            val saved = taskRepository.upsertTask(part)
            val (_, reminders) = schedulerOrchestrator.schedule(
                saved,
                com.orka.core.model.SchedulingContext(profile = profile, interactionHistory = emptyList(), now = now),
            )
            schedulerOrchestrator.persistSchedule(saved.id, reminders)
        }
    }
}

@Composable
fun AlarmRoute(
    reminderId: String,
    onComplete: () -> Unit,
    viewModel: AlarmViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(reminderId) { viewModel.load(reminderId) }
    // Splitting via Gemma can take a few seconds; backing out mid-flight (ViewModel gets
    // cleared, cancelling the in-progress coroutine) could orphan a half-completed split —
    // e.g. the original marked DISMISSED but only one of the two new parts created. Swallow
    // back presses for that narrow window instead.
    androidx.activity.compose.BackHandler(enabled = state.isProcessingSplit) {}
    val task = state.task
    val anchor = task?.let { if (it.primitiveType == com.orka.core.model.PrimitiveType.EVENT) it.eventStartTime ?: it.deadline else it.deadline }
    val tier = anchor?.let { UrgencyCalculator.tier(it, Instant.now()) } ?: UrgencyTier.CALM

    AlarmBackground(tier = tier) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OrkaEyebrow("ORKA")
                Text(task?.title ?: "Loading...", style = MaterialTheme.typography.displayLarge)
                state.reminderLabel?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                task?.let {
                    val effectiveAnchor = if (it.primitiveType == com.orka.core.model.PrimitiveType.EVENT) {
                        it.eventStartTime ?: it.deadline
                    } else {
                        it.deadline
                    }
                    val delta = Duration.between(Instant.now(), effectiveAnchor)
                    val dueText = if (delta.isNegative) {
                        "Overdue by ${TimeFormatter.humanizeDuration(delta.abs())}"
                    } else {
                        if (it.primitiveType == com.orka.core.model.PrimitiveType.EVENT) {
                            "Starts in ${TimeFormatter.humanizeDuration(delta)}"
                        } else {
                            "Due in ${TimeFormatter.humanizeDuration(delta)}"
                        }
                    }
                    Text(
                        dueText,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(it.category.displayName(), style = MaterialTheme.typography.labelMedium)
                }
            }

            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.isProcessingSplit) {
                    Text(
                        "Breaking this down...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.actions.forEach { action ->
                        OrkaActionButton(
                            text = action.label,
                            emphasis = action.emphasis,
                            enabled = !state.isHandlingAction,
                            onClick = { viewModel.handleAction(action.type, onComplete) },
                        )
                    }
                }
            }
        }
    }
}
