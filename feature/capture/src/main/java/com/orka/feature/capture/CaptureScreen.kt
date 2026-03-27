package com.orka.feature.capture

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orka.core.designsystem.OrkaActionButton
import com.orka.core.designsystem.OrkaEyebrow
import com.orka.core.designsystem.OrkaScreenContainer
import com.orka.core.designsystem.OrkaSurface
import com.orka.core.designsystem.OrkaWordmark
import com.orka.core.model.BehaviorProfileRepository
import com.orka.core.model.Task
import com.orka.core.model.TaskDraft
import com.orka.core.model.TaskDraftValidator
import com.orka.core.model.TaskParser
import com.orka.core.model.TaskRepository
import com.orka.data.scheduler.SchedulerOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CaptureUiState(
    val input: String = "",
    val isAnalyzing: Boolean = false,
    val draft: TaskDraft? = null,
    val errorMessage: String? = null,
    val createdTaskId: String? = null,
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val parser: TaskParser,
    private val validator: TaskDraftValidator,
    private val taskRepository: TaskRepository,
    private val behaviorProfileRepository: BehaviorProfileRepository,
    private val schedulerOrchestrator: SchedulerOrchestrator,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState = _uiState.asStateFlow()

    fun updateInput(input: String) {
        _uiState.value = _uiState.value.copy(input = input, createdTaskId = null, errorMessage = null)
    }

    fun analyse() {
        val input = _uiState.value.input.trim()
        if (input.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Describe the task first.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAnalyzing = true, errorMessage = null)
            val result = parser.parse(input, com.orka.core.model.ParserContext())
            _uiState.value = _uiState.value.copy(isAnalyzing = false, draft = result.draft, errorMessage = result.issues.firstOrNull())
        }
    }

    fun dismissDraft() {
        _uiState.value = _uiState.value.copy(draft = null, errorMessage = null)
    }

    fun confirmDraft() {
        val draft = _uiState.value.draft ?: return
        val validation = validator.validate(draft)
        val deadline = draft.deadline
        if (!validation.isValid || deadline == null) {
            _uiState.value = _uiState.value.copy(errorMessage = validation.errors.firstOrNull() ?: "Review the task details.")
            return
        }
        viewModelScope.launch {
            val task = Task(
                rawInput = draft.rawInput,
                title = draft.title,
                description = draft.description,
                deadline = deadline,
                deadlineConfidence = draft.deadlineConfidence,
                category = draft.category,
                estimatedEffortMinutes = draft.estimatedEffortMinutes,
                urgencyScore = draft.urgencyScore,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                userCorrectedFields = draft.lowConfidenceFields,
            )
            val savedTask = taskRepository.upsertTask(task)
            val profile = behaviorProfileRepository.getProfile()
            val (mode, reminders) = schedulerOrchestrator.schedule(
                task = savedTask,
                context = com.orka.core.model.SchedulingContext(
                    profile = profile,
                    interactionHistory = emptyList(),
                ),
            )
            schedulerOrchestrator.persistSchedule(savedTask.id, reminders)
            _uiState.value = CaptureUiState(
                createdTaskId = savedTask.id,
                errorMessage = if (mode == com.orka.core.model.SchedulerMode.RULE_BASED) null else "Schedule tuned using ${mode.name.lowercase()} mode.",
            )
        }
    }
}

@Composable
fun CaptureRoute(
    modifier: Modifier = Modifier,
    onTaskCreated: (String) -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.createdTaskId) {
        state.createdTaskId?.let(onTaskCreated)
    }

    OrkaSurface {
        OrkaScreenContainer(modifier = modifier.padding(top = 32.dp)) {
            OrkaEyebrow("Capture")
            OrkaWordmark(modifier = Modifier.fillMaxWidth())
            Text(
                "Describe the task the way you naturally think about it. ORKA will extract the schedule and ask for confirmation before anything is locked in.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.input,
                onValueChange = viewModel::updateInput,
                label = { Text("What needs to be done?") },
                minLines = 3,
            )
            OrkaActionButton(
                text = if (state.isAnalyzing) "Analysing..." else "Analyse",
                emphasis = com.orka.core.model.ActionEmphasis.PRIMARY,
                onClick = viewModel::analyse,
            )

            state.errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            }

            state.draft?.let { draft ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    OrkaScreenContainer(
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text("Confirm task", style = MaterialTheme.typography.headlineMedium)
                        Text("Title: ${draft.title}", style = MaterialTheme.typography.bodyLarge)
                        Text("Category: ${draft.category.name}", style = MaterialTheme.typography.bodyLarge)
                        Text("Effort: ${draft.estimatedEffortMinutes} min", style = MaterialTheme.typography.bodyLarge)
                        Text("Deadline confidence: ${(draft.deadlineConfidence * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Parse mode: ${draft.parseMode.name}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (draft.parseMode == com.orka.core.model.ParseMode.FALLBACK) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        OrkaActionButton(
                            text = "Confirm",
                            emphasis = com.orka.core.model.ActionEmphasis.PRIMARY,
                            onClick = viewModel::confirmDraft,
                        )
                        OrkaActionButton(
                            text = "Re-enter",
                            emphasis = com.orka.core.model.ActionEmphasis.TERTIARY,
                            onClick = viewModel::dismissDraft,
                        )
                    }
                }
            }
        }
    }
}
