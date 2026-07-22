package com.orka.feature.tasks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orka.core.designsystem.OrkaScreenContainer
import com.orka.core.designsystem.OrkaScreenHeader
import com.orka.core.designsystem.OrkaSurface
import com.orka.core.designsystem.OrkaTaskCard
import com.orka.core.model.Task
import com.orka.core.model.TaskMetrics
import com.orka.core.model.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class TasksUiState(
    val tasks: List<Task> = emptyList(),
    val metrics: TaskMetrics = TaskMetrics(),
)

@HiltViewModel
class TasksViewModel @Inject constructor(
    taskRepository: TaskRepository,
) : ViewModel() {
    val uiState = combine(
        taskRepository.observeActiveTasks(),
        taskRepository.observeTaskMetrics(),
    ) { tasks, metrics ->
        TasksUiState(tasks = tasks, metrics = metrics)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())
}

@Composable
fun TasksRoute(
    onTaskClick: (String) -> Unit,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    OrkaSurface {
        OrkaScreenContainer {
            OrkaScreenHeader("Tasks")
            Text(
                "${state.metrics.completedCount} completed | ${(state.metrics.onTimeRatio * 100).toInt()}% on time",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.tasks.isEmpty()) {
                Text("Nothing pending.", style = MaterialTheme.typography.bodyLarge)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(state.tasks, key = { it.id }) { task ->
                        OrkaTaskCard(task = task, onClick = { onTaskClick(task.id) })
                    }
                }
            }
        }
    }
}
