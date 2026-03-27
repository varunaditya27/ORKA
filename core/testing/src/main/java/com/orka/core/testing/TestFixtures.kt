package com.orka.core.testing

import com.orka.core.model.ActionEmphasis
import com.orka.core.model.AlarmActionOption
import com.orka.core.model.AlarmCapabilityState
import com.orka.core.model.BehaviorProfile
import com.orka.core.model.DiagnosticsSnapshot
import com.orka.core.model.InteractionEvent
import com.orka.core.model.InteractionType
import com.orka.core.model.ModelAvailability
import com.orka.core.model.ModelInstallState
import com.orka.core.model.ParseMode
import com.orka.core.model.ReminderEvent
import com.orka.core.model.ReminderStatus
import com.orka.core.model.RlReadiness
import com.orka.core.model.SchedulerMode
import com.orka.core.model.Task
import com.orka.core.model.TaskCategory
import com.orka.core.model.TaskDraft
import com.orka.core.model.TaskParseResult
import com.orka.core.model.TaskStatus
import com.orka.core.model.UserSettings
import java.time.Duration
import java.time.Instant

object TestFixtures {
    val now: Instant = Instant.parse("2026-03-30T09:00:00Z")
    val defaultDeadline: Instant = now.plus(Duration.ofDays(2))

    fun task(
        id: String = "task-1",
        rawInput: String = "Prepare presentation by tomorrow evening",
        title: String = "Prepare presentation",
        description: String? = rawInput,
        deadline: Instant = defaultDeadline,
        deadlineConfidence: Float = 0.9f,
        category: TaskCategory = TaskCategory.PROFESSIONAL,
        estimatedEffortMinutes: Int = 90,
        urgencyScore: Float = 4f,
        status: TaskStatus = TaskStatus.PENDING,
        createdAt: Instant = now.minus(Duration.ofHours(1)),
        updatedAt: Instant = now.minus(Duration.ofMinutes(30)),
        completedAt: Instant? = null,
        userCorrectedFields: Set<String> = emptySet(),
    ): Task = Task(
        id = id,
        rawInput = rawInput,
        title = title,
        description = description,
        deadline = deadline,
        deadlineConfidence = deadlineConfidence,
        category = category,
        estimatedEffortMinutes = estimatedEffortMinutes,
        urgencyScore = urgencyScore,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        userCorrectedFields = userCorrectedFields,
    )

    fun taskDraft(
        rawInput: String = "Prepare presentation by tomorrow evening",
        title: String = "Prepare presentation",
        deadline: Instant? = defaultDeadline,
        deadlineConfidence: Float = 0.9f,
        category: TaskCategory = TaskCategory.PROFESSIONAL,
        estimatedEffortMinutes: Int = 90,
        urgencyScore: Float = 4f,
        lowConfidenceFields: Set<String> = emptySet(),
        parseMode: ParseMode = ParseMode.FALLBACK,
    ): TaskDraft = TaskDraft(
        rawInput = rawInput,
        title = title,
        description = rawInput,
        deadline = deadline,
        deadlineConfidence = deadlineConfidence,
        category = category,
        estimatedEffortMinutes = estimatedEffortMinutes,
        urgencyScore = urgencyScore,
        lowConfidenceFields = lowConfidenceFields,
        parseMode = parseMode,
    )

    fun taskParseResult(
        draft: TaskDraft = taskDraft(),
        issues: List<String> = emptyList(),
    ): TaskParseResult = TaskParseResult(draft = draft, issues = issues)

    fun reminder(
        id: String = "reminder-1",
        taskId: String = "task-1",
        scheduledTime: Instant = now.plus(Duration.ofHours(4)),
        actualFireTime: Instant? = null,
        sequenceNumber: Int = 1,
        alarmManagerId: Int = 101,
        status: ReminderStatus = ReminderStatus.SCHEDULED,
        schedulerMode: SchedulerMode = SchedulerMode.RULE_BASED,
    ): ReminderEvent = ReminderEvent(
        id = id,
        taskId = taskId,
        scheduledTime = scheduledTime,
        actualFireTime = actualFireTime,
        sequenceNumber = sequenceNumber,
        alarmManagerId = alarmManagerId,
        status = status,
        schedulerMode = schedulerMode,
    )

    fun interaction(
        id: String = "interaction-1",
        taskId: String = "task-1",
        reminderId: String? = null,
        type: InteractionType = InteractionType.ACKNOWLEDGE,
        timestamp: Instant = now,
        responseDelaySeconds: Long = 0,
        escalationApplied: Boolean = false,
        metadata: Map<String, String> = emptyMap(),
    ): InteractionEvent = InteractionEvent(
        id = id,
        taskId = taskId,
        reminderId = reminderId,
        type = type,
        timestamp = timestamp,
        responseDelaySeconds = responseDelaySeconds,
        escalationApplied = escalationApplied,
        metadata = metadata,
    )

    fun behaviorProfile(
        productiveStartHour: Int = 9,
        productiveEndHour: Int = 18,
        averageResponseMinutes: Int = 30,
        snoozeRate: Float = 0.2f,
        totalInteractions: Int = 24,
        totalCompletions: Int = 8,
        categorySnoozeRates: Map<TaskCategory, Float> = emptyMap(),
        categoryCompletionRates: Map<TaskCategory, Float> = emptyMap(),
    ): BehaviorProfile = BehaviorProfile(
        productiveStartHour = productiveStartHour,
        productiveEndHour = productiveEndHour,
        averageResponseMinutes = averageResponseMinutes,
        snoozeRate = snoozeRate,
        totalInteractions = totalInteractions,
        totalCompletions = totalCompletions,
        categorySnoozeRates = categorySnoozeRates,
        categoryCompletionRates = categoryCompletionRates,
    )

    fun modelInstallState(
        availability: ModelAvailability = ModelAvailability.NOT_INSTALLED,
        modelPath: String? = null,
        checksum: String? = null,
        sizeBytes: Long = 0,
        message: String? = null,
    ): ModelInstallState = ModelInstallState(
        availability = availability,
        modelPath = modelPath,
        checksum = checksum,
        sizeBytes = sizeBytes,
        message = message,
    )

    fun diagnosticsSnapshot(
        capabilityState: AlarmCapabilityState = AlarmCapabilityState.READY,
        nextReminder: ReminderEvent? = null,
        lastFiredReminder: ReminderEvent? = null,
        modelInstallState: ModelInstallState = modelInstallState(),
        activeSchedulerMode: SchedulerMode = SchedulerMode.RULE_BASED,
        rlReadiness: RlReadiness = RlReadiness.NotReady("Not enough interaction history"),
        warnings: List<String> = emptyList(),
    ): DiagnosticsSnapshot = DiagnosticsSnapshot(
        capabilityState = capabilityState,
        nextReminder = nextReminder,
        lastFiredReminder = lastFiredReminder,
        modelInstallState = modelInstallState,
        activeSchedulerMode = activeSchedulerMode,
        rlReadiness = rlReadiness,
        warnings = warnings,
    )

    fun userSettings(
        darkModeOverride: Boolean? = null,
        onboardingCompleted: Boolean = false,
        diagnosticsEnabled: Boolean = true,
        adaptiveSchedulingEnabled: Boolean = true,
        rlSchedulingEnabled: Boolean = true,
        importedModelPath: String? = null,
    ): UserSettings = UserSettings(
        darkModeOverride = darkModeOverride,
        onboardingCompleted = onboardingCompleted,
        diagnosticsEnabled = diagnosticsEnabled,
        adaptiveSchedulingEnabled = adaptiveSchedulingEnabled,
        rlSchedulingEnabled = rlSchedulingEnabled,
        importedModelPath = importedModelPath,
    )

    fun alarmAction(
        type: InteractionType = InteractionType.START_TASK,
        label: String = "Start Now",
        emphasis: ActionEmphasis = ActionEmphasis.PRIMARY,
        requiresInput: Boolean = false,
    ): AlarmActionOption = AlarmActionOption(
        type = type,
        label = label,
        emphasis = emphasis,
        requiresInput = requiresInput,
    )
}
