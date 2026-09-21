package com.orka.data.rl

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.orka.core.database.InteractionDao
import com.orka.core.database.InteractionEventEntity
import com.orka.core.database.TaskDao
import com.orka.core.database.TaskEntity
import com.orka.core.model.BehaviorProfile
import com.orka.core.model.BehaviorProfileRepository
import com.orka.core.model.InteractionEvent
import com.orka.core.model.InteractionType
import com.orka.core.model.PreEventProfile
import com.orka.core.model.PrimitiveType
import com.orka.core.model.RlReadiness
import com.orka.core.model.Task
import com.orka.core.model.TaskCategory
import com.orka.core.model.TaskStatus
import com.orka.core.testing.TestFixtures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.io.File
import java.time.Instant

class DefaultRlTrainerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var taskDao: TaskDao
    private lateinit var interactionDao: InteractionDao

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.newFolder("files"))
        taskDao = mock(TaskDao::class.java)
        interactionDao = mock(InteractionDao::class.java)
    }

    @Test
    fun readinessStaysNotReadyWithoutEnoughData() = runBlocking {
        val trainer = DefaultRlTrainer(
            context = context,
            profileRepository = FakeBehaviorProfileRepository(BehaviorProfile(totalInteractions = 10, totalCompletions = 2)),
            taskDao = taskDao,
            interactionDao = interactionDao
        )

        val readiness = trainer.observeReadiness()

        assertThat(readiness.firstValue()).isInstanceOf(RlReadiness.NotReady::class.java)
    }

    @Test
    fun returnsRecommendationWhenProfileIsReady() = runBlocking {
        val trainer = DefaultRlTrainer(
            context = context,
            profileRepository = FakeBehaviorProfileRepository(
                BehaviorProfile(
                    totalInteractions = 50,
                    totalCompletions = 12,
                    categorySnoozeRates = mapOf(TaskCategory.PROFESSIONAL to 0.8f),
                ),
            ),
            taskDao = taskDao,
            interactionDao = interactionDao
        )

        val recommendation = trainer.recommend(
            task = TestFixtures.task(
                rawInput = "Prepare quarterly review",
                title = "Prepare quarterly review",
                deadline = java.time.Instant.parse("2026-04-10T12:00:00Z"),
                category = TaskCategory.PROFESSIONAL,
                estimatedEffortMinutes = 180,
            ),
            context = com.orka.core.model.SchedulingContext(
                profile = BehaviorProfile(),
                interactionHistory = listOf(InteractionEvent(taskId = "task", type = com.orka.core.model.InteractionType.SNOOZE_SHORT)),
            ),
        )

        assertThat(recommendation).isNotNull()
        assertThat(recommendation?.nextReminderDelayHours).isEqualTo(1)
    }

    @Test
    fun maybeTrainReturnsNotTrainedWhenProfileIsInsufficient() = runBlocking {
        `when`(taskDao.getAllTasks()).thenReturn(emptyList())
        `when`(interactionDao.getAllInteractions()).thenReturn(emptyList())

        val trainer = DefaultRlTrainer(
            context = context,
            profileRepository = FakeBehaviorProfileRepository(
                BehaviorProfile(
                    totalInteractions = 20,
                    totalCompletions = 4,
                ),
            ),
            taskDao = taskDao,
            interactionDao = interactionDao
        )

        val summary = trainer.maybeTrain()

        assertThat(summary.trained).isFalse()
        assertThat(summary.episodesUsed).isEqualTo(0)
        assertThat(summary.message).contains("Not enough data")
    }

    @Test
    fun maybeTrainReturnsTrainedWhenProfileIsReady() = runBlocking {
        val now = Instant.now()
        val task = TaskEntity(
            id = "task1",
            rawInput = "Task 1",
            title = "Task 1",
            description = "Task 1 description",
            deadline = now.plus(java.time.Duration.ofDays(1)),
            deadlineConfidence = 1.0f,
            primitiveType = PrimitiveType.TASK,
            eventStartTime = null,
            eventDurationMinutes = null,
            preEventProfile = null,
            linkedEntityId = null,
            clarificationNeeded = false,
            clarificationReason = null,
            resolvedTimezone = "Asia/Kolkata",
            temporalExpressionRaw = null,
            category = TaskCategory.PROFESSIONAL,
            estimatedEffortMinutes = 30,
            urgencyScore = 1.0f,
            status = TaskStatus.COMPLETED,
            createdAt = now,
            updatedAt = now,
            completedAt = now,
            userCorrectedFields = emptySet(),
        )

        val interaction = InteractionEventEntity(
            id = "int1",
            taskId = "task1",
            reminderId = "rem1",
            type = InteractionType.MARK_DONE,
            timestamp = now,
            responseDelaySeconds = 10L,
            escalationApplied = false,
            metadata = emptyMap(),
        )

        `when`(taskDao.getAllTasks()).thenReturn(listOf(task))
        `when`(interactionDao.getAllInteractions()).thenReturn(listOf(interaction))

        val trainer = DefaultRlTrainer(
            context = context,
            profileRepository = FakeBehaviorProfileRepository(
                BehaviorProfile(
                    totalInteractions = 60,
                    totalCompletions = 15,
                ),
            ),
            taskDao = taskDao,
            interactionDao = interactionDao
        )

        val summary = trainer.maybeTrain()

        assertThat(summary.trained).isTrue()
        assertThat(summary.episodesUsed).isEqualTo(10) // 1 episode run 10 times in repeat loop
        assertThat(summary.message).contains("successfully updated")

        val weightsFile = File(context.filesDir, "rl_weights.txt")
        val tmpFile = File(context.filesDir, "rl_weights.txt.tmp")
        assertThat(weightsFile.exists()).isTrue()
        assertThat(tmpFile.exists()).isFalse()
        val lines = weightsFile.readLines()
        assertThat(lines).hasSize(8)
        lines.forEach { line ->
            assertThat(line.split(",")).hasSize(16)
        }
    }

    @Test
    fun trainingWithOverdueTaskAndManyRemindersSucceedsWithPositiveReward() = runBlocking {
        val now = Instant.now()
        val overdueDeadline = now.minus(java.time.Duration.ofHours(2))
        val task = TaskEntity(
            id = "task-overdue",
            rawInput = "Late Task",
            title = "Late Task",
            description = null,
            deadline = overdueDeadline,
            deadlineConfidence = 1.0f,
            primitiveType = PrimitiveType.TASK,
            eventStartTime = null,
            eventDurationMinutes = null,
            preEventProfile = null,
            linkedEntityId = null,
            clarificationNeeded = false,
            clarificationReason = null,
            resolvedTimezone = "Asia/Kolkata",
            temporalExpressionRaw = null,
            category = TaskCategory.PROFESSIONAL,
            estimatedEffortMinutes = 30,
            urgencyScore = 1.0f,
            status = TaskStatus.COMPLETED,
            createdAt = now.minus(java.time.Duration.ofDays(2)),
            updatedAt = now,
            completedAt = now,
            userCorrectedFields = emptySet(),
        )

        // 30 snooze interactions + 1 mark done
        val interactions = (1..30).map { i ->
            InteractionEventEntity(
                id = "int-$i",
                taskId = "task-overdue",
                reminderId = "rem-$i",
                type = InteractionType.SNOOZE_SHORT,
                timestamp = now.minus(java.time.Duration.ofHours((31 - i).toLong())),
                responseDelaySeconds = 10L,
                escalationApplied = false,
                metadata = emptyMap(),
            )
        } + InteractionEventEntity(
            id = "int-done",
            taskId = "task-overdue",
            reminderId = "rem-31",
            type = InteractionType.MARK_DONE,
            timestamp = now,
            responseDelaySeconds = 10L,
            escalationApplied = false,
            metadata = emptyMap(),
        )

        `when`(taskDao.getAllTasks()).thenReturn(listOf(task))
        `when`(interactionDao.getAllInteractions()).thenReturn(interactions)

        val trainer = DefaultRlTrainer(
            context = context,
            profileRepository = FakeBehaviorProfileRepository(
                BehaviorProfile(
                    totalInteractions = 80,
                    totalCompletions = 20,
                    productiveStartHour = 22,
                    productiveEndHour = 5,
                ),
            ),
            taskDao = taskDao,
            interactionDao = interactionDao
        )

        val summary = trainer.maybeTrain()
        assertThat(summary.trained).isTrue()
    }
}

private class FakeBehaviorProfileRepository(
    private val profile: BehaviorProfile,
) : BehaviorProfileRepository {
    override fun observeProfile(): Flow<BehaviorProfile> = flowOf(profile)

    override suspend fun getProfile(): BehaviorProfile = profile

    override suspend fun seedDefaults(profile: BehaviorProfile) = Unit

    override suspend fun updateFromInteraction(task: Task, event: com.orka.core.model.InteractionEvent) = Unit
}

private suspend fun Flow<RlReadiness>.firstValue(): RlReadiness = first()
