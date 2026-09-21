package com.orka.data.behavior

import android.content.Context
import androidx.room.Room
import com.orka.core.database.BehaviorProfileDao
import com.orka.core.database.MIGRATION_1_2
import com.orka.core.database.MIGRATION_2_3
import com.orka.core.database.MIGRATION_3_4
import com.orka.core.database.OrkaDatabase
import com.orka.core.database.ReminderDao
import com.orka.core.database.TaskDao
import com.orka.core.database.asEntity
import com.orka.core.database.asExternalModel
import com.orka.core.model.BehaviorProfile
import com.orka.core.model.BehaviorProfileRepository
import com.orka.core.model.InteractionEvent
import com.orka.core.model.Task
import com.orka.core.model.TaskMetrics
import com.orka.core.model.TaskRepository
import com.orka.core.model.TaskStatus
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class RoomTaskRepository @Inject constructor(
    private val taskDao: TaskDao,
    private val reminderDao: ReminderDao,
    private val interactionDao: com.orka.core.database.InteractionDao,
) : TaskRepository {
    override fun observeActiveTasks(): Flow<List<Task>> = taskDao.observeActiveTasks().map { list -> list.map { it.asExternalModel() } }

    override fun observeArchivedTasks(): Flow<List<Task>> = taskDao.observeArchivedTasks().map { list -> list.map { it.asExternalModel() } }

    override fun observeTask(taskId: String): Flow<Task?> = taskDao.observeTask(taskId).map { it?.asExternalModel() }

    override fun observeTaskMetrics(): Flow<TaskMetrics> = combine(
        taskDao.observeActiveTasks(),
        taskDao.observeArchivedTasks(),
    ) { active, archived ->
        val completed = archived.count { it.status == TaskStatus.COMPLETED }
        val dismissed = archived.count { it.status == TaskStatus.DISMISSED }
        val overdue = active.count { it.status == TaskStatus.OVERDUE }
        val onTime = archived.count {
            val completedAt = it.completedAt
            it.status == TaskStatus.COMPLETED &&
                completedAt != null &&
                !completedAt.isAfter(it.deadline)
        }
        TaskMetrics(
            completedCount = completed,
            dismissedCount = dismissed,
            overdueCount = overdue,
            onTimeRatio = if (completed == 0) 0f else onTime.toFloat() / completed.toFloat(),
        )
    }

    override fun observeReminders(taskId: String) = reminderDao.observeForTask(taskId).map { list -> list.map { it.asExternalModel() } }

    override fun observeInteractions(taskId: String) = interactionDao.observeForTask(taskId).map { list -> list.map { it.asExternalModel() } }

    override fun observeNextReminder() = reminderDao.observeNextReminder().map { it?.asExternalModel() }

    override fun observeLastTriggeredReminder() = reminderDao.observeLastTriggeredReminder().map { it?.asExternalModel() }

    override suspend fun getTask(taskId: String): Task? = taskDao.getTask(taskId)?.asExternalModel()

    override suspend fun getReminder(reminderId: String) = reminderDao.getReminder(reminderId)?.asExternalModel()

    override suspend fun getAllScheduledReminders() = reminderDao.getAllScheduled().map { it.asExternalModel() }

    override suspend fun upsertTask(task: Task): Task {
        taskDao.upsert(task.asEntity())
        return task
    }

    override suspend fun replaceReminders(taskId: String, reminders: List<com.orka.core.model.ReminderEvent>) {
        reminderDao.replaceScheduled(taskId, reminders.map { it.asEntity() })
    }

    override suspend fun markReminderDelivered(reminderId: String, firedAt: Instant) {
        reminderDao.markDelivered(reminderId, firedAt)
    }

    override suspend fun recordInteraction(event: InteractionEvent) {
        interactionDao.insert(event.asEntity())
    }

    override suspend fun updateTaskStatus(taskId: String, status: TaskStatus, completedAt: Instant?) {
        taskDao.updateStatus(taskId, status, completedAt, Instant.now())
    }

    override suspend fun markOverdueTasks(now: Instant) {
        taskDao.markOverdueTasks(now)
    }
}

@Singleton
class DefaultBehaviorProfileRepository @Inject constructor(
    private val dao: BehaviorProfileDao,
) : BehaviorProfileRepository {
    override fun observeProfile(): Flow<BehaviorProfile> = dao.observe().map { it?.asExternalModel() ?: BehaviorProfile() }

    override suspend fun getProfile(): BehaviorProfile = dao.get()?.asExternalModel() ?: BehaviorProfile()

    override suspend fun seedDefaults(profile: BehaviorProfile) {
        dao.upsert(profile.asEntity())
    }

    override suspend fun updateFromInteraction(task: Task, event: InteractionEvent) {
        val current = getProfile()
        val nextInteractions = current.totalInteractions + 1
        val nextCompletions = current.totalCompletions + if (event.type == com.orka.core.model.InteractionType.MARK_DONE) 1 else 0

        val categorySnooze = current.categorySnoozeRates.toMutableMap()
        val categoryCompletion = current.categoryCompletionRates.toMutableMap()

        val currentSnooze = categorySnooze[task.category] ?: 0f
        val currentCompletion = categoryCompletion[task.category] ?: 0f
        val alpha = 0.1f

        when {
            event.type.name.startsWith("SNOOZE") || event.type == com.orka.core.model.InteractionType.IGNORE -> {
                categorySnooze[task.category] = (currentSnooze * (1f - alpha) + alpha * 1.0f).coerceIn(0f, 1f)
                categoryCompletion[task.category] = (currentCompletion * (1f - alpha)).coerceIn(0f, 1f)
            }
            event.type == com.orka.core.model.InteractionType.MARK_DONE -> {
                categoryCompletion[task.category] = (currentCompletion * (1f - alpha) + alpha * 1.0f).coerceIn(0f, 1f)
                categorySnooze[task.category] = (currentSnooze * (1f - alpha)).coerceIn(0f, 1f)
            }
            event.type == com.orka.core.model.InteractionType.START_TASK -> {
                categorySnooze[task.category] = (currentSnooze * (1f - alpha)).coerceIn(0f, 1f)
            }
            event.type == com.orka.core.model.InteractionType.DISMISS_TASK -> {
                categoryCompletion[task.category] = (currentCompletion * (1f - alpha)).coerceIn(0f, 1f)
            }
        }

        dao.upsert(
            current.copy(
                totalInteractions = nextInteractions,
                totalCompletions = nextCompletions,
                snoozeRate = categorySnooze.values.average().toFloat().takeIf { !it.isNaN() } ?: 0f,
                categorySnoozeRates = categorySnooze,
                categoryCompletionRates = categoryCompletion,
            ).asEntity(),
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OrkaDatabase = Room.databaseBuilder(
        context,
        OrkaDatabase::class.java,
        "orka.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    @Provides
    fun provideTaskDao(database: OrkaDatabase): TaskDao = database.taskDao()

    @Provides
    fun provideReminderDao(database: OrkaDatabase): ReminderDao = database.reminderDao()

    @Provides
    fun provideInteractionDao(database: OrkaDatabase): com.orka.core.database.InteractionDao = database.interactionDao()

    @Provides
    fun provideBehaviorDao(database: OrkaDatabase): BehaviorProfileDao = database.behaviorProfileDao()

    @Provides
    fun provideAlarmRegistryDao(database: OrkaDatabase): com.orka.core.database.AlarmRegistryDao = database.alarmRegistryDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindingsModule {
    @Binds
    abstract fun bindTaskRepository(impl: RoomTaskRepository): TaskRepository

    @Binds
    abstract fun bindBehaviorProfileRepository(impl: DefaultBehaviorProfileRepository): BehaviorProfileRepository
}
