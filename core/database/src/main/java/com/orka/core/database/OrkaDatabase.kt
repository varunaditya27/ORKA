package com.orka.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.orka.core.model.ClarificationReason
import com.orka.core.model.BehaviorProfile
import com.orka.core.model.InteractionEvent
import com.orka.core.model.InteractionType
import com.orka.core.model.PreEventProfile
import com.orka.core.model.PrimitiveType
import com.orka.core.model.ReminderEvent
import com.orka.core.model.ReminderStatus
import com.orka.core.model.SchedulerMode
import com.orka.core.model.Task
import com.orka.core.model.TaskCategory
import com.orka.core.model.TaskStatus
import java.time.Instant
import kotlinx.coroutines.flow.Flow

private const val ENTRY_DELIMITER = ','
private const val KEY_VALUE_DELIMITER = ':'
private const val ESCAPE_CHARACTER = '\\'

class OrkaTypeConverters {
    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun fromTaskCategory(value: TaskCategory): String = value.name

    @TypeConverter
    fun toTaskCategory(value: String): TaskCategory = TaskCategory.valueOf(value)

    @TypeConverter
    fun fromTaskStatus(value: TaskStatus): String = value.name

    @TypeConverter
    fun toTaskStatus(value: String): TaskStatus = TaskStatus.valueOf(value)

    @TypeConverter
    fun fromReminderStatus(value: ReminderStatus): String = value.name

    @TypeConverter
    fun toReminderStatus(value: String): ReminderStatus = ReminderStatus.valueOf(value)

    @TypeConverter
    fun fromSchedulerMode(value: SchedulerMode): String = value.name

    @TypeConverter
    fun toSchedulerMode(value: String): SchedulerMode = SchedulerMode.valueOf(value)

    @TypeConverter
    fun fromInteractionType(value: InteractionType): String = value.name

    @TypeConverter
    fun toInteractionType(value: String): InteractionType = InteractionType.valueOf(value)

    @TypeConverter
    fun fromPrimitiveType(value: PrimitiveType): String = value.name

    @TypeConverter
    fun toPrimitiveType(value: String): PrimitiveType =
        runCatching { PrimitiveType.valueOf(value) }.getOrElse { PrimitiveType.TASK }

    @TypeConverter
    fun fromPreEventProfile(value: PreEventProfile?): String? = value?.name

    @TypeConverter
    fun toPreEventProfile(value: String?): PreEventProfile? = value?.let {
        runCatching { PreEventProfile.valueOf(it) }.getOrNull()
    }

    @TypeConverter
    fun fromClarificationReason(value: ClarificationReason?): String? = value?.name

    @TypeConverter
    fun toClarificationReason(value: String?): ClarificationReason? = value?.let {
        runCatching { ClarificationReason.valueOf(it) }.getOrNull()
    }

    @TypeConverter
    fun fromStringSet(value: Set<String>): String = encodeStringSet(value)

    @TypeConverter
    fun toStringSet(value: String): Set<String> = decodeStringSet(value)

    @TypeConverter
    fun fromStringMap(value: Map<String, String>): String = encodeStringMap(value)

    @TypeConverter
    fun toStringMap(value: String): Map<String, String> = decodeStringMap(value)

    @TypeConverter
    fun fromFloatMap(value: Map<TaskCategory, Float>): String = encodeFloatMap(value)

    @TypeConverter
    fun toFloatMap(value: String): Map<TaskCategory, Float> = decodeFloatMap(value)
}

private fun encodeStringSet(value: Set<String>): String = value
    .toList()
    .sorted()
    .joinToString(separator = ENTRY_DELIMITER.toString()) { it.escapeForStorage() }

private fun decodeStringSet(value: String): Set<String> = when {
    value.isBlank() -> emptySet()
    else -> splitEscaped(value, ENTRY_DELIMITER)
        .map(String::unescapeFromStorage)
        .toSet()
}

private fun encodeStringMap(value: Map<String, String>): String = value
    .toSortedMap()
    .entries
    .joinToString(separator = ENTRY_DELIMITER.toString()) { (key, mapValue) ->
        "${key.escapeForStorage()}$KEY_VALUE_DELIMITER${mapValue.escapeForStorage()}"
    }

private fun decodeStringMap(value: String): Map<String, String> = when {
    value.isBlank() -> emptyMap()
    else -> splitEscaped(value, ENTRY_DELIMITER).associate { entry ->
        val parts = splitEscaped(entry, KEY_VALUE_DELIMITER)
        require(parts.size == 2) { "Malformed encoded map entry: $entry" }
        parts[0].unescapeFromStorage() to parts[1].unescapeFromStorage()
    }
}

private fun encodeFloatMap(value: Map<TaskCategory, Float>): String = value
    .entries
    .sortedBy { it.key.name }
    .joinToString(separator = ENTRY_DELIMITER.toString()) { (key, mapValue) ->
        "${key.name}$KEY_VALUE_DELIMITER${mapValue}"
    }

private fun decodeFloatMap(value: String): Map<TaskCategory, Float> = when {
    value.isBlank() -> emptyMap()
    else -> splitEscaped(value, ENTRY_DELIMITER).associate { entry ->
        val parts = splitEscaped(entry, KEY_VALUE_DELIMITER)
        require(parts.size == 2) { "Malformed encoded category map entry: $entry" }
        TaskCategory.valueOf(parts[0]) to parts[1].toFloat()
    }
}

private fun String.escapeForStorage(): String = buildString(length) {
    for (character in this@escapeForStorage) {
        if (
            character == ESCAPE_CHARACTER ||
            character == ENTRY_DELIMITER ||
            character == KEY_VALUE_DELIMITER
        ) {
            append(ESCAPE_CHARACTER)
        }
        append(character)
    }
}

private fun String.unescapeFromStorage(): String = buildString(length) {
    var escaped = false
    for (character in this@unescapeFromStorage) {
        if (escaped) {
            append(character)
            escaped = false
        } else if (character == ESCAPE_CHARACTER) {
            escaped = true
        } else {
            append(character)
        }
    }
    require(!escaped) { "Malformed escaped value: dangling escape character" }
}

private fun splitEscaped(value: String, delimiter: Char): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false

    for (character in value) {
        when {
            escaped -> {
                current.append(character)
                escaped = false
            }
            character == ESCAPE_CHARACTER -> {
                current.append(character)
                escaped = true
            }
            character == delimiter -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(character)
        }
    }

    require(!escaped) { "Malformed encoded value: dangling escape character" }
    parts += current.toString()
    return parts
}

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val rawInput: String,
    val title: String,
    val description: String?,
    val deadline: Instant,
    val deadlineConfidence: Float,
    val primitiveType: PrimitiveType,
    val eventStartTime: Instant?,
    val eventDurationMinutes: Int?,
    val preEventProfile: PreEventProfile?,
    val linkedEntityId: String?,
    val clarificationNeeded: Boolean,
    val clarificationReason: ClarificationReason?,
    val resolvedTimezone: String,
    val temporalExpressionRaw: String?,
    val category: TaskCategory,
    val estimatedEffortMinutes: Int,
    val urgencyScore: Float,
    val status: TaskStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val userCorrectedFields: Set<String>,
)

@Entity(tableName = "reminders")
data class ReminderEventEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val scheduledTime: Instant,
    val actualFireTime: Instant?,
    val sequenceNumber: Int,
    val alarmManagerId: Int,
    val primitiveType: PrimitiveType,
    val preEventProfile: PreEventProfile?,
    val minutesBeforeAnchor: Long?,
    val reminderLabel: String?,
    val status: ReminderStatus,
    val schedulerMode: SchedulerMode,
)

@Entity(tableName = "interactions")
data class InteractionEventEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val reminderId: String?,
    val type: InteractionType,
    val timestamp: Instant,
    val responseDelaySeconds: Long,
    val escalationApplied: Boolean,
    val metadata: Map<String, String>,
)

@Entity(tableName = "behavior_profile")
data class BehaviorProfileEntity(
    @PrimaryKey val id: Int = 0,
    val productiveStartHour: Int,
    val productiveEndHour: Int,
    val averageResponseMinutes: Int,
    val snoozeRate: Float,
    val totalInteractions: Int,
    val totalCompletions: Int,
    val categorySnoozeRates: Map<TaskCategory, Float>,
    val categoryCompletionRates: Map<TaskCategory, Float>,
)

@Entity(tableName = "alarm_registry")
data class AlarmRegistryEntity(
    @PrimaryKey val reminderId: String,
    val taskId: String,
    val scheduledTime: Instant,
    val alarmManagerId: Int,
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE status NOT IN ('COMPLETED', 'DISMISSED') ORDER BY urgencyScore DESC, deadline ASC")
    fun observeActiveTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status IN ('COMPLETED', 'DISMISSED') ORDER BY updatedAt DESC")
    fun observeArchivedTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    fun observeTask(taskId: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    suspend fun getTask(taskId: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("UPDATE tasks SET status = :status, completedAt = :completedAt, updatedAt = :updatedAt WHERE id = :taskId")
    suspend fun updateStatus(taskId: String, status: TaskStatus, completedAt: Instant?, updatedAt: Instant)

    // urgencyScore is pinned to 5 (UrgencyCalculator's ceiling) here: it drives the Tasks list
    // sort order, and a task's stored score — computed once at creation against the deadline
    // that was still in the future then — would otherwise stay stale (understating urgency)
    // once that same task actually goes overdue.
    @Query("UPDATE tasks SET status = 'OVERDUE', urgencyScore = 5.0, updatedAt = :now WHERE status = 'PENDING' AND deadline < :now")
    suspend fun markOverdueTasks(now: Instant)

    @Query("SELECT * FROM tasks")
    suspend fun getAllTasks(): List<TaskEntity>
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE taskId = :taskId ORDER BY scheduledTime ASC")
    fun observeForTask(taskId: String): Flow<List<ReminderEventEntity>>

    @Query("SELECT * FROM reminders WHERE status = 'SCHEDULED' ORDER BY scheduledTime ASC LIMIT 1")
    fun observeNextReminder(): Flow<ReminderEventEntity?>

    @Query("SELECT * FROM reminders WHERE status = 'FIRED' ORDER BY actualFireTime DESC LIMIT 1")
    fun observeLastTriggeredReminder(): Flow<ReminderEventEntity?>

    @Query("SELECT * FROM reminders WHERE id = :reminderId")
    suspend fun getReminder(reminderId: String): ReminderEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<ReminderEventEntity>)

    @Query("DELETE FROM reminders WHERE taskId = :taskId AND status = 'SCHEDULED'")
    suspend fun deleteScheduledForTask(taskId: String)

    @Query("UPDATE reminders SET actualFireTime = :firedAt, status = 'FIRED' WHERE id = :reminderId")
    suspend fun markDelivered(reminderId: String, firedAt: Instant)

    @Query("SELECT * FROM reminders WHERE status = 'SCHEDULED'")
    suspend fun getAllScheduled(): List<ReminderEventEntity>
}

@Dao
interface InteractionDao {
    @Query("SELECT * FROM interactions WHERE taskId = :taskId ORDER BY timestamp DESC")
    fun observeForTask(taskId: String): Flow<List<InteractionEventEntity>>

    @Query("SELECT * FROM interactions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<InteractionEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: InteractionEventEntity)

    @Query("SELECT * FROM interactions")
    suspend fun getAllInteractions(): List<InteractionEventEntity>
}

@Dao
interface BehaviorProfileDao {
    @Query("SELECT * FROM behavior_profile WHERE id = 0")
    fun observe(): Flow<BehaviorProfileEntity?>

    @Query("SELECT * FROM behavior_profile WHERE id = 0")
    suspend fun get(): BehaviorProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: BehaviorProfileEntity)
}

@Dao
interface AlarmRegistryDao {
    @Query("SELECT * FROM alarm_registry")
    suspend fun getAll(): List<AlarmRegistryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(alarms: List<AlarmRegistryEntity>)

    @Query("DELETE FROM alarm_registry WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM alarm_registry WHERE reminderId = :reminderId")
    suspend fun deleteByReminder(reminderId: String)
}

@Database(
    entities = [
        TaskEntity::class,
        ReminderEventEntity::class,
        InteractionEventEntity::class,
        BehaviorProfileEntity::class,
        AlarmRegistryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(OrkaTypeConverters::class)
abstract class OrkaDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun reminderDao(): ReminderDao
    abstract fun interactionDao(): InteractionDao
    abstract fun behaviorProfileDao(): BehaviorProfileDao
    abstract fun alarmRegistryDao(): AlarmRegistryDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN primitiveType TEXT NOT NULL DEFAULT 'TASK'")
        db.execSQL("ALTER TABLE tasks ADD COLUMN eventStartTime INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN eventDurationMinutes INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN preEventProfile TEXT")
        db.execSQL("ALTER TABLE tasks ADD COLUMN linkedEntityId TEXT")
        db.execSQL("ALTER TABLE tasks ADD COLUMN clarificationNeeded INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN clarificationReason TEXT")
        db.execSQL("ALTER TABLE tasks ADD COLUMN resolvedTimezone TEXT NOT NULL DEFAULT 'Asia/Kolkata'")
        db.execSQL("ALTER TABLE tasks ADD COLUMN temporalExpressionRaw TEXT")

        db.execSQL("ALTER TABLE reminders ADD COLUMN primitiveType TEXT NOT NULL DEFAULT 'TASK'")
        db.execSQL("ALTER TABLE reminders ADD COLUMN preEventProfile TEXT")
        db.execSQL("ALTER TABLE reminders ADD COLUMN minutesBeforeAnchor INTEGER")
        db.execSQL("ALTER TABLE reminders ADD COLUMN reminderLabel TEXT")
    }
}

fun TaskEntity.asExternalModel(): Task = Task(
    id = id,
    rawInput = rawInput,
    title = title,
    description = description,
    deadline = deadline,
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
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    userCorrectedFields = userCorrectedFields,
)

fun Task.asEntity(): TaskEntity = TaskEntity(
    id = id,
    rawInput = rawInput,
    title = title,
    description = description,
    deadline = deadline,
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
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    userCorrectedFields = userCorrectedFields,
)

fun ReminderEventEntity.asExternalModel(): ReminderEvent = ReminderEvent(
    id = id,
    taskId = taskId,
    scheduledTime = scheduledTime,
    actualFireTime = actualFireTime,
    sequenceNumber = sequenceNumber,
    alarmManagerId = alarmManagerId,
    primitiveType = primitiveType,
    preEventProfile = preEventProfile,
    minutesBeforeAnchor = minutesBeforeAnchor,
    reminderLabel = reminderLabel,
    status = status,
    schedulerMode = schedulerMode,
)

fun ReminderEvent.asEntity(): ReminderEventEntity = ReminderEventEntity(
    id = id,
    taskId = taskId,
    scheduledTime = scheduledTime,
    actualFireTime = actualFireTime,
    sequenceNumber = sequenceNumber,
    alarmManagerId = alarmManagerId,
    primitiveType = primitiveType,
    preEventProfile = preEventProfile,
    minutesBeforeAnchor = minutesBeforeAnchor,
    reminderLabel = reminderLabel,
    status = status,
    schedulerMode = schedulerMode,
)

fun InteractionEventEntity.asExternalModel(): InteractionEvent = InteractionEvent(
    id = id,
    taskId = taskId,
    reminderId = reminderId,
    type = type,
    timestamp = timestamp,
    responseDelaySeconds = responseDelaySeconds,
    escalationApplied = escalationApplied,
    metadata = metadata,
)

fun InteractionEvent.asEntity(): InteractionEventEntity = InteractionEventEntity(
    id = id,
    taskId = taskId,
    reminderId = reminderId,
    type = type,
    timestamp = timestamp,
    responseDelaySeconds = responseDelaySeconds,
    escalationApplied = escalationApplied,
    metadata = metadata,
)

fun BehaviorProfileEntity.asExternalModel(): BehaviorProfile = BehaviorProfile(
    productiveStartHour = productiveStartHour,
    productiveEndHour = productiveEndHour,
    averageResponseMinutes = averageResponseMinutes,
    snoozeRate = snoozeRate,
    totalInteractions = totalInteractions,
    totalCompletions = totalCompletions,
    categorySnoozeRates = categorySnoozeRates,
    categoryCompletionRates = categoryCompletionRates,
)

fun BehaviorProfile.asEntity(): BehaviorProfileEntity = BehaviorProfileEntity(
    productiveStartHour = productiveStartHour,
    productiveEndHour = productiveEndHour,
    averageResponseMinutes = averageResponseMinutes,
    snoozeRate = snoozeRate,
    totalInteractions = totalInteractions,
    totalCompletions = totalCompletions,
    categorySnoozeRates = categorySnoozeRates,
    categoryCompletionRates = categoryCompletionRates,
)
