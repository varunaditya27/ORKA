package com.orka.feature.taskdetail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orka.core.common.TimeFormatter
import com.orka.core.common.UrgencyCalculator
import com.orka.core.designsystem.OrkaActionButton
import com.orka.core.designsystem.OrkaScreenContainer
import com.orka.core.designsystem.OrkaSurface
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
)

@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val behaviorProfileRepository: BehaviorProfileRepository,
    private val schedulerOrchestrator: SchedulerOrchestrator,
) : ViewModel() {
    private val taskId = MutableStateFlow<String?>(null)

    val uiState = taskId.filterNotNull().flatMapLatest { id ->
        combine(
            taskRepository.observeTask(id),
            taskRepository.observeInteractions(id),
            taskRepository.observeReminders(id),
        ) { task, interactions, reminders ->
            TaskDetailUiState(task = task, interactions = interactions, reminders = reminders)
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

    fun rescheduleOneDay() {
        val task = uiState.value.task ?: return
        viewModelScope.launch {
            val newDeadline = task.deadline.plus(Duration.ofDays(1))
            val updatedTask = task.copy(
                deadline = newDeadline,
                eventStartTime = task.eventStartTime?.plus(Duration.ofDays(1)),
                updatedAt = Instant.now(),
                status = TaskStatus.PENDING,
                // Must be recomputed — it drives the Tasks list sort order
                // (`ORDER BY urgencyScore DESC`), and pushing the deadline out a day without
                // updating it would leave the task sorted by its old, now-stale urgency.
                urgencyScore = UrgencyCalculator.urgencyScore(newDeadline, Instant.now(), task.estimatedEffortMinutes),
            )
            taskRepository.upsertTask(updatedTask)
            val profile = behaviorProfileRepository.getProfile()
            val (_, reminders) = schedulerOrchestrator.schedule(
                updatedTask,
                com.orka.core.model.SchedulingContext(
                    profile = profile,
                    interactionHistory = uiState.value.interactions,
                ),
            )
            schedulerOrchestrator.persistSchedule(updatedTask.id, reminders)
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
            OrkaActionButton(
                text = "Back",
                emphasis = com.orka.core.model.ActionEmphasis.TERTIARY,
                onClick = onBack,
            )

            state.task?.let { task ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    item {
                        Text(task.title, style = MaterialTheme.typography.headlineLarge)
                        Text("Type ${task.primitiveType.name}", style = MaterialTheme.typography.bodyLarge)
                        Text(task.category.name, style = MaterialTheme.typography.bodyLarge)
                        Text("Due ${TimeFormatter.formatInstant(task.deadline)}", style = MaterialTheme.typography.titleMedium)
                        if (task.primitiveType == com.orka.core.model.PrimitiveType.EVENT) {
                            Text(
                                "Event ${TimeFormatter.formatInstant(task.eventStartTime ?: task.deadline)}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("Profile ${task.preEventProfile?.name ?: "MEETING"}", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Effort ${task.estimatedEffortMinutes} min", style = MaterialTheme.typography.titleMedium)
                        if (task.status != TaskStatus.COMPLETED && task.status != TaskStatus.DISMISSED) {
                            OrkaActionButton(
                                text = "Mark Done",
                                emphasis = com.orka.core.model.ActionEmphasis.PRIMARY,
                                onClick = viewModel::markDone,
                            )
                            OrkaActionButton(
                                text = "Reschedule +1 day",
                                emphasis = com.orka.core.model.ActionEmphasis.SECONDARY,
                                onClick = viewModel::rescheduleOneDay,
                            )
                            OrkaActionButton(
                                text = "Dismiss task",
                                emphasis = com.orka.core.model.ActionEmphasis.TERTIARY,
                                onClick = viewModel::dismissTask,
                            )
                        }
                        Text("Upcoming reminders", style = MaterialTheme.typography.headlineMedium)
                    }
                    items(state.reminders, key = { it.id }) { reminder ->
                        Text(
                            "${reminder.schedulerMode.name} | ${TimeFormatter.formatInstant(reminder.scheduledTime)}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    item {
                        Text("Activity", style = MaterialTheme.typography.headlineMedium)
                    }
                    items(state.interactions, key = { it.id }) { event ->
                        Text(
                            "${event.type.name} | ${TimeFormatter.formatInstant(event.timestamp)}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } ?: Text("Loading task...", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
