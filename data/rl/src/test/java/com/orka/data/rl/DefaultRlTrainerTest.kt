package com.orka.data.rl

import com.google.common.truth.Truth.assertThat
import com.orka.core.model.BehaviorProfile
import com.orka.core.model.BehaviorProfileRepository
import com.orka.core.model.InteractionEvent
import com.orka.core.model.RlReadiness
import com.orka.core.model.Task
import com.orka.core.model.TaskCategory
import com.orka.core.testing.TestFixtures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DefaultRlTrainerTest {
    @Test
    fun readinessStaysNotReadyWithoutEnoughData() = runBlocking {
        val trainer = DefaultRlTrainer(FakeBehaviorProfileRepository(BehaviorProfile(totalInteractions = 10, totalCompletions = 2)))

        val readiness = trainer.observeReadiness()

        assertThat(readiness.firstValue()).isInstanceOf(RlReadiness.NotReady::class.java)
    }

    @Test
    fun returnsRecommendationWhenProfileIsReady() = runBlocking {
        val trainer = DefaultRlTrainer(
            FakeBehaviorProfileRepository(
                BehaviorProfile(
                    totalInteractions = 50,
                    totalCompletions = 12,
                    categorySnoozeRates = mapOf(TaskCategory.PROFESSIONAL to 0.8f),
                ),
            ),
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
        val trainer = DefaultRlTrainer(
            FakeBehaviorProfileRepository(
                BehaviorProfile(
                    totalInteractions = 20,
                    totalCompletions = 4,
                ),
            ),
        )

        val summary = trainer.maybeTrain()

        assertThat(summary.trained).isFalse()
        assertThat(summary.episodesUsed).isEqualTo(4)
        assertThat(summary.message).contains("Not enough data")
    }

    @Test
    fun maybeTrainReturnsTrainedWhenProfileIsReady() = runBlocking {
        val trainer = DefaultRlTrainer(
            FakeBehaviorProfileRepository(
                BehaviorProfile(
                    totalInteractions = 60,
                    totalCompletions = 15,
                ),
            ),
        )

        val summary = trainer.maybeTrain()

        assertThat(summary.trained).isTrue()
        assertThat(summary.episodesUsed).isEqualTo(15)
        assertThat(summary.message).contains("refreshed")
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
