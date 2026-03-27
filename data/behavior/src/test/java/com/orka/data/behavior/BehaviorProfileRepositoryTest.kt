package com.orka.data.behavior

import com.google.common.truth.Truth.assertThat
import com.orka.core.database.BehaviorProfileDao
import com.orka.core.database.BehaviorProfileEntity
import com.orka.core.database.asEntity
import com.orka.core.database.asExternalModel
import com.orka.core.model.BehaviorProfile
import com.orka.core.model.InteractionType
import com.orka.core.testing.TestFixtures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BehaviorProfileRepositoryTest {
    @Test
    fun snoozeInteractionIncrementsRatesAndTotals() = runBlocking {
        val dao = FakeBehaviorProfileDao(BehaviorProfile().asEntity())
        val repository = DefaultBehaviorProfileRepository(dao)
        val task = TestFixtures.task()

        repository.updateFromInteraction(
            task = task,
            event = TestFixtures.interaction(taskId = task.id, type = InteractionType.SNOOZE_SHORT),
        )

        val updated = repository.getProfile()
        assertThat(updated.totalInteractions).isEqualTo(1)
        assertThat(updated.totalCompletions).isEqualTo(0)
        assertThat(updated.categorySnoozeRates[task.category]).isEqualTo(0.1f)
    }

    @Test
    fun markDoneUpdatesCompletionStats() = runBlocking {
        val dao = FakeBehaviorProfileDao(BehaviorProfile().asEntity())
        val repository = DefaultBehaviorProfileRepository(dao)
        val task = TestFixtures.task()

        repository.updateFromInteraction(
            task = task,
            event = TestFixtures.interaction(taskId = task.id, type = InteractionType.MARK_DONE),
        )

        val updated = repository.getProfile()
        assertThat(updated.totalInteractions).isEqualTo(1)
        assertThat(updated.totalCompletions).isEqualTo(1)
        assertThat(updated.categoryCompletionRates[task.category]).isEqualTo(0.1f)
    }
}

private class FakeBehaviorProfileDao(
    initial: BehaviorProfileEntity?,
) : BehaviorProfileDao {
    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<BehaviorProfileEntity?> = state

    override suspend fun get(): BehaviorProfileEntity? = state.value

    override suspend fun upsert(profile: BehaviorProfileEntity) {
        state.value = profile
    }
}
