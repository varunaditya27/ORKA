package com.orka.core.testing

import com.orka.core.model.AlarmActionOption
import com.orka.core.model.AlarmActionResolver
import com.orka.core.model.AlarmRegistrar
import com.orka.core.model.BehaviorProfile
import com.orka.core.model.BehaviorProfileRepository
import com.orka.core.model.DiagnosticsRepository
import com.orka.core.model.DiagnosticsSnapshot
import com.orka.core.model.InteractionEvent
import com.orka.core.model.InteractionType
import com.orka.core.model.ModelAvailability
import com.orka.core.model.ModelInstallState
import com.orka.core.model.ModelInstaller
import com.orka.core.model.ParserContext
import com.orka.core.model.ReminderEvent
import com.orka.core.model.ReminderStatus
import com.orka.core.model.RlReadiness
import com.orka.core.model.RlRecommendation
import com.orka.core.model.RlTrainer
import com.orka.core.model.RlTrainingSummary
import com.orka.core.model.SettingsRepository
import com.orka.core.model.SchedulingContext
import com.orka.core.model.Task
import com.orka.core.model.TaskMetrics
import com.orka.core.model.RescheduleSuggestion
import com.orka.core.model.TaskParseResult
import com.orka.core.model.TaskParser
import com.orka.core.model.TaskRepository
import com.orka.core.model.TaskRescheduleAdvisor
import com.orka.core.model.TaskSplitSuggestion
import com.orka.core.model.TaskSplitter
import com.orka.core.model.TaskStatus
import com.orka.core.model.TaskDraft
import com.orka.core.model.TaskDraftValidationResult
import com.orka.core.model.TaskDraftValidator
import com.orka.core.model.UserSettings
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class FakeTaskRepository(
    tasks: List<Task> = emptyList(),
    reminders: List<ReminderEvent> = emptyList(),
    interactions: List<InteractionEvent> = emptyList(),
) : TaskRepository {
    private val tasksFlow = MutableStateFlow(tasks.associateBy(Task::id))
    private val remindersFlow = MutableStateFlow(
        reminders.groupBy(ReminderEvent::taskId).mapValues { (_, values) -> values.sortedBy(ReminderEvent::scheduledTime) },
    )
    private val interactionsFlow = MutableStateFlow(
        interactions.groupBy(InteractionEvent::taskId).mapValues { (_, values) -> values.sortedByDescending(InteractionEvent::timestamp) },
    )

    override fun observeActiveTasks(): Flow<List<Task>> = tasksFlow.map { tasksById ->
        tasksById.values
            .filterNot { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.DISMISSED }
            .sortedWith(compareByDescending<Task> { it.urgencyScore }.thenBy(Task::deadline))
    }

    override fun observeArchivedTasks(): Flow<List<Task>> = tasksFlow.map { tasksById ->
        tasksById.values
            .filter { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.DISMISSED }
            .sortedByDescending(Task::updatedAt)
    }

    override fun observeTask(taskId: String): Flow<Task?> = tasksFlow.map { tasksById -> tasksById[taskId] }

    override fun observeTaskMetrics(): Flow<TaskMetrics> = tasksFlow.map { tasksById ->
        val active = tasksById.values.filterNot { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.DISMISSED }
        val archived = tasksById.values.filter { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.DISMISSED }
        val completed = archived.count { it.status == TaskStatus.COMPLETED }
        val onTime = archived.count { task ->
            val completedAt = task.completedAt
            task.status == TaskStatus.COMPLETED && completedAt != null && !completedAt.isAfter(task.deadline)
        }
        TaskMetrics(
            completedCount = completed,
            dismissedCount = archived.count { it.status == TaskStatus.DISMISSED },
            overdueCount = active.count { it.status == TaskStatus.OVERDUE },
            onTimeRatio = if (completed == 0) 0f else onTime.toFloat() / completed.toFloat(),
        )
    }

    override fun observeReminders(taskId: String): Flow<List<ReminderEvent>> = remindersFlow.map { it[taskId].orEmpty() }

    override fun observeInteractions(taskId: String): Flow<List<InteractionEvent>> = interactionsFlow.map { it[taskId].orEmpty() }

    override fun observeNextReminder(): Flow<ReminderEvent?> = remindersFlow.map { grouped ->
        grouped.values.flatten()
            .filter { it.status == ReminderStatus.SCHEDULED }
            .minByOrNull(ReminderEvent::scheduledTime)
    }

    override fun observeLastTriggeredReminder(): Flow<ReminderEvent?> = remindersFlow.map { grouped ->
        grouped.values.flatten()
            .filter { it.status == ReminderStatus.FIRED }
            .maxByOrNull { it.actualFireTime ?: Instant.MIN }
    }

    override suspend fun getTask(taskId: String): Task? = tasksFlow.value[taskId]

    override suspend fun getReminder(reminderId: String): ReminderEvent? = remindersFlow.value.values
        .flatten()
        .firstOrNull { it.id == reminderId }

    override suspend fun upsertTask(task: Task): Task {
        tasksFlow.value = tasksFlow.value + (task.id to task)
        return task
    }

    override suspend fun replaceReminders(taskId: String, reminders: List<ReminderEvent>) {
        remindersFlow.value = remindersFlow.value.toMutableMap().apply {
            this[taskId] = reminders.sortedBy(ReminderEvent::scheduledTime)
        }
    }

    override suspend fun markReminderDelivered(reminderId: String, firedAt: Instant) {
        remindersFlow.value = remindersFlow.value.mapValues { (_, values) ->
            values.map { reminder ->
                if (reminder.id == reminderId) {
                    reminder.copy(actualFireTime = firedAt, status = ReminderStatus.FIRED)
                } else {
                    reminder
                }
            }
        }
    }

    override suspend fun recordInteraction(event: InteractionEvent) {
        val current = interactionsFlow.value[event.taskId].orEmpty()
        interactionsFlow.value = interactionsFlow.value.toMutableMap().apply {
            this[event.taskId] = (current + event).sortedByDescending(InteractionEvent::timestamp)
        }
    }

    override suspend fun updateTaskStatus(taskId: String, status: TaskStatus, completedAt: Instant?) {
        val current = tasksFlow.value[taskId] ?: return
        tasksFlow.value = tasksFlow.value + (taskId to current.copy(
            status = status,
            completedAt = completedAt,
            updatedAt = completedAt ?: current.updatedAt,
        ))
    }

    override suspend fun markOverdueTasks(now: Instant) {
        tasksFlow.value = tasksFlow.value.mapValues { (_, task) ->
            if (task.status == TaskStatus.PENDING && task.deadline.isBefore(now)) {
                task.copy(status = TaskStatus.OVERDUE, urgencyScore = 5f, updatedAt = now)
            } else {
                task
            }
        }
    }
}

class FakeBehaviorProfileRepository(
    initialProfile: BehaviorProfile = BehaviorProfile(),
) : BehaviorProfileRepository {
    private val state = MutableStateFlow(initialProfile)

    override fun observeProfile(): Flow<BehaviorProfile> = state.asStateFlow()

    override suspend fun getProfile(): BehaviorProfile = state.value

    override suspend fun seedDefaults(profile: BehaviorProfile) {
        state.value = profile
    }

    override suspend fun updateFromInteraction(task: Task, event: InteractionEvent) {
        val current = state.value
        val updatedSnoozeRates = current.categorySnoozeRates.toMutableMap()
        val updatedCompletionRates = current.categoryCompletionRates.toMutableMap()

        if (event.type.name.startsWith("SNOOZE")) {
            val currentRate = updatedSnoozeRates[task.category] ?: 0f
            updatedSnoozeRates[task.category] = (currentRate + 0.1f).coerceAtMost(1f)
        }
        if (event.type == InteractionType.MARK_DONE) {
            val currentRate = updatedCompletionRates[task.category] ?: 0f
            updatedCompletionRates[task.category] = (currentRate + 0.1f).coerceAtMost(1f)
        }

        state.value = current.copy(
            totalInteractions = current.totalInteractions + 1,
            totalCompletions = current.totalCompletions + if (event.type == InteractionType.MARK_DONE) 1 else 0,
            snoozeRate = updatedSnoozeRates.values.average().toFloat().takeIf { !it.isNaN() } ?: 0f,
            categorySnoozeRates = updatedSnoozeRates,
            categoryCompletionRates = updatedCompletionRates,
        )
    }
}

class FakeSettingsRepository(
    initialSettings: UserSettings = UserSettings(),
) : SettingsRepository {
    private val state = MutableStateFlow(initialSettings)

    override fun observeSettings(): Flow<UserSettings> = state.asStateFlow()

    override suspend fun current(): UserSettings = state.value

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        state.value = transform(state.value)
    }
}

class FakeModelInstaller(
    initialState: ModelInstallState = ModelInstallState(),
) : ModelInstaller {
    private val state = MutableStateFlow(initialState)

    override fun observeState(): Flow<ModelInstallState> = state.asStateFlow()

    override suspend fun installBundledModelIfAvailable(): ModelInstallState {
        val nextState = ModelInstallState(
            availability = ModelAvailability.READY,
            modelPath = "/data/local/tmp/gemma-4-E4B-it.litertlm",
            message = "Model ready.",
        )
        state.value = nextState
        return nextState
    }

    override suspend fun installFromCompanionKit(
        sourcePath: String,
        expectedChecksum: String?,
    ): ModelInstallState {
        val nextState = ModelInstallState(
            availability = ModelAvailability.READY,
            modelPath = sourcePath,
            checksum = expectedChecksum,
            message = "Model ready.",
        )
        state.value = nextState
        return nextState
    }

    override suspend fun reset() {
        state.value = ModelInstallState()
    }
}

class FakeDiagnosticsRepository(
    initialSnapshot: DiagnosticsSnapshot = DiagnosticsSnapshot(),
) : DiagnosticsRepository {
    private val state = MutableStateFlow(initialSnapshot)

    override fun observeSnapshot(): Flow<DiagnosticsSnapshot> = state.asStateFlow()

    override suspend fun refreshNow(): DiagnosticsSnapshot = state.value

    fun update(snapshot: DiagnosticsSnapshot) {
        state.value = snapshot
    }
}

class FakeAlarmRegistrar : AlarmRegistrar {
    val registerCalls = mutableListOf<List<ReminderEvent>>()
    val cancelCalls = mutableListOf<List<ReminderEvent>>()
    val cancelForTaskCalls = mutableListOf<String>()
    val activeReminders = mutableListOf<ReminderEvent>()

    override suspend fun register(reminders: List<ReminderEvent>) {
        registerCalls += reminders
        activeReminders.removeAll { current -> reminders.any { it.id == current.id } }
        activeReminders += reminders
    }

    override suspend fun cancel(reminders: List<ReminderEvent>) {
        cancelCalls += reminders
        activeReminders.removeAll { current -> reminders.any { it.id == current.id } }
    }

    override suspend fun cancelForTask(taskId: String) {
        cancelForTaskCalls += taskId
        activeReminders.removeAll { it.taskId == taskId }
    }

    override suspend fun refreshAll() = Unit
}

class FakeRlTrainer(
    initialReadiness: RlReadiness = RlReadiness.NotReady("Not enough interaction history"),
    var nextRecommendation: RlRecommendation? = null,
    var trainingSummary: RlTrainingSummary = RlTrainingSummary(false, 0, "Not trained"),
) : RlTrainer {
    private val state = MutableStateFlow(initialReadiness)

    override fun observeReadiness(): Flow<RlReadiness> = state.asStateFlow()

    override suspend fun maybeTrain(): RlTrainingSummary = trainingSummary

    override suspend fun recommend(task: Task, context: SchedulingContext): RlRecommendation? = nextRecommendation

    fun updateReadiness(readiness: RlReadiness) {
        state.value = readiness
    }
}

class FakeTaskParser(
    var nextResult: TaskParseResult = TestFixtures.taskParseResult(),
) : TaskParser {
    override suspend fun parse(rawInput: String, context: ParserContext): TaskParseResult = nextResult
}

class FakeTaskDraftValidator(
    var nextResult: TaskDraftValidationResult = TaskDraftValidationResult(isValid = true),
) : TaskDraftValidator {
    override fun validate(draft: TaskDraft): TaskDraftValidationResult = nextResult
}

class FakeAlarmActionResolver(
    private val actions: List<AlarmActionOption>,
) : AlarmActionResolver {
    override fun resolve(task: Task, history: List<InteractionEvent>, now: Instant): List<AlarmActionOption> = actions
}

class FakeTaskSplitter(
    var nextSuggestion: TaskSplitSuggestion? = null,
) : TaskSplitter {
    override suspend fun suggestSplit(task: Task): TaskSplitSuggestion? = nextSuggestion
}

class FakeTaskRescheduleAdvisor(
    var nextSuggestion: RescheduleSuggestion? = null,
) : TaskRescheduleAdvisor {
    override suspend fun suggestReschedule(task: Task, profile: BehaviorProfile): RescheduleSuggestion? = nextSuggestion
}
