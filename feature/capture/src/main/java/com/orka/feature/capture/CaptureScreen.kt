package com.orka.feature.capture

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orka.core.common.TimeFormatter
import com.orka.core.common.displayName
import com.orka.core.designsystem.OrkaActionButton
import com.orka.core.designsystem.OrkaAmber
import com.orka.core.designsystem.OrkaEyebrow
import com.orka.core.designsystem.OrkaScreenContainer
import com.orka.core.designsystem.OrkaSurface
import com.orka.core.model.ActionEmphasis
import com.orka.core.model.BehaviorProfileRepository
import com.orka.core.model.ClarificationReason
import com.orka.core.model.PrimitiveType
import com.orka.core.model.ReminderEvent
import com.orka.core.model.SchedulerMode
import com.orka.core.model.SchedulingContext
import com.orka.core.model.Task
import com.orka.core.model.TaskClarificationAdvisor
import com.orka.core.model.TaskDraft
import com.orka.core.model.TaskDraftValidator
import com.orka.core.model.TaskParser
import com.orka.core.model.TaskRepository
import com.orka.data.scheduler.SchedulerOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val IST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
private val PREVIEW_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a", Locale.ENGLISH)
private val DAY_TOKEN_REGEX =
    Regex("""\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""", RegexOption.IGNORE_CASE)
private val TIME_TOKEN_REGEX = Regex("""\b(\d{1,2})(?::(\d{2}))?\b""")

data class CaptureUiState(
    val input: String = "",
    val isAnalyzing: Boolean = false,
    val isConfirming: Boolean = false,
    val draft: TaskDraft? = null,
    val linkedDrafts: List<TaskDraft> = emptyList(),
    val reminderPreviews: List<List<ReminderEvent>> = emptyList(),
    val dismissedReminderKeys: Set<String> = emptySet(),
    val selectedDraftIndex: Int = 0,
    val warningMessage: String? = null,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val createdTaskId: String? = null,
    val smartClarificationInstant: Instant? = null,
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val parser: TaskParser,
    private val validator: TaskDraftValidator,
    private val taskRepository: TaskRepository,
    private val behaviorProfileRepository: BehaviorProfileRepository,
    private val schedulerOrchestrator: SchedulerOrchestrator,
    private val taskClarificationAdvisor: TaskClarificationAdvisor,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState = _uiState.asStateFlow()

    fun updateInput(input: String) {
        _uiState.value = _uiState.value.copy(
            input = input,
            draft = null,
            linkedDrafts = emptyList(),
            reminderPreviews = emptyList(),
            dismissedReminderKeys = emptySet(),
            selectedDraftIndex = 0,
            warningMessage = null,
            errorMessage = null,
            infoMessage = null,
            createdTaskId = null,
            smartClarificationInstant = null,
        )
    }

    fun analyse() {
        val input = _uiState.value.input.trim()
        if (input.isBlank()) {
            _uiState.value = _uiState.value.copy(
                warningMessage = null,
                infoMessage = null,
                errorMessage = "Describe the task first.",
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isAnalyzing = true,
                warningMessage = null,
                errorMessage = null,
                infoMessage = null,
            )
            val result = parser.parse(input, com.orka.core.model.ParserContext())
            val previews = buildReminderPreviews(result.linkedDrafts)
            _uiState.value = _uiState.value.copy(
                isAnalyzing = false,
                draft = result.draft,
                linkedDrafts = result.linkedDrafts,
                reminderPreviews = previews,
                dismissedReminderKeys = emptySet(),
                selectedDraftIndex = 0,
                warningMessage = result.issues.firstOrNull(),
                errorMessage = null,
                infoMessage = null,
                smartClarificationInstant = null,
            )

            // Never block showing the clarification sheet on this — the static quick-picks
            // (Today 6pm / Tomorrow 9am / this week end) are always available immediately;
            // this just adds a task-specific option alongside them if/when it's ready.
            val draft = result.draft
            val reason = draft.clarificationReason
            if (draft.clarificationNeeded &&
                (reason == ClarificationReason.NO_DEADLINE_DETECTED || reason == ClarificationReason.VAGUE_TEMPORAL_EXPRESSION)
            ) {
                launch {
                    val suggestion = runCatching { taskClarificationAdvisor.suggestDeadline(draft) }.getOrNull()
                        ?: return@launch
                    val current = _uiState.value
                    // Only apply if the user is still looking at this same clarification —
                    // they may have already typed something new or resolved it another way.
                    if (current.draft?.rawInput == draft.rawInput && current.draft?.clarificationNeeded == true) {
                        _uiState.value = current.copy(smartClarificationInstant = suggestion)
                    }
                }
            }
        }
    }

    fun dismissDraft() {
        _uiState.value = _uiState.value.copy(
            draft = null,
            linkedDrafts = emptyList(),
            reminderPreviews = emptyList(),
            dismissedReminderKeys = emptySet(),
            selectedDraftIndex = 0,
            warningMessage = null,
            errorMessage = null,
            infoMessage = null,
            smartClarificationInstant = null,
        )
    }

    fun selectDraft(index: Int) {
        val current = _uiState.value
        val drafts = current.linkedDrafts.ifEmpty { listOfNotNull(current.draft) }
        if (drafts.isEmpty()) return
        _uiState.value = current.copy(selectedDraftIndex = index.coerceIn(0, drafts.lastIndex))
    }

    fun toggleReminderSuppression(draftIndex: Int, reminder: ReminderEvent) {
        val current = _uiState.value
        val key = reminderPreviewKey(draftIndex, reminder)
        val updatedKeys = current.dismissedReminderKeys.toMutableSet()
        if (!updatedKeys.add(key)) updatedKeys.remove(key)
        _uiState.value = current.copy(dismissedReminderKeys = updatedKeys)
    }

    fun applyClarification(resolvedInstant: Instant) {
        viewModelScope.launch {
            val current = _uiState.value
            val drafts = current.linkedDrafts.ifEmpty { listOfNotNull(current.draft) }
            if (drafts.isEmpty()) return@launch

            if (resolvedInstant.isBefore(Instant.now().minusSeconds(30))) {
                _uiState.value = current.copy(errorMessage = "Pick a future date/time to continue.")
                return@launch
            }

            val updated = drafts.map { draft ->
                val shouldAnchorEvent = draft.primitiveType != PrimitiveType.TASK
                draft.copy(
                    deadline = resolvedInstant,
                    deadlineConfidence = maxOf(draft.deadlineConfidence, 0.95f),
                    eventStartTime = if (shouldAnchorEvent) resolvedInstant else null,
                    clarificationNeeded = false,
                    clarificationReason = null,
                    lowConfidenceFields = draft.lowConfidenceFields - "deadline",
                )
            }

            val previews = buildReminderPreviews(updated)
            _uiState.value = current.copy(
                draft = updated.first(),
                linkedDrafts = updated,
                reminderPreviews = previews,
                dismissedReminderKeys = emptySet(),
                warningMessage = null,
                errorMessage = null,
                selectedDraftIndex = current.selectedDraftIndex.coerceIn(0, updated.lastIndex),
                smartClarificationInstant = null,
            )
        }
    }

    fun confirmDraft() {
        val currentState = _uiState.value
        // Without this, a rapid double-tap would re-enter this function while the first
        // confirmDraft() coroutine is still mid-flight and create a second, duplicate task
        // (each call builds a fresh Task with a new random id) with its own duplicate reminders.
        if (currentState.isConfirming) return
        val drafts = currentState.linkedDrafts.ifEmpty { listOfNotNull(currentState.draft) }
        if (drafts.isEmpty()) return

        val firstInvalid = drafts
            .map { it to validator.validate(it) }
            .firstOrNull { (_, validation) -> !validation.isValid }

        if (firstInvalid != null) {
            val (_, validation) = firstInvalid
            _uiState.value = _uiState.value.copy(
                warningMessage = null,
                infoMessage = null,
                errorMessage = validation.errors.firstOrNull() ?: "Review the task details.",
            )
            return
        }

        _uiState.value = _uiState.value.copy(isConfirming = true)

        viewModelScope.launch {
            val profile = behaviorProfileRepository.getProfile()
            val createdTasks = mutableListOf<Task>()
            var schedulerMode = SchedulerMode.RULE_BASED
            var removedReminderCount = 0

            drafts.forEachIndexed { index, draft ->
                val deadline = draft.deadline ?: return@forEachIndexed
                val task = Task(
                    rawInput = draft.rawInput,
                    title = draft.title,
                    description = draft.description,
                    deadline = deadline,
                    deadlineConfidence = draft.deadlineConfidence,
                    primitiveType = draft.primitiveType,
                    eventStartTime = draft.eventStartTime,
                    eventDurationMinutes = draft.eventDurationMinutes,
                    preEventProfile = draft.preEventProfile,
                    linkedEntityId = draft.linkedEntityId,
                    clarificationNeeded = draft.clarificationNeeded,
                    clarificationReason = draft.clarificationReason,
                    resolvedTimezone = draft.resolvedTimezone,
                    temporalExpressionRaw = draft.temporalExpressionRaw,
                    category = draft.category,
                    estimatedEffortMinutes = draft.estimatedEffortMinutes,
                    urgencyScore = draft.urgencyScore,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                    userCorrectedFields = draft.lowConfidenceFields,
                )
                val savedTask = taskRepository.upsertTask(task)
                createdTasks += savedTask

                val (mode, reminders) = schedulerOrchestrator.schedule(
                    task = savedTask,
                    context = SchedulingContext(
                        profile = profile,
                        interactionHistory = emptyList(),
                    ),
                )
                schedulerMode = mode
                val filteredReminders = reminders.filterNot {
                    reminderPreviewKey(index, it) in currentState.dismissedReminderKeys
                }
                removedReminderCount += reminders.size - filteredReminders.size
                schedulerOrchestrator.persistSchedule(savedTask.id, filteredReminders)
            }

            _uiState.value = CaptureUiState(
                createdTaskId = createdTasks.firstOrNull()?.id,
                infoMessage = when {
                    removedReminderCount > 0 -> "Saved task and skipped $removedReminderCount reminder${if (removedReminderCount == 1) "" else "s"}."
                    createdTasks.size > 1 -> "Created linked event and prep task."
                    schedulerMode == SchedulerMode.RULE_BASED -> null
                    else -> "Schedule tuned using ${schedulerMode.name.lowercase()} mode."
                },
            )
        }
    }

    private suspend fun buildReminderPreviews(drafts: List<TaskDraft>): List<List<ReminderEvent>> {
        val profile = behaviorProfileRepository.getProfile()
        val now = Instant.now()
        return drafts.map { draft ->
            val previewTask = draft.toTask(now) ?: return@map emptyList()
            runCatching {
                schedulerOrchestrator.schedule(
                    task = previewTask,
                    context = SchedulingContext(
                        profile = profile,
                        interactionHistory = emptyList(),
                        now = now,
                    ),
                ).second
            }.getOrElse { emptyList() }
        }
    }
}

private fun TaskDraft.toTask(now: Instant): Task? {
    val resolvedDeadline = deadline ?: return null
    return Task(
        rawInput = rawInput,
        title = title.ifBlank { "Untitled task" },
        description = description,
        deadline = resolvedDeadline,
        deadlineConfidence = deadlineConfidence,
        primitiveType = primitiveType,
        eventStartTime = eventStartTime,
        eventDurationMinutes = eventDurationMinutes,
        preEventProfile = preEventProfile,
        linkedEntityId = linkedEntityId,
        clarificationNeeded = clarificationNeeded,
        clarificationReason = clarificationReason,
        resolvedTimezone = resolvedTimezone,
        temporalExpressionRaw = temporalExpressionRaw,
        category = category,
        estimatedEffortMinutes = estimatedEffortMinutes,
        urgencyScore = urgencyScore,
        createdAt = now,
        updatedAt = now,
        userCorrectedFields = lowConfidenceFields,
    )
}

private fun reminderPreviewKey(draftIndex: Int, reminder: ReminderEvent): String {
    val minuteBucket = reminder.scheduledTime.epochSecond / 60
    return "$draftIndex|${reminder.minutesBeforeAnchor ?: Long.MIN_VALUE}|${reminder.reminderLabel ?: ""}|$minuteBucket"
}

private fun formatDeadline(instant: Instant?): String =
    instant?.atZone(IST_ZONE)?.format(PREVIEW_TIME_FORMATTER) ?: "Not detected"

private fun formatReminderPreview(reminder: ReminderEvent): String {
    val localLabel = reminder.scheduledTime.atZone(IST_ZONE).format(PREVIEW_TIME_FORMATTER)
    val beforeAnchor = reminder.minutesBeforeAnchor?.let {
        " · ${TimeFormatter.humanizeDuration(Duration.ofMinutes(it))} before"
    }.orEmpty()
    return "$localLabel$beforeAnchor"
}

private fun toFutureOrTomorrow(candidate: ZonedDateTime, now: ZonedDateTime): ZonedDateTime {
    return if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
}

private fun parseDayOfWeek(rawInput: String): DayOfWeek? {
    val token = DAY_TOKEN_REGEX.find(rawInput)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    return when (token) {
        "monday" -> DayOfWeek.MONDAY
        "tuesday" -> DayOfWeek.TUESDAY
        "wednesday" -> DayOfWeek.WEDNESDAY
        "thursday" -> DayOfWeek.THURSDAY
        "friday" -> DayOfWeek.FRIDAY
        "saturday" -> DayOfWeek.SATURDAY
        "sunday" -> DayOfWeek.SUNDAY
        else -> null
    }
}

private fun parseHourMinute(rawInput: String): Pair<Int, Int>? {
    val match = TIME_TOKEN_REGEX.find(rawInput) ?: return null
    val hour = match.groupValues[1].toIntOrNull()?.coerceIn(1, 12) ?: return null
    val minute = match.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
    return hour to minute
}

private fun draftTabLabel(draft: TaskDraft, linkedMode: Boolean): String {
    return when (draft.primitiveType) {
        PrimitiveType.EVENT -> "Event"
        PrimitiveType.TASK -> if (linkedMode) "Prep task" else "Task"
        PrimitiveType.DERIVED_TASK_EVENT -> "Task + event"
    }
}

private fun clarificationQuestion(reason: ClarificationReason, draft: TaskDraft): String {
    return when (reason) {
        ClarificationReason.NO_DEADLINE_DETECTED -> "When does this need to happen?"
        ClarificationReason.VAGUE_TEMPORAL_EXPRESSION -> {
            val token = draft.temporalExpressionRaw ?: "that phrase"
            "When exactly? ‘$token’ isn't specific enough to schedule."
        }
        ClarificationReason.AMBIGUOUS_DAY_REFERENCE -> "Which date did you mean?"
        ClarificationReason.MORNING_PASSED -> "Morning has already passed — what should ORKA schedule instead?"
        ClarificationReason.AMBIGUOUS_AM_PM -> "Is that time AM or PM?"
        ClarificationReason.DATE_IN_PAST -> "That time has already passed — should ORKA move it to tomorrow?"
    }
}

private fun launchDateTimePicker(
    context: Context,
    initial: ZonedDateTime,
    onSelected: (Instant) -> Unit,
) {
    DatePickerDialog(
        context,
        { _, year, monthOfYear, dayOfMonth ->
            TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    val resolved = ZonedDateTime.of(
                        LocalDate.of(year, monthOfYear + 1, dayOfMonth),
                        LocalTime.of(hourOfDay, minute),
                        IST_ZONE,
                    )
                    onSelected(resolved.toInstant())
                },
                initial.hour,
                initial.minute,
                false,
            ).show()
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).show()
}

@Composable
private fun ClarificationChoiceCard(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClarificationSheet(
    reason: ClarificationReason,
    draft: TaskDraft,
    smartSuggestion: Instant?,
    onApply: (Instant) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val nowIst = ZonedDateTime.now(IST_ZONE)
    val fallbackReference = draft.deadline?.atZone(IST_ZONE) ?: nowIst.with(LocalTime.of(9, 0))
    val mandatoryClarification = reason in setOf(
        ClarificationReason.NO_DEADLINE_DETECTED,
        ClarificationReason.VAGUE_TEMPORAL_EXPRESSION,
        ClarificationReason.AMBIGUOUS_AM_PM,
    )

    ModalBottomSheet(
        onDismissRequest = {
            if (!mandatoryClarification) onCancel()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(draft.rawInput, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = clarificationQuestion(reason, draft),
                style = MaterialTheme.typography.headlineMedium,
            )

            when (reason) {
                ClarificationReason.NO_DEADLINE_DETECTED,
                ClarificationReason.VAGUE_TEMPORAL_EXPRESSION,
                -> {
                    smartSuggestion?.let { suggested ->
                        ClarificationChoiceCard(
                            title = suggested.atZone(IST_ZONE).format(PREVIEW_TIME_FORMATTER),
                            subtitle = "Suggested for this task",
                        ) {
                            onApply(suggested)
                        }
                    }

                    ClarificationChoiceCard(
                        title = "Today · 6:00 PM",
                        subtitle = "Quick schedule",
                    ) {
                        onApply(toFutureOrTomorrow(nowIst.with(LocalTime.of(18, 0)), nowIst).toInstant())
                    }

                    ClarificationChoiceCard(
                        title = "Tomorrow · 9:00 AM",
                        subtitle = "Morning default",
                    ) {
                        onApply(nowIst.plusDays(1).with(LocalTime.of(9, 0)).toInstant())
                    }

                    val weekEnd = nowIst
                        .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                        .with(LocalTime.of(23, 59))
                        .let { if (it.isAfter(nowIst)) it else it.plusWeeks(1) }

                    ClarificationChoiceCard(
                        title = "This week end",
                        subtitle = weekEnd.format(PREVIEW_TIME_FORMATTER),
                    ) {
                        onApply(weekEnd.toInstant())
                    }

                    OrkaActionButton(
                        text = "Pick date & time",
                        emphasis = ActionEmphasis.PRIMARY,
                    ) {
                        launchDateTimePicker(
                            context = context,
                            initial = fallbackReference,
                            onSelected = onApply,
                        )
                    }
                }

                ClarificationReason.MORNING_PASSED -> {
                    ClarificationChoiceCard(
                        title = "This afternoon",
                        subtitle = toFutureOrTomorrow(nowIst.with(LocalTime.of(14, 0)), nowIst)
                            .format(PREVIEW_TIME_FORMATTER),
                    ) {
                        onApply(toFutureOrTomorrow(nowIst.with(LocalTime.of(14, 0)), nowIst).toInstant())
                    }

                    ClarificationChoiceCard(
                        title = "Tomorrow morning",
                        subtitle = nowIst.plusDays(1).with(LocalTime.of(9, 0)).format(PREVIEW_TIME_FORMATTER),
                    ) {
                        onApply(nowIst.plusDays(1).with(LocalTime.of(9, 0)).toInstant())
                    }

                    OrkaActionButton(
                        text = "Pick date & time",
                        emphasis = ActionEmphasis.SECONDARY,
                    ) {
                        launchDateTimePicker(
                            context = context,
                            initial = fallbackReference,
                            onSelected = onApply,
                        )
                    }
                }

                ClarificationReason.AMBIGUOUS_DAY_REFERENCE -> {
                    val targetDay = parseDayOfWeek(draft.rawInput) ?: DayOfWeek.FRIDAY
                    val baseTime = draft.deadline?.atZone(IST_ZONE)?.toLocalTime() ?: LocalTime.of(9, 0)
                    val firstCandidate = ZonedDateTime.of(
                        nowIst.toLocalDate().with(TemporalAdjusters.next(targetDay)),
                        baseTime,
                        IST_ZONE,
                    )
                    val secondCandidate = firstCandidate.plusWeeks(1)

                    ClarificationChoiceCard(
                        title = firstCandidate.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)),
                        subtitle = firstCandidate.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)),
                    ) {
                        onApply(firstCandidate.toInstant())
                    }

                    ClarificationChoiceCard(
                        title = secondCandidate.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)),
                        subtitle = secondCandidate.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)),
                    ) {
                        onApply(secondCandidate.toInstant())
                    }

                    OrkaActionButton(
                        text = "Pick date & time",
                        emphasis = ActionEmphasis.SECONDARY,
                    ) {
                        launchDateTimePicker(
                            context = context,
                            initial = firstCandidate,
                            onSelected = onApply,
                        )
                    }
                }

                ClarificationReason.AMBIGUOUS_AM_PM -> {
                    val (hour12, minute) = parseHourMinute(draft.temporalExpressionRaw ?: draft.rawInput) ?: (9 to 0)
                    val baseDate = draft.deadline?.atZone(IST_ZONE)?.toLocalDate() ?: nowIst.toLocalDate()
                    var amCandidate = ZonedDateTime.of(
                        baseDate,
                        LocalTime.of(if (hour12 == 12) 0 else hour12, minute),
                        IST_ZONE,
                    )
                    var pmCandidate = ZonedDateTime.of(
                        baseDate,
                        LocalTime.of(if (hour12 == 12) 12 else hour12 + 12, minute),
                        IST_ZONE,
                    )
                    if (amCandidate.isBefore(nowIst.minusMinutes(1))) amCandidate = amCandidate.plusDays(1)
                    if (pmCandidate.isBefore(nowIst.minusMinutes(1))) pmCandidate = pmCandidate.plusDays(1)

                    ClarificationChoiceCard(
                        title = "AM (${String.format(Locale.ENGLISH, "%02d:%02d", hour12, minute)})",
                        subtitle = amCandidate.format(PREVIEW_TIME_FORMATTER),
                    ) {
                        onApply(amCandidate.toInstant())
                    }

                    ClarificationChoiceCard(
                        title = "PM (${String.format(Locale.ENGLISH, "%02d:%02d", hour12, minute)})",
                        subtitle = pmCandidate.format(PREVIEW_TIME_FORMATTER),
                    ) {
                        onApply(pmCandidate.toInstant())
                    }

                    OrkaActionButton(
                        text = "Pick date & time",
                        emphasis = ActionEmphasis.SECONDARY,
                    ) {
                        launchDateTimePicker(
                            context = context,
                            initial = pmCandidate,
                            onSelected = onApply,
                        )
                    }
                }

                ClarificationReason.DATE_IN_PAST -> {
                    val tomorrow = (draft.deadline?.atZone(IST_ZONE) ?: fallbackReference)
                        .plusDays(1)
                    ClarificationChoiceCard(
                        title = "Tomorrow (same time)",
                        subtitle = tomorrow.format(PREVIEW_TIME_FORMATTER),
                    ) {
                        onApply(tomorrow.toInstant())
                    }

                    OrkaActionButton(
                        text = "Pick a different date",
                        emphasis = ActionEmphasis.SECONDARY,
                    ) {
                        launchDateTimePicker(
                            context = context,
                            initial = tomorrow,
                            onSelected = onApply,
                        )
                    }
                }
            }

            if (!mandatoryClarification) {
                OrkaActionButton(
                    text = "Cancel",
                    emphasis = ActionEmphasis.TERTIARY,
                    onClick = onCancel,
                )
            }
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

    val primaryDraft = state.draft
    val draftsToRender = state.linkedDrafts.ifEmpty { listOfNotNull(state.draft) }
    val selectedDraftIndex = state.selectedDraftIndex.coerceIn(0, (draftsToRender.lastIndex).coerceAtLeast(0))
    val selectedDraft = draftsToRender.getOrNull(selectedDraftIndex)
    val selectedPreviewReminders = state.reminderPreviews.getOrNull(selectedDraftIndex).orEmpty()

    OrkaSurface {
        OrkaScreenContainer(
            modifier = modifier.verticalScroll(rememberScrollState()),
        ) {
            OrkaEyebrow("Capture")
            Text("Capture", style = MaterialTheme.typography.headlineLarge)
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
                enabled = !state.isAnalyzing,
                onClick = viewModel::analyse,
            )

            state.warningMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.infoMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val clarificationReason = primaryDraft?.clarificationReason
            if (primaryDraft?.clarificationNeeded == true && clarificationReason != null) {
                ClarificationSheet(
                    reason = clarificationReason,
                    draft = primaryDraft,
                    smartSuggestion = state.smartClarificationInstant,
                    onApply = viewModel::applyClarification,
                    onCancel = viewModel::dismissDraft,
                )
            }

            if (selectedDraft != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (draftsToRender.size > 1) "Confirm linked entries" else "Confirm task",
                            style = MaterialTheme.typography.headlineMedium,
                        )

                        if (draftsToRender.size > 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                draftsToRender.forEachIndexed { index, draft ->
                                    FilterChip(
                                        selected = index == selectedDraftIndex,
                                        onClick = { viewModel.selectDraft(index) },
                                        label = {
                                            Text(draftTabLabel(draft, linkedMode = true))
                                        },
                                    )
                                }
                            }
                        }

                        val deadlineNeedsAttention = "deadline" in selectedDraft.lowConfidenceFields
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (deadlineNeedsAttention) {
                                        OrkaAmber.copy(alpha = 0.12f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                )
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "Type: ${selectedDraft.primitiveType.displayName()}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text("Title: ${selectedDraft.title}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Category: ${selectedDraft.category.displayName()}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "Effort: ${selectedDraft.estimatedEffortMinutes} min",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "Deadline confidence: ${(selectedDraft.deadlineConfidence * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (selectedDraft.primitiveType != PrimitiveType.TASK) {
                                Text(
                                    "Event profile: ${selectedDraft.preEventProfile?.displayName() ?: "Meeting"}",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                // titleMedium (JetBrains Mono) matches how TaskDetailScreen and
                                // OrkaTaskCard render deadline/time text elsewhere in the app.
                                Text(
                                    "Event time: ${formatDeadline(selectedDraft.eventStartTime)}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                text = "Deadline: ${formatDeadline(selectedDraft.deadline)}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        if (selectedPreviewReminders.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            Text("Reminder schedule preview", style = MaterialTheme.typography.titleMedium)
                            selectedPreviewReminders.forEach { reminder ->
                                val reminderKey = reminderPreviewKey(selectedDraftIndex, reminder)
                                val removed = reminderKey in state.dismissedReminderKeys
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (removed) {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            },
                                        )
                                        .clickable {
                                            viewModel.toggleReminderSuppression(selectedDraftIndex, reminder)
                                        }
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = reminder.reminderLabel
                                            ?: "Reminder ${reminder.sequenceNumber}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        textDecoration = if (removed) {
                                            TextDecoration.LineThrough
                                        } else {
                                            TextDecoration.None
                                        },
                                    )
                                    Text(
                                        text = formatReminderPreview(reminder),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textDecoration = if (removed) {
                                            TextDecoration.LineThrough
                                        } else {
                                            TextDecoration.None
                                        },
                                    )
                                    Text(
                                        text = if (removed) {
                                            "Removed — tap to restore"
                                        } else {
                                            "Tap to remove from schedule"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        OrkaActionButton(
                            text = if (state.isConfirming) "Saving..." else "Confirm",
                            emphasis = ActionEmphasis.PRIMARY,
                            enabled = !state.isConfirming,
                            onClick = viewModel::confirmDraft,
                        )
                        OrkaActionButton(
                            text = "Re-enter",
                            enabled = !state.isConfirming,
                            emphasis = ActionEmphasis.TERTIARY,
                            onClick = viewModel::dismissDraft,
                        )
                    }
                }
            }
        }
    }
}
