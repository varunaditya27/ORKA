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
            val updatedTask = task.copy(
                deadline = task.deadline.plus(Duration.ofDays(1)),
                updatedAt = Instant.now(),
                status = TaskStatus.PENDING,
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
                        Text(task.category.name, style = MaterialTheme.typography.bodyLarge)
                        Text("Due ${task.deadline}", style = MaterialTheme.typography.titleMedium)
                        Text("Effort ${task.estimatedEffortMinutes} min", style = MaterialTheme.typography.titleMedium)
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
                        Text("Upcoming reminders", style = MaterialTheme.typography.headlineMedium)
                    }
                    items(state.reminders, key = { it.id }) { reminder ->
                        Text("${reminder.schedulerMode.name} | ${reminder.scheduledTime}", style = MaterialTheme.typography.bodyLarge)
                    }
                    item {
                        Text("Activity", style = MaterialTheme.typography.headlineMedium)
                    }
                    items(state.interactions, key = { it.id }) { event ->
                        Text("${event.type.name} | ${event.timestamp}", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } ?: Text("Loading task...", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
