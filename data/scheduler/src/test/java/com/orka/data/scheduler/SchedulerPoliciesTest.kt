package com.orka.data.scheduler

import com.google.common.truth.Truth.assertThat
import com.orka.core.model.BehaviorProfile
import com.orka.core.model.InteractionEvent
import com.orka.core.model.TaskCategory
import com.orka.core.testing.TestFixtures
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SchedulerPoliciesTest {
    private val task = TestFixtures.task(
        rawInput = "Prepare presentation by Monday evening",
        title = "Prepare presentation",
        deadline = Instant.parse("2026-04-03T18:00:00Z"),
        category = TaskCategory.PROFESSIONAL,
        estimatedEffortMinutes = 120,
    )

    @Test
    fun ruleBasedScheduleCreatesSortedFutureReminders() = runBlocking {
        val policy = RuleBasedSchedulerPolicy()

        val reminders = policy.schedule(
            task = task,
            context = com.orka.core.model.SchedulingContext(
                profile = BehaviorProfile(),
                interactionHistory = emptyList(),
                now = Instant.parse("2026-03-30T09:00:00Z"),
            ),
        )

        assertThat(reminders).isNotEmpty()
        assertThat(reminders.map { it.scheduledTime })
            .containsExactlyElementsIn(reminders.map { it.scheduledTime }.sorted())
            .inOrder()
        assertThat(reminders.all { it.scheduledTime.isBefore(task.deadline) || it.scheduledTime == task.deadline }).isTrue()
    }

    @Test
    fun adaptiveScheduleAlignsToProductiveWindow() = runBlocking {
        val policy = AdaptiveSchedulerPolicy()

        val reminders = policy.schedule(
            task = task,
            context = com.orka.core.model.SchedulingContext(
                profile = BehaviorProfile(
                    productiveStartHour = 10,
                    productiveEndHour = 18,
                    categorySnoozeRates = mapOf(TaskCategory.PROFESSIONAL to 0.8f),
                ),
                interactionHistory = listOf(InteractionEvent(taskId = task.id, type = com.orka.core.model.InteractionType.SNOOZE_SHORT)),
                now = Instant.parse("2026-03-30T09:00:00Z"),
            ),
        )

        assertThat(reminders).isNotEmpty()
        assertThat(reminders.all { reminder ->
            val hour = reminder.scheduledTime.atZone(java.time.ZoneId.systemDefault()).hour
            hour in 10..18
        }).isTrue()
    }
}
