package com.orka.data.execution

import com.google.common.truth.Truth.assertThat
import com.orka.core.model.InteractionType
import com.orka.core.testing.TestFixtures
import java.time.Duration
import org.junit.Test

class DefaultAlarmActionResolverTest {
    private val resolver = DefaultAlarmActionResolver()

    @Test
    fun resolvePrioritizesStartAndSnoozeForNearDeadlineTask() {
        val task = TestFixtures.task(
            deadline = TestFixtures.now.plus(Duration.ofHours(8)),
            estimatedEffortMinutes = 60,
        )

        val actions = resolver.resolve(task, emptyList(), TestFixtures.now)

        assertThat(actions.map { it.type }).containsAtLeast(
            InteractionType.START_TASK,
            InteractionType.SNOOZE_SHORT,
            InteractionType.MARK_DONE,
            InteractionType.ACKNOWLEDGE,
        )
        assertThat(actions.first().type).isEqualTo(InteractionType.START_TASK)
    }

    @Test
    fun resolveOffersSplitTaskForLongEffortWork() {
        val task = TestFixtures.task(
            deadline = TestFixtures.now.plus(Duration.ofDays(4)),
            estimatedEffortMinutes = 180,
        )

        val actions = resolver.resolve(task, emptyList(), TestFixtures.now)

        assertThat(actions.map { it.type }).contains(InteractionType.SPLIT_TASK)
        assertThat(actions.map { it.type }).doesNotContain(InteractionType.SNOOZE_SHORT)
    }
}
