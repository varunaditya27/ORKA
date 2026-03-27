package com.orka.data.parser

import android.content.Context
import com.orka.core.common.UrgencyCalculator
import com.orka.core.model.ModelAvailability
import com.orka.core.model.ModelInstallState
import com.orka.core.model.ModelInstaller
import com.orka.core.model.ParseMode
import com.orka.core.model.ParserContext
import com.orka.core.model.TaskCategory
import com.orka.core.model.TaskDraft
import com.orka.core.model.TaskDraftValidationResult
import com.orka.core.model.TaskDraftValidator
import com.orka.core.model.TaskParseResult
import com.orka.core.model.TaskParser
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MODEL_FILE_NAME = "gemma-2b-int4.gguf"

class DefaultTaskDraftValidator @Inject constructor() : TaskDraftValidator {
    override fun validate(draft: TaskDraft): TaskDraftValidationResult {
        val errors = buildList {
            if (draft.title.isBlank()) add("Task title is required.")
            if (draft.deadline == null) add("Deadline is required.")
            if (draft.estimatedEffortMinutes <= 0) add("Estimated effort must be positive.")
        }
        return TaskDraftValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }
}

class DefaultTaskParser @Inject constructor(
    @ApplicationContext private val context: Context,
) : TaskParser {
    override suspend fun parse(rawInput: String, context: ParserContext): TaskParseResult {
        val modelInstalled = File(this.context.filesDir, "model/$MODEL_FILE_NAME").exists()
        val draft = fallbackParse(
            rawInput = rawInput,
            context = context,
            parseMode = if (modelInstalled) ParseMode.GEMMA else ParseMode.FALLBACK,
        )
        return TaskParseResult(
            draft = draft,
            issues = if (draft.lowConfidenceFields.isEmpty()) {
                emptyList()
            } else {
                listOf("Review highlighted fields before confirming.")
            },
        )
    }

    private fun fallbackParse(
        rawInput: String,
        context: ParserContext,
        parseMode: ParseMode,
    ): TaskDraft {
        val normalized = rawInput.trim()
        val deadline = extractDeadline(normalized, context.now, context.zoneId)
        val category = inferCategory(normalized)
        val effort = inferEffort(normalized)
        val confidence = if (deadline != null) 0.82f else 0.35f
        val lowConfidence = buildSet {
            if (deadline == null) add("deadline")
            if (normalized.length < 6) add("title")
        }

        return TaskDraft(
            rawInput = normalized,
            title = normalized.substringBefore(" by ").substringBefore(" before ").take(80).ifBlank { "Untitled task" },
            description = normalized,
            deadline = deadline,
            deadlineConfidence = confidence,
            category = category,
            estimatedEffortMinutes = effort,
            urgencyScore = deadline?.let { UrgencyCalculator.urgencyScore(it, context.now.toInstant(), effort) } ?: 1f,
            lowConfidenceFields = lowConfidence,
            parseMode = parseMode,
        )
    }

    private fun inferCategory(rawInput: String): TaskCategory {
        val value = rawInput.lowercase()
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
        val value = rawInput.lowercase()
        return when {
            listOf("quick", "call", "reply", "email").any(value::contains) -> 20
            listOf("report", "assignment", "prepare", "presentation").any(value::contains) -> 120
            listOf("budget", "proposal", "research").any(value::contains) -> 180
            else -> 45
        }
    }

    private fun extractDeadline(rawInput: String, now: ZonedDateTime, zoneId: ZoneId): Instant? {
        val lowercase = rawInput.lowercase()
        val time = when {
            "11:59" in lowercase || "midnight" in lowercase -> LocalTime.of(23, 59)
            "evening" in lowercase -> LocalTime.of(18, 0)
            "morning" in lowercase -> LocalTime.of(9, 0)
            "afternoon" in lowercase -> LocalTime.of(15, 0)
            else -> LocalTime.of(17, 0)
        }

        if ("today" in lowercase) {
            return ZonedDateTime.of(now.toLocalDate(), time, zoneId).toInstant()
        }
        if ("tomorrow" in lowercase) {
            return ZonedDateTime.of(now.toLocalDate().plusDays(1), time, zoneId).toInstant()
        }

        DayOfWeek.entries.firstOrNull { lowercase.contains(it.name.lowercase()) }?.let { day ->
            val nextDate = if (lowercase.contains("this ${day.name.lowercase()}")) {
                now.toLocalDate().with(TemporalAdjusters.nextOrSame(day))
            } else {
                now.toLocalDate().with(TemporalAdjusters.next(day))
            }
            return ZonedDateTime.of(nextDate, time, zoneId).toInstant()
        }

        Month.entries.firstOrNull { lowercase.contains(it.name.lowercase()) }?.let { month ->
            val dayMatch = Regex("""(\d{1,2})(st|nd|rd|th)?""").find(lowercase) ?: return@let
            val day = dayMatch.groupValues[1].toInt()
            val year = if (month.value < now.monthValue) now.year + 1 else now.year
            return ZonedDateTime.of(LocalDate.of(year, month, day), time, zoneId).toInstant()
        }

        Regex("""(\d{1,2})/(\d{1,2})/(\d{2,4})""").find(lowercase)?.let { match ->
            val day = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val year = match.groupValues[3].toInt().let { if (it < 100) it + 2000 else it }
            return ZonedDateTime.of(LocalDate.of(year, month, day), time, zoneId).toInstant()
        }

        return null
    }
}

@Singleton
class CompanionKitModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : ModelInstaller {
    private val modelDirectory = File(context.filesDir, "model").apply { mkdirs() }
    private val state = MutableStateFlow(
        if (installedModelFile().exists()) {
            ModelInstallState(
                availability = ModelAvailability.READY,
                modelPath = installedModelFile().absolutePath,
                sizeBytes = installedModelFile().length(),
            )
        } else {
            ModelInstallState()
        },
    )

    override fun observeState(): Flow<ModelInstallState> = state.asStateFlow()

    override suspend fun installFromCompanionKit(
        sourcePath: String,
        expectedChecksum: String?,
    ): ModelInstallState {
        state.value = ModelInstallState(availability = ModelAvailability.IMPORTING, message = "Importing model kit...")
        val source = File(sourcePath)
        if (!source.exists()) {
            return updateFailure("Model file was not found at $sourcePath")
        }

        return runCatching {
            val target = installedModelFile()
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

    override suspend fun reset() {
        installedModelFile().delete()
        state.value = ModelInstallState()
    }

    private fun installedModelFile(): File = File(modelDirectory, MODEL_FILE_NAME)

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
}

@Module
@InstallIn(SingletonComponent::class)
object ParserModule {
    @Provides
    @Singleton
    fun provideDefaultZoneId(): ZoneId = ZoneId.systemDefault()
}
