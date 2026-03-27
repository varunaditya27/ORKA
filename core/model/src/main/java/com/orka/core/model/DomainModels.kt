package com.orka.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

enum class TaskCategory {
    ACADEMIC,
    PERSONAL,
    PROFESSIONAL,
    CLUB,
    HEALTH,
    FINANCIAL,
    OTHER,
}

enum class TaskStatus {
    PENDING,
    ACTIVE,
    SNOOZED,
    COMPLETED,
    OVERDUE,
    DISMISSED,
}

enum class ReminderStatus {
    SCHEDULED,
    FIRED,
    CANCELLED,
    MISSED,
}

enum class InteractionType {
    START_TASK,
    SNOOZE_SHORT,
    SNOOZE_LONG,
    SNOOZE_CUSTOM,
    MARK_DONE,
    RESCHEDULE,
    SPLIT_TASK,
    ACKNOWLEDGE,
    IGNORE,
}

enum class SchedulerMode {
    RULE_BASED,
    ADAPTIVE,
    RL,
}

enum class AlarmCapabilityState {
    READY,
    EXACT_ALARM_DENIED,
    BATTERY_OPTIMIZATION_ENABLED,
    OEM_ACTION_REQUIRED,
    NOTIFICATION_BLOCKED,
}

enum class ParseMode {
    GEMMA,
    FALLBACK,
    ERROR,
}

enum class ActionEmphasis {
    PRIMARY,
    SECONDARY,
    TERTIARY,
}

data class Task(
    val id: String = UUID.randomUUID().toString(),
    val rawInput: String,
    val title: String,
    val description: String? = null,
    val deadline: Instant,
    val deadlineConfidence: Float,
    val category: TaskCategory,
    val estimatedEffortMinutes: Int,
    val urgencyScore: Float,
    val status: TaskStatus = TaskStatus.PENDING,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val userCorrectedFields: Set<String> = emptySet(),
)

data class TaskDraft(
    val rawInput: String,
    val title: String = "",
    val description: String? = null,
    val deadline: Instant? = null,
    val deadlineConfidence: Float = 0f,
    val category: TaskCategory = TaskCategory.OTHER,
    val estimatedEffortMinutes: Int = 30,
    val urgencyScore: Float = 1f,
    val lowConfidenceFields: Set<String> = emptySet(),
    val parseMode: ParseMode = ParseMode.FALLBACK,
)

data class TaskParseResult(
    val draft: TaskDraft,
    val issues: List<String> = emptyList(),
)

data class TaskDraftValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
)

data class ReminderEvent(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val scheduledTime: Instant,
    val actualFireTime: Instant? = null,
    val sequenceNumber: Int,
    val alarmManagerId: Int,
    val status: ReminderStatus = ReminderStatus.SCHEDULED,
    val schedulerMode: SchedulerMode = SchedulerMode.RULE_BASED,
)

data class InteractionEvent(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val reminderId: String? = null,
    val type: InteractionType,
    val timestamp: Instant = Instant.now(),
    val responseDelaySeconds: Long = 0,
    val escalationApplied: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
)

data class BehaviorProfile(
    val productiveStartHour: Int = 9,
    val productiveEndHour: Int = 21,
    val averageResponseMinutes: Int = 30,
    val snoozeRate: Float = 0f,
    val totalInteractions: Int = 0,
    val totalCompletions: Int = 0,
    val categorySnoozeRates: Map<TaskCategory, Float> = emptyMap(),
    val categoryCompletionRates: Map<TaskCategory, Float> = emptyMap(),
)

data class ParserContext(
    val now: ZonedDateTime = ZonedDateTime.now(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val domainHints: Map<String, String> = emptyMap(),
)

data class SchedulingContext(
    val profile: BehaviorProfile,
    val interactionHistory: List<InteractionEvent>,
    val now: Instant = Instant.now(),
)

data class AlarmActionOption(
    val type: InteractionType,
    val label: String,
    val emphasis: ActionEmphasis,
    val requiresInput: Boolean = false,
)

data class TaskMetrics(
    val completedCount: Int = 0,
    val dismissedCount: Int = 0,
    val overdueCount: Int = 0,
    val onTimeRatio: Float = 0f,
)

data class DiagnosticsSnapshot(
    val capabilityState: AlarmCapabilityState = AlarmCapabilityState.READY,
    val nextReminder: ReminderEvent? = null,
    val lastFiredReminder: ReminderEvent? = null,
    val modelInstallState: ModelInstallState = ModelInstallState(),
    val activeSchedulerMode: SchedulerMode = SchedulerMode.RULE_BASED,
    val rlReadiness: RlReadiness = RlReadiness.NotReady("Not enough interaction history"),
    val warnings: List<String> = emptyList(),
)

data class UserSettings(
    val darkModeOverride: Boolean? = null,
    val onboardingCompleted: Boolean = false,
    val diagnosticsEnabled: Boolean = true,
    val adaptiveSchedulingEnabled: Boolean = true,
    val rlSchedulingEnabled: Boolean = true,
    val importedModelPath: String? = null,
)

enum class ModelAvailability {
    NOT_INSTALLED,
    IMPORTING,
    READY,
    FAILED,
}

data class ModelInstallState(
    val availability: ModelAvailability = ModelAvailability.NOT_INSTALLED,
    val modelPath: String? = null,
    val checksum: String? = null,
    val sizeBytes: Long = 0,
    val message: String? = null,
)

sealed interface RlReadiness {
    data object Ready : RlReadiness
    data class NotReady(val reason: String) : RlReadiness
}

data class RlRecommendation(
    val nextReminderDelayHours: Int,
    val confidence: Float,
)

data class RlTrainingSummary(
    val trained: Boolean,
    val episodesUsed: Int,
    val message: String,
)

interface TaskParser {
    suspend fun parse(rawInput: String, context: ParserContext): TaskParseResult
}

interface TaskDraftValidator {
    fun validate(draft: TaskDraft): TaskDraftValidationResult
}

interface TaskRepository {
    fun observeActiveTasks(): Flow<List<Task>>
    fun observeArchivedTasks(): Flow<List<Task>>
    fun observeTask(taskId: String): Flow<Task?>
    fun observeTaskMetrics(): Flow<TaskMetrics>
    fun observeReminders(taskId: String): Flow<List<ReminderEvent>>
    fun observeInteractions(taskId: String): Flow<List<InteractionEvent>>
    fun observeNextReminder(): Flow<ReminderEvent?>
    fun observeLastTriggeredReminder(): Flow<ReminderEvent?>
    suspend fun getTask(taskId: String): Task?
    suspend fun getReminder(reminderId: String): ReminderEvent?
    suspend fun upsertTask(task: Task): Task
    suspend fun replaceReminders(taskId: String, reminders: List<ReminderEvent>)
    suspend fun markReminderDelivered(reminderId: String, firedAt: Instant)
    suspend fun recordInteraction(event: InteractionEvent)
    suspend fun updateTaskStatus(taskId: String, status: TaskStatus, completedAt: Instant? = null)
}

interface SchedulerPolicy {
    val mode: SchedulerMode
    suspend fun schedule(task: Task, context: SchedulingContext): List<ReminderEvent>
}

interface AlarmActionResolver {
    fun resolve(task: Task, history: List<InteractionEvent>, now: Instant = Instant.now()): List<AlarmActionOption>
}

interface AlarmRegistrar {
    suspend fun register(reminders: List<ReminderEvent>)
    suspend fun cancel(reminders: List<ReminderEvent>)
    suspend fun cancelForTask(taskId: String)
    suspend fun refreshAll()
}

interface BehaviorProfileRepository {
    fun observeProfile(): Flow<BehaviorProfile>
    suspend fun getProfile(): BehaviorProfile
    suspend fun seedDefaults(profile: BehaviorProfile)
    suspend fun updateFromInteraction(task: Task, event: InteractionEvent)
}

interface RlTrainer {
    fun observeReadiness(): Flow<RlReadiness>
    suspend fun maybeTrain(): RlTrainingSummary
    suspend fun recommend(task: Task, context: SchedulingContext): RlRecommendation?
}

interface ModelInstaller {
    fun observeState(): Flow<ModelInstallState>
    suspend fun installFromCompanionKit(sourcePath: String, expectedChecksum: String? = null): ModelInstallState
    suspend fun reset()
}

interface DiagnosticsRepository {
    fun observeSnapshot(): Flow<DiagnosticsSnapshot>
    suspend fun refreshNow(): DiagnosticsSnapshot
}

interface SettingsRepository {
    fun observeSettings(): Flow<UserSettings>
    suspend fun current(): UserSettings
    suspend fun update(transform: (UserSettings) -> UserSettings)
}

@Serializable
data object SplashRoute

@Serializable
data object OnboardingRoute

@Serializable
data object CaptureRoute

@Serializable
data object TasksRoute

@Serializable
data class TaskDetailRoute(val taskId: String)

@Serializable
data object ArchiveRoute

@Serializable
data object SettingsRoute

@Serializable
data object DiagnosticsRoute
