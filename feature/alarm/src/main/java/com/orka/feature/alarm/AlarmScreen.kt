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
import com.orka.core.designsystem.AlarmBackground
import com.orka.core.designsystem.OrkaActionButton
import com.orka.core.model.AlarmActionOption
import com.orka.core.model.AlarmActionResolver
import com.orka.core.model.BehaviorProfileRepository
import com.orka.core.model.InteractionEvent
import com.orka.core.model.InteractionType
import com.orka.core.model.Task
import com.orka.core.model.TaskRepository
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

data class AlarmUiState(
    val task: Task? = null,
    val reminderId: String? = null,
    val reminderLabel: String? = null,
    val actions: List<AlarmActionOption> = emptyList(),
    val history: List<InteractionEvent> = emptyList(),
)

@HiltViewModel
class AlarmViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val behaviorProfileRepository: BehaviorProfileRepository,
    private val actionResolver: AlarmActionResolver,
    private val schedulerOrchestrator: SchedulerOrchestrator,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AlarmUiState())
    val uiState = _uiState.asStateFlow()

    fun load(reminderId: String) {
        viewModelScope.launch {
            val reminder = taskRepository.getReminder(reminderId) ?: return@launch
            val task = taskRepository.getTask(reminder.taskId) ?: return@launch
            val history = taskRepository.observeInteractions(task.id).first()
            _uiState.value = AlarmUiState(
                task = task,
                reminderId = reminderId,
                reminderLabel = reminder.reminderLabel,
                actions = actionResolver.resolve(task, history, Instant.now()),
                history = history,
            )
        }
    }

    fun handleAction(type: InteractionType, onComplete: () -> Unit) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            when (type) {
                InteractionType.START_TASK -> {
                    taskRepository.updateTaskStatus(task.id, TaskStatus.ACTIVE)
                }

                InteractionType.MARK_DONE -> {
                    taskRepository.updateTaskStatus(task.id, TaskStatus.COMPLETED, Instant.now())
                    schedulerOrchestrator.persistSchedule(task.id, emptyList())
                }

                InteractionType.RESCHEDULE -> {
                    val newDeadline = task.deadline.plus(Duration.ofDays(1))
                    val updated = task.copy(
                        deadline = newDeadline,
                        eventStartTime = task.eventStartTime?.plus(Duration.ofDays(1)),
                        updatedAt = Instant.now(),
                        status = TaskStatus.PENDING,
                        // Must be recomputed — it drives the Tasks list sort order
                        // (`ORDER BY urgencyScore DESC`), and pushing the deadline out a day
                        // without updating it would leave the task sorted by its old urgency.
                        urgencyScore = UrgencyCalculator.urgencyScore(newDeadline, Instant.now(), task.estimatedEffortMinutes),
                    )
                    taskRepository.upsertTask(updated)
                    val profile = behaviorProfileRepository.getProfile()
                    val (_, reminders) = schedulerOrchestrator.schedule(
                        updated,
                        com.orka.core.model.SchedulingContext(
                            profile = profile,
                            interactionHistory = _uiState.value.history,
                        ),
                    )
                    schedulerOrchestrator.persistSchedule(updated.id, reminders)
                }

                InteractionType.SNOOZE_SHORT -> scheduleSnooze(task, Duration.ofMinutes(30))
                InteractionType.SNOOZE_LONG -> scheduleSnooze(task, Duration.ofHours(3))
                InteractionType.SNOOZE_CUSTOM -> scheduleSnooze(task, Duration.ofHours(6))
                InteractionType.SPLIT_TASK -> splitTask(task)
                InteractionType.ACKNOWLEDGE,
                InteractionType.IGNORE,
                -> Unit
            }

            val event = InteractionEvent(taskId = task.id, reminderId = _uiState.value.reminderId, type = type)
            taskRepository.recordInteraction(event)
            behaviorProfileRepository.updateFromInteraction(task, event)
            onComplete()
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
     * intimidating block: the first half is due at the midpoint between now and the original
     * deadline (so it gets its own earlier pressure), the second half keeps the original
     * deadline. The original task is retired (DISMISSED, reminders cancelled) since it's now
     * represented by the two parts, which share [Task.linkedEntityId] so TaskDetail/Archive can
     * still show them as related.
     */
    private suspend fun splitTask(task: Task) {
        val now = Instant.now()
        val totalEffort = task.estimatedEffortMinutes.coerceAtLeast(2)
        val firstEffort = (totalEffort / 2).coerceAtLeast(1)
        val secondEffort = totalEffort - firstEffort

        val remaining = Duration.between(now, task.deadline)
        val midpointOffset = if (remaining.isNegative || remaining.isZero) Duration.ZERO else remaining.dividedBy(2)
        val midpointDeadline = now.plus(midpointOffset).coerceAtMost(task.deadline)

        val linkId = UUID.randomUUID().toString()
        val firstPart = task.copy(
            id = UUID.randomUUID().toString(),
            title = "${task.title} — part 1",
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
            title = "${task.title} — part 2",
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
                Text("ORKA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(it.category.name, style = MaterialTheme.typography.labelMedium)
                }
            }

            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                state.actions.forEach { action ->
                    OrkaActionButton(
                        text = action.label,
                        emphasis = action.emphasis,
                        onClick = { viewModel.handleAction(action.type, onComplete) },
                    )
                }
            }
        }
    }
}
