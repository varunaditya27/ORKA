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
            val hour = reminder.scheduledTime.atZone(java.time.ZoneId.of("Asia/Kolkata")).hour
            hour in 10..18
        }).isTrue()
    }

    @Test
    fun adaptiveScheduleNeverSchedulesInPastAndDeduplicatesReminders() = runBlocking {
        val policy = AdaptiveSchedulerPolicy()
        val now = Instant.parse("2026-03-30T09:00:00Z") // 14:30 IST
        val shortTask = task.copy(
            deadline = now.plus(java.time.Duration.ofHours(3)), // 17:30 IST
        )

        val reminders = policy.schedule(
            task = shortTask,
            context = com.orka.core.model.SchedulingContext(
                profile = BehaviorProfile(
                    productiveStartHour = 9,
                    productiveEndHour = 21,
                    categorySnoozeRates = mapOf(TaskCategory.PROFESSIONAL to 0.8f),
                ),
                interactionHistory = emptyList(),
                now = now,
            ),
        )

        assertThat(reminders).isNotEmpty()
        // Every scheduled reminder must strictly be after now + 30s
        assertThat(reminders.all { it.scheduledTime.isAfter(now.plusSeconds(30)) || it.scheduledTime == now.plusSeconds(30) }).isTrue()
        // No duplicate reminder timestamps
        val scheduledTimes = reminders.map { it.scheduledTime }
        assertThat(scheduledTimes).containsNoDuplicates()
    }

    @Test
    fun adaptiveScheduleSupportsNightShiftProductiveWindowWithoutCrashing() = runBlocking {
        val policy = AdaptiveSchedulerPolicy()
        val now = Instant.parse("2026-03-30T17:00:00Z") // 22:30 IST (inside 22:00 to 05:00 window)
        val nightTask = task.copy(
            deadline = now.plus(java.time.Duration.ofHours(4)), // 02:30 IST
        )

        // Should not throw IllegalArgumentException when startHour (22) > endHour (5)
        val reminders = policy.schedule(
            task = nightTask,
            context = com.orka.core.model.SchedulingContext(
                profile = BehaviorProfile(
                    productiveStartHour = 22,
                    productiveEndHour = 5,
                    categorySnoozeRates = mapOf(TaskCategory.PROFESSIONAL to 0.2f),
                ),
                interactionHistory = emptyList(),
                now = now,
            ),
        )

        assertThat(reminders).isNotEmpty()
        assertThat(reminders.all { it.scheduledTime.isBefore(nightTask.deadline) || it.scheduledTime == nightTask.deadline }).isTrue()
    }

    @Test
    fun adaptiveScheduleGuaranteesReminderForDiligentUserNearDeadline() = runBlocking {
        val policy = AdaptiveSchedulerPolicy()
        val now = Instant.parse("2026-03-30T09:00:00Z") // 14:30 IST
        val imminentTask = task.copy(
            deadline = now.plus(java.time.Duration.ofMinutes(45)), // 15:15 IST
        )

        // Diligent user (snooze rate 0.1f) must NOT have reminders pushed past 15:15 deadline
        val reminders = policy.schedule(
            task = imminentTask,
            context = com.orka.core.model.SchedulingContext(
                profile = BehaviorProfile(
                    productiveStartHour = 9,
                    productiveEndHour = 21,
                    categorySnoozeRates = mapOf(TaskCategory.PROFESSIONAL to 0.1f),
                ),
                interactionHistory = emptyList(),
                now = now,
            ),
        )

        assertThat(reminders).isNotEmpty()
        assertThat(reminders.all { it.scheduledTime.isBefore(imminentTask.deadline) }).isTrue()
    }
}
