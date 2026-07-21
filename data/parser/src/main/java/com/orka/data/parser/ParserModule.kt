package com.orka.data.parser

import android.content.Context
import com.orka.core.model.ClarificationReason
import com.orka.core.model.PreEventProfile
import com.orka.core.model.PrimitiveType
import com.orka.core.common.UrgencyCalculator
import com.orka.core.model.ModelAvailability
import com.orka.core.model.ModelInstallState
import com.orka.core.model.ModelInstaller
import com.orka.core.model.ParseMode
import com.orka.core.model.ParserContext
import com.orka.core.model.TaskCategory
import com.orka.core.model.TaskDraft
import com.orka.core.model.Task
import com.orka.core.model.TaskDraftValidationResult
import com.orka.core.model.TaskDraftValidator
import com.orka.core.model.TaskParseResult
import com.orka.core.model.TaskParser
import com.orka.core.model.TaskSplitSuggestion
import com.orka.core.model.TaskSplitter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MODEL_FILE_NAME = "gemma-4-E4B-it.litertlm"
private const val MODEL_MIN_VALID_SIZE_BYTES = 10 * 1024 * 1024L

/**
 * Manually `adb push`ed once to this well-known, world-readable device path — never bundled
 * into the APK. Keeping the model out of app builds means every subsequent `assembleDebug` /
 * `installDebug` is a lightweight APK instead of re-shipping several gigabytes each time.
 */
private const val DEVICE_PUSHED_MODEL_DIR = "/data/local/tmp"

/**
 * Holds a single warm [Engine] for the lifetime of the process. Engine.initialize() can take
 * up to ~10s, so re-creating it per parse (or per ViewModel instance) would make every capture
 * pay that cost. Guarded by a mutex since parse() may be invoked concurrently.
 */
private object GemmaEngineHolder {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedModelPath: String? = null

    suspend fun getOrCreate(modelPath: String): Engine = mutex.withLock {
        val existing = engine
        if (existing != null && loadedModelPath == modelPath) return@withLock existing

        existing?.close()
        engine = null
        loadedModelPath = null

        val created = withContext(Dispatchers.IO) {
            Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU())).apply { initialize() }
        }
        engine = created
        loadedModelPath = modelPath
        created
    }

    suspend fun reset() = mutex.withLock {
        engine?.close()
        engine = null
        loadedModelPath = null
    }
}

private fun Message.asText(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString(separator = "") { it.text }

private fun cleanJson(input: String): String {
    var cleaned = input.trim()
    if (cleaned.startsWith("```")) {
        cleaned = cleaned.substringAfter("\n")
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substringBeforeLast("```")
        }
    }
    return cleaned.trim()
}

/** Shared by [DefaultTaskParser] and [DefaultTaskSplitter]: is the on-device model actually usable? */
private fun resolveReadyModelFile(modelState: com.orka.core.model.ModelInstallState): File? {
    val modelFile = modelState.modelPath?.let(::File)
    val ready = modelState.availability == ModelAvailability.READY &&
        modelFile != null &&
        modelFile.exists() &&
        modelFile.length() > MODEL_MIN_VALID_SIZE_BYTES
    return if (ready) modelFile else null
}

private val IST_ZONE_ID: ZoneId = ZoneId.of("Asia/Kolkata")
private const val MONTH_PATTERN = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
private val DURATION_REGEX = Regex("""\bin\s+(\d+)\s*(minutes?|mins?|hours?|hrs?|days?)\b""")
private val TIME_WITH_AM_PM_REGEX = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""")
private val BARE_TIME_REGEX = Regex("""\b(?:at|by|before)\s+(\d{1,2})(?::(\d{2}))?\b""")
private val DMY_DATE_REGEX = Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""")
private val ORDINAL_MONTH_REGEX = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+($MONTH_PATTERN)\b""")
private val MONTH_ORDINAL_REGEX = Regex("""\b($MONTH_PATTERN)\s+(\d{1,2})(?:st|nd|rd|th)?\b""")

private data class TemporalResolution(
    val resolvedInstant: Instant? = null,
    val confidence: Float = 0f,
    val clarificationReason: ClarificationReason? = null,
    val temporalExpressionRaw: String? = null,
    val lowConfidenceFields: Set<String> = emptySet(),
)

private data class DateResolution(
    val date: LocalDate? = null,
    val confidence: Float = 0.95f,
    val clarificationReason: ClarificationReason? = null,
    val raw: String? = null,
    val inferred: Boolean = false,
)

private data class TimeResolution(
    val time: LocalTime? = null,
    val confidence: Float = 0.95f,
    val clarificationReason: ClarificationReason? = null,
    val raw: String? = null,
    val inferred: Boolean = false,
)

class DefaultTaskDraftValidator @Inject constructor() : TaskDraftValidator {
    override fun validate(draft: TaskDraft): TaskDraftValidationResult {
        val errors = buildList {
            if (draft.title.isBlank()) add("Task title is required.")
            if (draft.clarificationNeeded) add("Clarification is required before confirming this task.")
            if (draft.deadline == null && !draft.clarificationNeeded) add("Deadline is required.")
            if (
                draft.primitiveType == PrimitiveType.EVENT &&
                draft.eventStartTime == null &&
                !draft.clarificationNeeded
            ) {
                add("Event start time is required for event reminders.")
            }
            if (draft.estimatedEffortMinutes <= 0) add("Estimated effort must be positive.")
        }
        return TaskDraftValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }
}

@Serializable
private data class LlmTaskDraft(
    val primitive_type: String,
    val title: String,
    val description: String,
    val deadline_timestamp: String? = null,
    val deadline_confidence: Float = 0f,
    val event_start_time: String? = null,
    val event_duration_minutes: Int? = null,
    val pre_event_profile: String? = null,
    val clarification_needed: Boolean = false,
    val clarification_reason: String? = null,
    val resolved_timezone: String = "Asia/Kolkata",
    val temporal_expression_raw: String? = null,
    val category: String,
    val estimated_effort_minutes: Int = 30,
) {
    fun toTaskDraft(rawInput: String, parseMode: ParseMode): TaskDraft {
        val deadlineInstant = deadline_timestamp?.let { 
            runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull()
        }
        val eventStartInstant = event_start_time?.let {
            runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull()
        }
        val primType = runCatching { PrimitiveType.valueOf(primitive_type) }.getOrElse { PrimitiveType.TASK }
        val profile = pre_event_profile?.let {
            runCatching { PreEventProfile.valueOf(it) }.getOrNull()
        }
        val reason = clarification_reason?.let {
            runCatching { ClarificationReason.valueOf(it) }.getOrNull()
        }
        val cat = runCatching { TaskCategory.valueOf(category) }.getOrElse { TaskCategory.OTHER }

        val lowConfidence = buildSet {
            if (deadline_confidence < 0.75f) add("deadline")
            if (title.length < 6) add("title")
        }

        return TaskDraft(
            rawInput = rawInput,
            title = title,
            description = description,
            deadline = deadlineInstant,
            deadlineConfidence = deadline_confidence,
            primitiveType = primType,
            eventStartTime = eventStartInstant,
            eventDurationMinutes = event_duration_minutes,
            preEventProfile = profile,
            linkedEntityId = null,
            clarificationNeeded = clarification_needed,
            clarificationReason = reason,
            resolvedTimezone = resolved_timezone,
            temporalExpressionRaw = temporal_expression_raw,
            category = cat,
            estimatedEffortMinutes = estimated_effort_minutes,
            urgencyScore = deadlineInstant?.let { UrgencyCalculator.urgencyScore(it, Instant.now(), estimated_effort_minutes) } ?: 1f,
            lowConfidenceFields = lowConfidence,
            parseMode = parseMode,
        )
    }
}

class DefaultTaskParser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelInstaller: ModelInstaller,
) : TaskParser {
    override suspend fun parse(rawInput: String, context: ParserContext): TaskParseResult {
        // Re-checked (cheap: just exists()/length(), no file I/O) rather than trusting
        // observeState()'s possibly-not-yet-initialized value, so a model pushed after app
        // launch is picked up on the very next capture without requiring a restart.
        val modelFile = resolveReadyModelFile(modelInstaller.installBundledModelIfAvailable())

        val parsed = if (modelFile != null) gemmaParse(rawInput, context, modelFile) else null

        val finalDrafts = parsed ?: fallbackParse(
            rawInput = rawInput,
            context = context,
            parseMode = ParseMode.FALLBACK,
        )

        return TaskParseResult(
            draft = finalDrafts.first(),
            linkedDrafts = finalDrafts,
            issues = buildIssues(finalDrafts),
        )
    }

    private suspend fun gemmaParse(
        rawInput: String,
        context: ParserContext,
        modelFile: File,
    ): List<TaskDraft>? {
        val nowIst = context.now.withZoneSameInstant(IST_ZONE_ID)
        val contextBlock = """
            CURRENT CONTEXT:
            Date: ${nowIst.dayOfWeek.name.lowercase().replaceFirstChar { it.titlecase() }}, ${"%02d".format(nowIst.dayOfMonth)} ${nowIst.month.name.lowercase().replaceFirstChar { it.titlecase() }} ${nowIst.year}
            Time: ${"%02d:%02d".format(nowIst.hour, nowIst.minute)} IST
            Timezone: Asia/Kolkata (IST, UTC+5:30)
        """.trimIndent()

        val systemPrompt = """
            You are a structured task extraction engine. Your sole job is to parse natural language input and return a valid JSON object conforming to the TaskObject schema. You output only JSON, with no preamble, no explanation, and no markdown.
            
            $contextBlock
            
            INPUT CLASSIFICATION RULES:
            - EVENT: An event is something that happens at a specific time. Has a start time, not a deadline. Examples: "Meeting at 9pm", "Doctor's appointment on Thursday at 11am", "Flight on 15th March at 6:45am".
            - TASK: A task is something the user must complete by a future point. Has a deadline. Examples: "Submit assignment by Friday 11:59pm", "Finish client report before Monday".
            - DERIVED_TASK_EVENT: Preparation/practice for an upcoming moment. Decompose into two linked entities: one EVENT (the presentation/exam/call itself) and one TASK (the preparation work, with the event time as its deadline). Examples: "Prepare slides for the Monday presentation".
            
            TEMPORAL RESOLUTION RULES:
            1. Time with no date:
               - If specified time is > 30 minutes in future -> resolve to today.
               - If specified time is <= 30 minutes in future -> resolve to today, flag deadline_confidence: 0.6.
               - If specified time has already passed today -> resolve to tomorrow.
            2. "Tomorrow": calendar day following current IST date.
            3. "Tonight"/"This evening": today. "Tonight" defaults to 21:00 IST. "This evening" defaults to 18:00 IST.
            4. "This morning"/"This afternoon": "This morning" defaults to 09:00 IST. "This afternoon" defaults to 14:00 IST. If already passed, set clarification_needed: true and clarification_reason: "morning_passed".
            5. Named day:
               - "This [day]": upcoming occurrence in current week (Mon-Sun). If day passed, set clarification_needed: true.
               - "Next [day]": occurrence in following week.
            6. "This week": Sunday 23:59 IST.
            7. "End of day"/"EOD": today 23:59 IST.
            8. Explicit date: if no year, assume current year if future, next year if past.
            9. "Soon"/"Later"/Vague: set clarification_needed: true, clarification_reason: "vague_temporal_expression".
            10. No temporal expression: set clarification_needed: true, clarification_reason: "no_deadline_detected".
            
            TIME-OF-DAY DEFAULTS:
            - Morning: 09:00 IST
            - Mid-morning: 10:30 IST
            - Afternoon: 14:00 IST
            - Evening: 18:00 IST
            - Tonight/Night: 21:00 IST
            - Late night: 23:00 IST
            - Dawn/Early morning: 06:00 IST
            
            AM/PM INFERENCE:
            - 1 to 6 with no AM/PM -> PM.
            - 7 to 11 with no AM/PM -> AM if morning keyword, otherwise PM if current time is morning, AM if current time is evening.
            - 12 with no AM/PM -> noon (12pm) unless midnight/raat is present.
            
            OUTPUT SCHEMA:
            If type is DERIVED_TASK_EVENT, return a JSON array containing two objects (the TASK first, then the EVENT). For all other types, return a single JSON object.
            Fields:
            - "primitive_type": "EVENT", "TASK", or "DERIVED_TASK_EVENT"
            - "title": string (short title)
            - "description": string (original input)
            - "deadline_timestamp": ISO 8601 string with IST offset (e.g. "2025-04-02T21:00:00+05:30") or null (for TASK entities, this is the deadline; for EVENT, same as event_start_time)
            - "deadline_confidence": float between 0.0 and 1.0
            - "event_start_time": ISO 8601 string with IST offset or null
            - "event_duration_minutes": integer or null (e.g., meeting=60, appointment=30, exam=180, call=15)
            - "pre_event_profile": "MEETING", "EXAM", "APPOINTMENT", "TRAVEL", "CALL", "DEADLINE_EVENT" or null
            - "clarification_needed": boolean
            - "clarification_reason": "no_deadline_detected", "vague_temporal_expression", "ambiguous_day_reference", "morning_passed", "ambiguous_am_pm", "date_in_past" or null
            - "resolved_timezone": "Asia/Kolkata"
            - "temporal_expression_raw": string or null
            - "category": "ACADEMIC", "PERSONAL", "PROFESSIONAL", "CLUB", "HEALTH", "FINANCIAL", "OTHER"
            - "estimated_effort_minutes": integer
            
            WORKED EXAMPLES:
            Example 1: "Submit the ML assignment by Friday 11:59pm" (context: Wednesday 02 Apr 2025, 14:35 IST)
            Output: {"primitive_type":"TASK","title":"Submit the ML assignment","description":"Submit the ML assignment by Friday 11:59pm","deadline_timestamp":"2025-04-04T23:59:00+05:30","deadline_confidence":0.95,"event_start_time":null,"event_duration_minutes":null,"pre_event_profile":null,"clarification_needed":false,"clarification_reason":null,"resolved_timezone":"Asia/Kolkata","temporal_expression_raw":"Friday 11:59pm","category":"ACADEMIC","estimated_effort_minutes":120}
            
            Example 2: "Meeting at 9pm" (context: Wednesday 02 Apr 2025, 14:35 IST)
            Output: {"primitive_type":"EVENT","title":"Meeting","description":"Meeting at 9pm","deadline_timestamp":"2025-04-02T21:00:00+05:30","deadline_confidence":0.95,"event_start_time":"2025-04-02T21:00:00+05:30","event_duration_minutes":60,"pre_event_profile":"MEETING","clarification_needed":false,"clarification_reason":null,"resolved_timezone":"Asia/Kolkata","temporal_expression_raw":"9pm","category":"OTHER","estimated_effort_minutes":45}
            
            Example 3: "Prepare slides for Monday presentation" (context: Wednesday 02 Apr 2025, 14:35 IST)
            Output: [{"primitive_type":"TASK","title":"Prepare slides","description":"Prepare slides for Monday presentation","deadline_timestamp":"2025-04-07T09:00:00+05:30","deadline_confidence":0.65,"event_start_time":null,"event_duration_minutes":null,"pre_event_profile":null,"clarification_needed":false,"clarification_reason":null,"resolved_timezone":"Asia/Kolkata","temporal_expression_raw":"Monday","category":"PROFESSIONAL","estimated_effort_minutes":120},{"primitive_type":"EVENT","title":"Presentation","description":"Prepare slides for Monday presentation","deadline_timestamp":"2025-04-07T09:00:00+05:30","deadline_confidence":0.65,"event_start_time":"2025-04-07T09:00:00+05:30","event_duration_minutes":60,"pre_event_profile":"MEETING","clarification_needed":false,"clarification_reason":null,"resolved_timezone":"Asia/Kolkata","temporal_expression_raw":"Monday","category":"PROFESSIONAL","estimated_effort_minutes":120}]
            
            USER INPUT:
            "$rawInput"
            
            Output JSON:
        """.trimIndent()

        return try {
            val engine = GemmaEngineHolder.getOrCreate(modelFile.absolutePath)
            val response = engine.createConversation().use { conversation ->
                withContext(Dispatchers.Default) {
                    conversation.sendMessage(systemPrompt)
                }
            }
            parseJsonToTaskDrafts(response.asText(), rawInput)
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private fun parseJsonToTaskDrafts(response: String, rawInput: String): List<TaskDraft>? {
        val cleaned = cleanJson(response)
        val json = Json { ignoreUnknownKeys = true }
        return try {
            if (cleaned.startsWith("[")) {
                val list = json.decodeFromString<List<LlmTaskDraft>>(cleaned)
                val drafts = list.map { it.toTaskDraft(rawInput, ParseMode.GEMMA) }
                if (drafts.size == 2) {
                    val linkedId = UUID.randomUUID().toString()
                    drafts.map { it.copy(linkedEntityId = linkedId) }
                } else {
                    drafts
                }
            } else {
                val single = json.decodeFromString<LlmTaskDraft>(cleaned)
                listOf(single.toTaskDraft(rawInput, ParseMode.GEMMA))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun fallbackParse(
        rawInput: String,
        context: ParserContext,
        parseMode: ParseMode,
    ): List<TaskDraft> {
        val normalized = rawInput.trim()
        val lower = normalized.lowercase(Locale.ROOT)
        val primitiveType = classifyPrimitiveType(lower)
        val preEventProfile = inferPreEventProfile(lower, primitiveType)
        val temporal = resolveTemporalExpression(
            rawInput = normalized,
            context = context,
            primitiveType = primitiveType,
            preEventProfile = preEventProfile,
        )

        val category = inferCategory(normalized)
        val effort = inferEffort(normalized)

        val lowConfidence = buildSet {
            addAll(temporal.lowConfidenceFields)
            if (normalized.length < 6) add("title")
        }

        val linkedEntityId = if (primitiveType == PrimitiveType.DERIVED_TASK_EVENT) {
            UUID.randomUUID().toString()
        } else {
            null
        }

        val deadline = temporal.resolvedInstant
        val clarificationNeeded = temporal.clarificationReason != null
        val confidence = if (clarificationNeeded) 0f else temporal.confidence
        val title = inferTitle(normalized)

        val baseDraft = TaskDraft(
            rawInput = normalized,
            title = title,
            description = normalized,
            deadline = if (clarificationNeeded) null else deadline,
            deadlineConfidence = confidence,
            primitiveType = primitiveType,
            eventStartTime = when (primitiveType) {
                PrimitiveType.EVENT,
                PrimitiveType.DERIVED_TASK_EVENT,
                -> if (clarificationNeeded) null else deadline
                PrimitiveType.TASK -> null
            },
            eventDurationMinutes = inferEventDurationMinutes(lower, preEventProfile),
            preEventProfile = preEventProfile,
            linkedEntityId = linkedEntityId,
            clarificationNeeded = clarificationNeeded,
            clarificationReason = temporal.clarificationReason,
            resolvedTimezone = IST_ZONE_ID.id,
            temporalExpressionRaw = temporal.temporalExpressionRaw,
            category = category,
            estimatedEffortMinutes = effort,
            urgencyScore = deadline?.let { UrgencyCalculator.urgencyScore(it, context.now.toInstant(), effort) } ?: 1f,
            lowConfidenceFields = lowConfidence,
            parseMode = parseMode,
        )

        if (primitiveType != PrimitiveType.DERIVED_TASK_EVENT || clarificationNeeded) {
            return listOf(baseDraft)
        }

        val eventDraft = baseDraft.copy(
            primitiveType = PrimitiveType.EVENT,
            title = inferDerivedEventTitle(normalized),
        )
        val taskDraft = baseDraft.copy(
            primitiveType = PrimitiveType.TASK,
            preEventProfile = null,
            eventStartTime = null,
            title = inferDerivedTaskTitle(normalized),
        )
        return listOf(taskDraft, eventDraft)
    }

    private fun buildIssues(drafts: List<TaskDraft>): List<String> {
        return buildList {
            val primary = drafts.firstOrNull() ?: return@buildList
            primary.clarificationReason?.let { reason ->
                add(
                    when (reason) {
                        ClarificationReason.NO_DEADLINE_DETECTED -> "When does this need to happen?"
                        ClarificationReason.VAGUE_TEMPORAL_EXPRESSION -> "When exactly? That time expression is too vague to schedule."
                        ClarificationReason.AMBIGUOUS_DAY_REFERENCE -> "The day reference is ambiguous. Please pick the exact date."
                        ClarificationReason.MORNING_PASSED -> "Morning has already passed. Pick this afternoon, tomorrow morning, or a custom time."
                        ClarificationReason.AMBIGUOUS_AM_PM -> "Please confirm whether the time is AM or PM."
                        ClarificationReason.DATE_IN_PAST -> "That time has already passed. Pick tomorrow or choose another date."
                    },
                )
            }
            if ("deadline" in primary.lowConfidenceFields && !primary.clarificationNeeded) {
                add("Time was inferred from context. Review highlighted fields before confirming.")
            }
            if ("title" in primary.lowConfidenceFields) {
                add("Task title looks short or ambiguous. Consider rephrasing with more detail.")
            }
        }
    }

    private fun inferCategory(rawInput: String): TaskCategory {
        val value = rawInput.lowercase(Locale.ROOT)
        return when {
            listOf("assignment", "exam", "professor", "class", "semester", "lecture").any(value::contains) -> TaskCategory.ACADEMIC
            listOf("client", "report", "meeting", "internship", "review", "invoice").any(value::contains) -> TaskCategory.PROFESSIONAL
            listOf("club", "event", "committee", "budget").any(value::contains) -> TaskCategory.CLUB
            listOf("gym", "doctor", "workout", "medication").any(value::contains) -> TaskCategory.HEALTH
            listOf("bank", "bill", "rent", "payment", "emi").any(value::contains) -> TaskCategory.FINANCIAL
            listOf("mom", "dad", "home", "call", "personal").any(value::contains) -> TaskCategory.PERSONAL
            else -> TaskCategory.OTHER
        }
    }

    private fun inferEffort(rawInput: String): Int {
        val value = rawInput.lowercase(Locale.ROOT)
        return when {
            listOf("quick", "call", "reply", "email").any(value::contains) -> 20
            listOf("report", "assignment", "prepare", "presentation").any(value::contains) -> 120
            listOf("budget", "proposal", "research").any(value::contains) -> 180
            else -> 45
        }
    }

    private fun resolveTemporalExpression(
        rawInput: String,
        context: ParserContext,
        primitiveType: PrimitiveType,
        preEventProfile: PreEventProfile?,
    ): TemporalResolution {
        val nowIst = context.now.withZoneSameInstant(IST_ZONE_ID)
        val lowercase = rawInput.lowercase(Locale.ROOT)

        if (containsVagueTemporalExpression(lowercase)) {
            return TemporalResolution(
                clarificationReason = ClarificationReason.VAGUE_TEMPORAL_EXPRESSION,
                temporalExpressionRaw = extractVagueTemporalExpression(lowercase),
            )
        }

        if (isMorningOrAfternoonPassed(lowercase, nowIst)) {
            return TemporalResolution(
                clarificationReason = ClarificationReason.MORNING_PASSED,
                temporalExpressionRaw = if ("morning" in lowercase) "morning" else "afternoon",
            )
        }

        resolveDurationExpression(lowercase, nowIst)?.let { return it }

        if (!hasAnyTemporalSignal(lowercase)) {
            return TemporalResolution(
                clarificationReason = ClarificationReason.NO_DEADLINE_DETECTED,
            )
        }

        val dateResolution = resolveDateExpression(lowercase, nowIst)
        if (dateResolution.clarificationReason != null) {
            return TemporalResolution(
                clarificationReason = dateResolution.clarificationReason,
                temporalExpressionRaw = dateResolution.raw,
            )
        }

        val timeResolution = resolveTimeExpression(lowercase, nowIst)
        if (timeResolution.clarificationReason != null) {
            return TemporalResolution(
                clarificationReason = timeResolution.clarificationReason,
                temporalExpressionRaw = timeResolution.raw,
            )
        }

        var date = dateResolution.date
        var time = timeResolution.time
        var confidence = maxOf(dateResolution.confidence, timeResolution.confidence).coerceIn(0f, 1f)
        val lowConfidence = mutableSetOf<String>()
        if (dateResolution.inferred || timeResolution.inferred) {
            lowConfidence += "deadline"
        }

        if (date == null && time == null) {
            return TemporalResolution(clarificationReason = ClarificationReason.NO_DEADLINE_DETECTED)
        }

        if (date == null && time != null) {
            val todayCandidate = ZonedDateTime.of(nowIst.toLocalDate(), time, IST_ZONE_ID)
            val minutesUntil = Duration.between(nowIst, todayCandidate).toMinutes()
            date = when {
                minutesUntil > 30 -> nowIst.toLocalDate().also { confidence = minOf(confidence, 0.95f) }
                minutesUntil >= 0 -> nowIst.toLocalDate().also {
                    confidence = minOf(confidence, 0.6f)
                    lowConfidence += "deadline"
                }
                else -> nowIst.toLocalDate().plusDays(1).also { confidence = minOf(confidence, 0.9f) }
            }
        }

        if (date != null && time == null) {
            time = defaultTimeForContext(lowercase, primitiveType, preEventProfile)
            confidence = minOf(confidence, 0.7f)
            lowConfidence += "deadline"
        }

        val resolved = ZonedDateTime.of(date, time, IST_ZONE_ID)
        if (resolved.isBefore(nowIst)) {
            return TemporalResolution(
                clarificationReason = ClarificationReason.DATE_IN_PAST,
                temporalExpressionRaw = timeResolution.raw ?: dateResolution.raw,
            )
        }

        return TemporalResolution(
            resolvedInstant = resolved.toInstant(),
            confidence = confidence,
            temporalExpressionRaw = timeResolution.raw ?: dateResolution.raw,
            lowConfidenceFields = lowConfidence,
        )
    }

    private fun resolveDurationExpression(rawInput: String, nowIst: ZonedDateTime): TemporalResolution? {
        val match = DURATION_REGEX.find(rawInput) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2]
        val resolved = when {
            unit.startsWith("min") -> nowIst.plusMinutes(amount)
            unit.startsWith("hour") || unit.startsWith("hr") -> nowIst.plusHours(amount)
            unit.startsWith("day") -> nowIst.plusDays(amount)
            else -> nowIst
        }
        return TemporalResolution(
            resolvedInstant = resolved.toInstant(),
            confidence = 0.95f,
            temporalExpressionRaw = match.value,
        )
    }

    private fun resolveDateExpression(rawInput: String, nowIst: ZonedDateTime): DateResolution {
        val today = nowIst.toLocalDate()

        if ("this week" in rawInput) {
            return DateResolution(
                date = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)),
                confidence = 0.8f,
                raw = "this week",
                inferred = true,
            )
        }

        if ("tomorrow" in rawInput) {
            return DateResolution(date = today.plusDays(1), confidence = 0.95f, raw = "tomorrow")
        }

        if ("today" in rawInput || "tonight" in rawInput || "this evening" in rawInput) {
            return DateResolution(date = today, confidence = 0.9f, raw = "today", inferred = true)
        }

        DayOfWeek.entries.forEach { day ->
            val token = day.name.lowercase(Locale.ROOT)
            if ("next $token" in rawInput) {
                return DateResolution(
                    date = today.with(TemporalAdjusters.next(day)),
                    confidence = 0.9f,
                    raw = "next $token",
                )
            }
            if ("this $token" in rawInput) {
                if (
                    day == DayOfWeek.FRIDAY &&
                    (nowIst.dayOfWeek == DayOfWeek.SATURDAY || nowIst.dayOfWeek == DayOfWeek.SUNDAY)
                ) {
                    return DateResolution(
                        clarificationReason = ClarificationReason.AMBIGUOUS_DAY_REFERENCE,
                        raw = "this $token",
                    )
                }
                return DateResolution(
                    date = today.with(TemporalAdjusters.nextOrSame(day)),
                    confidence = 0.85f,
                    raw = "this $token",
                    inferred = true,
                )
            }
            if (Regex("""\b$token\b""").containsMatchIn(rawInput)) {
                return DateResolution(
                    date = today.with(TemporalAdjusters.nextOrSame(day)),
                    confidence = 0.85f,
                    raw = token,
                    inferred = true,
                )
            }
        }

        DMY_DATE_REGEX.find(rawInput)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            val month = match.groupValues[2].toIntOrNull() ?: return@let
            val year = match.groupValues[3].toIntOrNull()?.let { if (it < 100) it + 2000 else it } ?: today.year
            val parsedDate = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return@let
            val resolvedYear = if (match.groupValues[3].isBlank() && parsedDate.isBefore(today)) parsedDate.year + 1 else parsedDate.year
            return DateResolution(
                date = parsedDate.withYear(resolvedYear),
                confidence = 0.95f,
                raw = match.value,
            )
        }

        ORDINAL_MONTH_REGEX.find(rawInput)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            val month = monthFromToken(match.groupValues[2]) ?: return@let
            val candidate = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return@let
            val resolved = if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
            return DateResolution(date = resolved, confidence = 0.95f, raw = match.value)
        }

        MONTH_ORDINAL_REGEX.find(rawInput)?.let { match ->
            val month = monthFromToken(match.groupValues[1]) ?: return@let
            val day = match.groupValues[2].toIntOrNull() ?: return@let
            val candidate = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return@let
            val resolved = if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
            return DateResolution(date = resolved, confidence = 0.95f, raw = match.value)
        }

        return DateResolution()
    }

    private fun resolveTimeExpression(rawInput: String, nowIst: ZonedDateTime): TimeResolution {
        if ("eod" in rawInput || "end of day" in rawInput || "before midnight" in rawInput) {
            return TimeResolution(time = LocalTime.of(23, 59), confidence = 0.92f, raw = "end of day")
        }
        if ("midnight" in rawInput) {
            return TimeResolution(time = LocalTime.of(23, 59), confidence = 0.9f, raw = "midnight")
        }
        if ("mid-morning" in rawInput) {
            return TimeResolution(time = LocalTime.of(10, 30), confidence = 0.7f, raw = "mid-morning", inferred = true)
        }
        if ("dawn" in rawInput || "early morning" in rawInput) {
            return TimeResolution(time = LocalTime.of(6, 0), confidence = 0.7f, raw = "dawn", inferred = true)
        }
        if ("this morning" in rawInput || "today morning" in rawInput || "morning" in rawInput) {
            return TimeResolution(time = LocalTime.of(9, 0), confidence = 0.7f, raw = "morning", inferred = true)
        }
        if ("this afternoon" in rawInput || "today afternoon" in rawInput || "afternoon" in rawInput) {
            return TimeResolution(time = LocalTime.of(14, 0), confidence = 0.7f, raw = "afternoon", inferred = true)
        }
        if ("evening" in rawInput) {
            return TimeResolution(time = LocalTime.of(18, 0), confidence = 0.7f, raw = "evening", inferred = true)
        }
        if ("late night" in rawInput) {
            return TimeResolution(time = LocalTime.of(23, 0), confidence = 0.7f, raw = "late night", inferred = true)
        }
        if ("tonight" in rawInput || "night" in rawInput) {
            return TimeResolution(time = LocalTime.of(21, 0), confidence = 0.7f, raw = "tonight", inferred = true)
        }

        TIME_WITH_AM_PM_REGEX.find(rawInput)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val period = match.groupValues[3]
            return TimeResolution(
                time = parseTimeWithAmPm(hour, minute, period),
                confidence = 0.95f,
                raw = match.value,
            )
        }

        BARE_TIME_REGEX.find(rawInput)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val inferred = inferAmPmTime(hour, minute, rawInput, nowIst)
            return TimeResolution(
                time = inferred.first,
                confidence = inferred.second,
                raw = match.value,
                inferred = true,
            )
        }

        return TimeResolution()
    }

    private fun inferAmPmTime(hourInput: Int, minute: Int, rawInput: String, nowIst: ZonedDateTime): Pair<LocalTime, Float> {
        val hour = hourInput.coerceIn(1, 12)
        val lowercase = rawInput.lowercase(Locale.ROOT)
        return when {
            hour == 12 -> {
                val resolved = if ("midnight" in lowercase || "raat" in lowercase) {
                    LocalTime.of(0, minute)
                } else {
                    LocalTime.of(12, minute)
                }
                resolved to 0.7f
            }
            hour in 1..6 -> LocalTime.of(hour + 12, minute) to 0.65f
            hour in 7..11 && hasMorningContext(lowercase) -> LocalTime.of(hour, minute) to 0.7f
            hour in 7..11 && nowIst.hour < 12 -> LocalTime.of(hour + 12, minute) to 0.65f
            else -> LocalTime.of(hour, minute) to 0.65f
        }
    }

    private fun parseTimeWithAmPm(hourInput: Int, minute: Int, period: String): LocalTime {
        val hour = hourInput.coerceIn(1, 12)
        val normalizedHour = when (period.lowercase(Locale.ROOT)) {
            "am" -> if (hour == 12) 0 else hour
            "pm" -> if (hour == 12) 12 else hour + 12
            else -> hour
        }
        return LocalTime.of(normalizedHour, minute)
    }

    private fun classifyPrimitiveType(rawInput: String): PrimitiveType {
        val hasEventKeyword = listOf(
            "meeting", "appointment", "interview", "standup", "call", "flight", "train", "bus", "exam", "quiz", "doctor",
        ).any(rawInput::contains)
        val hasPreparationKeyword = listOf("prepare", "study", "rehearse", "practice").any(rawInput::contains)
        val hasFor = Regex("""\bfor\b""").containsMatchIn(rawInput)

        return when {
            hasPreparationKeyword && hasFor && hasEventKeyword -> PrimitiveType.DERIVED_TASK_EVENT
            hasEventKeyword -> PrimitiveType.EVENT
            else -> PrimitiveType.TASK
        }
    }

    private fun inferPreEventProfile(rawInput: String, primitiveType: PrimitiveType): PreEventProfile? {
        if (primitiveType == PrimitiveType.TASK) return null
        return when {
            listOf("exam", "test", "quiz", "viva", "assessment", "evaluation").any(rawInput::contains) -> PreEventProfile.EXAM
            listOf("appointment", "doctor", "dentist", "hospital", "clinic", "consultation").any(rawInput::contains) -> PreEventProfile.APPOINTMENT
            listOf("flight", "train", "bus", "cab", "pickup", "departure", "airport", "station").any(rawInput::contains) -> PreEventProfile.TRAVEL
            listOf("portal", "cutoff", "closes", "closure", "registration", "deadline").any(rawInput::contains) -> PreEventProfile.DEADLINE_EVENT
            listOf("call", "phone", "ring", "buzz").any(rawInput::contains) -> PreEventProfile.CALL
            else -> PreEventProfile.MEETING
        }
    }

    private fun inferEventDurationMinutes(rawInput: String, preEventProfile: PreEventProfile?): Int? {
        Regex("""\b(\d+)\s*(hours?|hrs?)\b""").find(rawInput)?.let { return it.groupValues[1].toInt() * 60 }
        Regex("""\b(\d+)\s*(minutes?|mins?)\b""").find(rawInput)?.let { return it.groupValues[1].toInt() }
        return when (preEventProfile) {
            PreEventProfile.APPOINTMENT -> 30
            PreEventProfile.EXAM -> 180
            PreEventProfile.CALL -> 15
            PreEventProfile.MEETING -> 60
            else -> null
        }
    }

    private fun inferTitle(rawInput: String): String {
        return rawInput
            .substringBefore(" by ")
            .substringBefore(" before ")
            .substringBefore(" at ")
            .take(80)
            .trim()
            .ifBlank { "Untitled task" }
    }

    private fun inferDerivedTaskTitle(rawInput: String): String {
        return rawInput.substringBefore(" for ").trim().ifBlank { inferTitle(rawInput) }
    }

    private fun inferDerivedEventTitle(rawInput: String): String {
        return rawInput.substringAfter(" for ", missingDelimiterValue = rawInput).trim().ifBlank { inferTitle(rawInput) }
    }

    private fun containsVagueTemporalExpression(rawInput: String): Boolean {
        return listOf("soon", "later", "someday", "eventually").any(rawInput::contains)
    }

    private fun extractVagueTemporalExpression(rawInput: String): String? {
        return listOf("soon", "later", "someday", "eventually").firstOrNull(rawInput::contains)
    }

    private fun hasAnyTemporalSignal(rawInput: String): Boolean {
        val keywordSignal = listOf(
            "today", "tomorrow", "tonight", "morning", "afternoon", "evening", "night",
            "this week", "next week", "eod", "end of day", "midnight",
        ).any(rawInput::contains)
        return keywordSignal ||
            DURATION_REGEX.containsMatchIn(rawInput) ||
            TIME_WITH_AM_PM_REGEX.containsMatchIn(rawInput) ||
            BARE_TIME_REGEX.containsMatchIn(rawInput) ||
            DMY_DATE_REGEX.containsMatchIn(rawInput) ||
            ORDINAL_MONTH_REGEX.containsMatchIn(rawInput) ||
            MONTH_ORDINAL_REGEX.containsMatchIn(rawInput) ||
            DayOfWeek.entries.any { Regex("""\b${it.name.lowercase(Locale.ROOT)}\b""").containsMatchIn(rawInput) }
    }

    private fun hasMorningContext(rawInput: String): Boolean {
        return listOf("morning", "subah", "class", "lecture", "gym", "breakfast").any(rawInput::contains)
    }

    private fun isMorningOrAfternoonPassed(rawInput: String, nowIst: ZonedDateTime): Boolean {
        val nowTime = nowIst.toLocalTime()
        val morningRequested = "this morning" in rawInput || "today morning" in rawInput
        val afternoonRequested = "this afternoon" in rawInput || "today afternoon" in rawInput
        return (morningRequested && nowTime.isAfter(LocalTime.NOON)) ||
            (afternoonRequested && nowTime.isAfter(LocalTime.of(14, 0)))
    }

    private fun defaultTimeForContext(
        rawInput: String,
        primitiveType: PrimitiveType,
        preEventProfile: PreEventProfile?,
    ): LocalTime {
        return when {
            "eod" in rawInput || "end of day" in rawInput || "before midnight" in rawInput || "midnight" in rawInput -> LocalTime.of(23, 59)
            preEventProfile == PreEventProfile.EXAM -> LocalTime.of(9, 0)
            primitiveType == PrimitiveType.EVENT || primitiveType == PrimitiveType.DERIVED_TASK_EVENT -> LocalTime.of(9, 0)
            else -> LocalTime.of(17, 0)
        }
    }

    private fun monthFromToken(token: String): Month? {
        return when (token.lowercase(Locale.ROOT).take(3)) {
            "jan" -> Month.JANUARY
            "feb" -> Month.FEBRUARY
            "mar" -> Month.MARCH
            "apr" -> Month.APRIL
            "may" -> Month.MAY
            "jun" -> Month.JUNE
            "jul" -> Month.JULY
            "aug" -> Month.AUGUST
            "sep" -> Month.SEPTEMBER
            "oct" -> Month.OCTOBER
            "nov" -> Month.NOVEMBER
            "dec" -> Month.DECEMBER
            else -> null
        }
    }
}

@Serializable
private data class LlmTaskSplit(
    val first_title: String,
    val first_effort_minutes: Int,
    val second_title: String,
    val second_effort_minutes: Int,
)

/**
 * Elevates [SPLIT_TASK][com.orka.core.model.InteractionType.SPLIT_TASK] beyond a mechanical
 * "divide the effort in half" by asking Gemma for a breakdown that's actually specific to the
 * task at hand (e.g. "Prepare quarterly report" -> "Gather data and outline" / "Write and format
 * final report" rather than generic "Part 1"/"Part 2"). Returns null whenever the model isn't
 * ready or the response can't be parsed — callers fall back to the mechanical split, exactly
 * like [DefaultTaskParser] falls back to regex parsing.
 */
class DefaultTaskSplitter @Inject constructor(
    private val modelInstaller: ModelInstaller,
) : TaskSplitter {
    override suspend fun suggestSplit(task: Task): TaskSplitSuggestion? {
        val modelFile = resolveReadyModelFile(modelInstaller.installBundledModelIfAvailable()) ?: return null

        val prompt = """
            You are a task-planning assistant. Split ONE task into exactly two smaller, concrete
            sub-tasks that together accomplish the original task. Respond with ONLY a JSON object,
            no explanation, no markdown.

            TASK: "${task.title}"
            DETAILS: "${task.description ?: task.title}"
            CATEGORY: ${task.category.name}
            TOTAL ESTIMATED EFFORT: ${task.estimatedEffortMinutes} minutes

            Fields:
            - "first_title": short, concrete, actionable title for the first half of the work
            - "first_effort_minutes": integer, this half's share of the total effort
            - "second_title": short, concrete, actionable title for the second half of the work
            - "second_effort_minutes": integer, this half's share of the total effort
            first_effort_minutes + second_effort_minutes should add up to approximately ${task.estimatedEffortMinutes}.

            Example: TASK "Prepare quarterly report" (180 minutes, PROFESSIONAL)
            Output: {"first_title":"Gather data and outline report","first_effort_minutes":90,"second_title":"Write and format final report","second_effort_minutes":90}

            Output JSON:
        """.trimIndent()

        return try {
            val engine = GemmaEngineHolder.getOrCreate(modelFile.absolutePath)
            val response = engine.createConversation().use { conversation ->
                withContext(Dispatchers.Default) {
                    conversation.sendMessage(prompt)
                }
            }
            parseSplitResponse(response.asText(), task.estimatedEffortMinutes)
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private fun parseSplitResponse(response: String, totalEffort: Int): TaskSplitSuggestion? {
        return try {
            val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<LlmTaskSplit>(cleanJson(response))
            if (parsed.first_title.isBlank() || parsed.second_title.isBlank()) return null
            if (parsed.first_effort_minutes <= 0 || parsed.second_effort_minutes <= 0) return null
            // Sanity-check the model didn't wildly invent a total unrelated to the real one
            // (e.g. hallucinating minutes that don't reflect the task's actual scope).
            val suggestedTotal = parsed.first_effort_minutes + parsed.second_effort_minutes
            if (suggestedTotal < totalEffort / 4 || suggestedTotal > totalEffort * 4) return null

            TaskSplitSuggestion(
                firstTitle = parsed.first_title.take(80),
                firstEffortMinutes = parsed.first_effort_minutes,
                secondTitle = parsed.second_title.take(80),
                secondEffortMinutes = parsed.second_effort_minutes,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@Singleton
class CompanionKitModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : ModelInstaller {
    private val modelDirectory = File(context.filesDir, "model").apply { mkdirs() }
    private val installMutex = Mutex()
    private val installerScope = CoroutineScope(Dispatchers.IO)
    private val state = MutableStateFlow(ModelInstallState())

    init {
        installerScope.launch {
            installBundledModelIfAvailable()
        }
    }

    override fun observeState(): Flow<ModelInstallState> = state.asStateFlow()

    /**
     * Detects a model made available on the device without repackaging it into the APK:
     * either a one-time `adb push <model> $DEVICE_PUSHED_MODEL_PATH`, or a prior
     * [installFromCompanionKit] copy already sitting in app-private storage. The pushed file
     * is used in place directly (no copy) since it's already 3+ GB — duplicating it into app
     * storage would waste that much disk again for no benefit.
     */
    override suspend fun installBundledModelIfAvailable(): ModelInstallState {
        return withContext(Dispatchers.IO) {
            installMutex.withLock {
                val importedCopy = installedModelFile()
                if (importedCopy.exists() && importedCopy.length() > MODEL_MIN_VALID_SIZE_BYTES) {
                    return@withLock ModelInstallState(
                        availability = ModelAvailability.READY,
                        modelPath = importedCopy.absolutePath,
                        sizeBytes = importedCopy.length(),
                        message = "Imported model ready.",
                    ).also { state.value = it }
                }

                val pushed = pushedModelFile()
                if (!pushed.exists() || pushed.length() <= MODEL_MIN_VALID_SIZE_BYTES) {
                    return@withLock ModelInstallState(
                        availability = ModelAvailability.NOT_INSTALLED,
                        message = "Push the model once via: adb push $MODEL_FILE_NAME ${pushed.path}",
                    ).also { state.value = it }
                }

                return@withLock ModelInstallState(
                    availability = ModelAvailability.READY,
                    modelPath = pushed.absolutePath,
                    sizeBytes = pushed.length(),
                    message = "Model detected at ${pushed.path}.",
                ).also { state.value = it }
            }
        }
    }

    override suspend fun installFromCompanionKit(
        sourcePath: String,
        expectedChecksum: String?,
    ): ModelInstallState {
        return withContext(Dispatchers.IO) {
            installMutex.withLock {
                state.value = ModelInstallState(availability = ModelAvailability.IMPORTING, message = "Importing model kit...")
                val source = File(sourcePath)
                if (!source.exists()) {
                    return@withLock updateFailure("Model file was not found at $sourcePath")
                }

                return@withLock runCatching {
                    val target = installedModelFile()
                    GemmaEngineHolder.reset()
                    source.copyTo(target, overwrite = true)
                    val checksum = sha256(target)
                    if (expectedChecksum != null && checksum != expectedChecksum) {
                        target.delete()
                        updateFailure("Checksum mismatch for imported model.")
                    } else {
                        ModelInstallState(
                            availability = ModelAvailability.READY,
                            modelPath = target.absolutePath,
                            checksum = checksum,
                            sizeBytes = target.length(),
                            message = "Model ready.",
                        ).also { state.value = it }
                    }
                }.getOrElse { throwable ->
                    updateFailure(throwable.message ?: "Failed to import model kit.")
                }
            }
        }
    }

    override suspend fun reset() {
        GemmaEngineHolder.reset()
        installedModelFile().delete()
        state.value = ModelInstallState()
    }

    private fun installedModelFile(): File = File(modelDirectory, MODEL_FILE_NAME)

    private fun pushedModelFile(): File = File(DEVICE_PUSHED_MODEL_DIR, MODEL_FILE_NAME)

    private fun updateFailure(message: String): ModelInstallState {
        return ModelInstallState(
            availability = ModelAvailability.FAILED,
            message = message,
        ).also { state.value = it }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ParserBindingsModule {
    @Binds
    abstract fun bindTaskParser(impl: DefaultTaskParser): TaskParser

    @Binds
    abstract fun bindTaskDraftValidator(impl: DefaultTaskDraftValidator): TaskDraftValidator

    @Binds
    abstract fun bindModelInstaller(impl: CompanionKitModelInstaller): ModelInstaller

    @Binds
    abstract fun bindTaskSplitter(impl: DefaultTaskSplitter): TaskSplitter
}

@Module
@InstallIn(SingletonComponent::class)
object ParserModule {
    @Provides
    @Singleton
    fun provideDefaultZoneId(): ZoneId = ZoneId.systemDefault()
}
