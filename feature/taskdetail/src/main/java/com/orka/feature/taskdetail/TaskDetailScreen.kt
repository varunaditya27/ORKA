package com.orka.feature.taskdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orka.core.common.TimeFormatter
import com.orka.core.common.UrgencyCalculator
import com.orka.core.common.displayName
import com.orka.core.designsystem.OrkaActionButton
import com.orka.core.designsystem.OrkaEyebrow
import com.orka.core.designsystem.OrkaScreenContainer
import com.orka.core.designsystem.OrkaSpacing
import com.orka.core.designsystem.OrkaSurface
import com.orka.core.model.BehaviorProfileRepository
import com.orka.core.model.InteractionEvent
import com.orka.core.model.InteractionType
import com.orka.core.model.Task
import com.orka.core.model.TaskRepository
import com.orka.core.model.TaskRescheduleAdvisor
import com.orka.core.model.TaskStatus
import com.orka.data.scheduler.SchedulerOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TaskDetailUiState(
    val task: Task? = null,
    val interactions: List<InteractionEvent> = emptyList(),
    val reminders: List<com.orka.core.model.ReminderEvent> = emptyList(),
    val rescheduleMessage: String? = null,
)

@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val behaviorProfileRepository: BehaviorProfileRepository,
    private val schedulerOrchestrator: SchedulerOrchestrator,
    private val taskRescheduleAdvisor: TaskRescheduleAdvisor,
) : ViewModel() {
    private val taskId = MutableStateFlow<String?>(null)
    private val rescheduleMessage = MutableStateFlow<String?>(null)

    val uiState = taskId.filterNotNull().flatMapLatest { id ->
        combine(
            taskRepository.observeTask(id),
            taskRepository.observeInteractions(id),
            taskRepository.observeReminders(id),
            rescheduleMessage,
        ) { task, interactions, reminders, message ->
            TaskDetailUiState(task = task, interactions = interactions, reminders = reminders, rescheduleMessage = message)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState())

    fun load(taskId: String) {
        this.taskId.value = taskId
    }

    fun markDone() {
        val task = uiState.value.task ?: return
        viewModelScope.launch {
            taskRepository.updateTaskStatus(task.id, TaskStatus.COMPLETED, Instant.now())
            schedulerOrchestrator.persistSchedule(task.id, emptyList())
            val event = InteractionEvent(taskId = task.id, type = InteractionType.MARK_DONE)
            taskRepository.recordInteraction(event)
            behaviorProfileRepository.updateFromInteraction(task, event)
        }
    }

    fun reschedule() {
        val task = uiState.value.task ?: return
        viewModelScope.launch {
            rescheduleMessage.value = null
            val profile = behaviorProfileRepository.getProfile()
            // Prefer a deadline that respects the user's actual productive hours over blindly
            // repeating the same time-of-day that was already missed once.
            val suggestion = runCatching { taskRescheduleAdvisor.suggestReschedule(task, profile) }.getOrNull()
            val newDeadline = suggestion?.newDeadline ?: task.deadline.plus(Duration.ofDays(1))
            // Keep eventStartTime in sync with whatever delta was actually applied, rather than
            // assuming it's always exactly one day.
            val appliedDelta = Duration.between(task.deadline, newDeadline)
            val updatedTask = task.copy(
                deadline = newDeadline,
                eventStartTime = task.eventStartTime?.plus(appliedDelta),
                updatedAt = Instant.now(),
                status = TaskStatus.PENDING,
                // Must be recomputed — it drives the Tasks list sort order
                // (`ORDER BY urgencyScore DESC`), and pushing the deadline out without updating
                // it would leave the task sorted by its old, now-stale urgency.
                urgencyScore = UrgencyCalculator.urgencyScore(newDeadline, Instant.now(), task.estimatedEffortMinutes),
            )
            taskRepository.upsertTask(updatedTask)
            val (_, reminders) = schedulerOrchestrator.schedule(
                updatedTask,
                com.orka.core.model.SchedulingContext(
                    profile = profile,
                    interactionHistory = uiState.value.interactions,
                ),
            )
            schedulerOrchestrator.persistSchedule(updatedTask.id, reminders)
            rescheduleMessage.value = suggestion?.let {
                "Rescheduled to ${TimeFormatter.formatInstant(newDeadline)} — ${it.reason}"
            }
        }
    }

    fun dismissTask() {
        val task = uiState.value.task ?: return
        viewModelScope.launch {
            taskRepository.updateTaskStatus(task.id, TaskStatus.DISMISSED, Instant.now())
            schedulerOrchestrator.persistSchedule(task.id, emptyList())
            val event = InteractionEvent(taskId = task.id, type = InteractionType.DISMISS_TASK)
            taskRepository.recordInteraction(event)
            behaviorProfileRepository.updateFromInteraction(task, event)
        }
    }
}

@Composable
fun TaskDetailRoute(
    taskId: String,
    onBack: () -> Unit,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(taskId) { viewModel.load(taskId) }

    OrkaSurface {
        OrkaScreenContainer {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                OrkaEyebrow("Task")
            }

            state.task?.let { task ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(OrkaSpacing.sm),
                ) {
                    item {
                        // A LazyColumn item slot stacks multiple emitted composables with zero
                        // gap by default — without this Column's own spacing, every line here
                        // (title, type, category, due date, buttons) would run into the next.
                        Column(verticalArrangement = Arrangement.spacedBy(OrkaSpacing.xs)) {
                            Text(task.title, style = MaterialTheme.typography.headlineLarge)
                            Text("Type ${task.primitiveType.displayName()}", style = MaterialTheme.typography.bodyLarge)
                            Text(task.category.displayName(), style = MaterialTheme.typography.bodyLarge)
                            Text("Due ${TimeFormatter.formatInstant(task.deadline)}", style = MaterialTheme.typography.titleMedium)
                            if (task.primitiveType == com.orka.core.model.PrimitiveType.EVENT) {
                                Text(
                                    "Event ${TimeFormatter.formatInstant(task.eventStartTime ?: task.deadline)}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Profile ${task.preEventProfile?.displayName() ?: "Meeting"}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text("Effort ${task.estimatedEffortMinutes} min", style = MaterialTheme.typography.titleMedium)
                            if (task.status != TaskStatus.COMPLETED && task.status != TaskStatus.DISMISSED) {
                                OrkaActionButton(
                                    text = "Mark Done",
                                    emphasis = com.orka.core.model.ActionEmphasis.PRIMARY,
                                    onClick = viewModel::markDone,
                                )
                                OrkaActionButton(
                                    text = "Reschedule",
                                    emphasis = com.orka.core.model.ActionEmphasis.SECONDARY,
                                    onClick = viewModel::reschedule,
                                )
                                OrkaActionButton(
                                    text = "Dismiss task",
                                    emphasis = com.orka.core.model.ActionEmphasis.TERTIARY,
                                    onClick = viewModel::dismissTask,
                                )
                            }
                            state.rescheduleMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    item {
                        Text("Upcoming reminders", style = MaterialTheme.typography.headlineMedium)
                    }
                    if (state.reminders.isEmpty()) {
                        item {
                            Text(
                                "No reminders scheduled.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.reminders, key = { it.id }) { reminder ->
                        Text(
                            "${reminder.schedulerMode.displayName()} | ${TimeFormatter.formatInstant(reminder.scheduledTime)}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    item {
                        Text("Activity", style = MaterialTheme.typography.headlineMedium)
                    }
                    if (state.interactions.isEmpty()) {
                        item {
                            Text(
                                "No activity yet.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.interactions, key = { it.id }) { event ->
                        Text(
                            "${event.type.displayName()} | ${TimeFormatter.formatInstant(event.timestamp)}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } ?: Text("Loading task...", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
