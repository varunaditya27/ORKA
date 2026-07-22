package com.orka.feature.archive

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

data class ArchiveUiState(
    val tasks: List<Task> = emptyList(),
    val metrics: TaskMetrics = TaskMetrics(),
)

@HiltViewModel
class ArchiveViewModel @Inject constructor(
    taskRepository: TaskRepository,
) : ViewModel() {
    val uiState = combine(
        taskRepository.observeArchivedTasks(),
        taskRepository.observeTaskMetrics(),
    ) { tasks, metrics ->
        ArchiveUiState(tasks, metrics)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchiveUiState())
}

@Composable
fun ArchiveRoute(
    viewModel: ArchiveViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    OrkaSurface {
        OrkaScreenContainer {
            OrkaScreenHeader("Archive")
            Text(
                "${state.metrics.completedCount} tasks completed | ${(state.metrics.onTimeRatio * 100).toInt()}% on time",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.tasks.isEmpty()) {
                Text("No archived tasks yet.", style = MaterialTheme.typography.bodyLarge)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(state.tasks, key = { it.id }) { task ->
                        OrkaTaskCard(task = task)
                    }
                }
            }
        }
    }
}
