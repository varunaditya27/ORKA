package com.orka.data.scheduler

import com.google.common.truth.Truth.assertThat
import com.orka.core.model.PreEventProfile
import com.orka.core.model.PrimitiveType
import com.orka.core.model.SchedulingContext
import com.orka.core.testing.TestFixtures
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PreEventSchedulerPolicyTest {
    private val policy = PreEventSchedulerPolicy()
    private val ist = ZoneId.of("Asia/Kolkata")

    @Test
    fun meetingProfileSchedulesStandardPreEventOffsets() = runBlocking {
        val nowIst = ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, ist)
        val eventIst = ZonedDateTime.of(2026, 4, 1, 18, 0, 0, 0, ist)
        val task = TestFixtures.task(
            title = "Team meeting",
            deadline = eventIst.toInstant(),
            primitiveType = PrimitiveType.EVENT,
            eventStartTime = eventIst.toInstant(),
            preEventProfile = PreEventProfile.MEETING,
        )

        val reminders = policy.schedule(
            task = task,
            context = SchedulingContext(
                profile = TestFixtures.behaviorProfile(),
                interactionHistory = emptyList(),
                now = nowIst.toInstant(),
            ),
        )

        val offsets = reminders.mapNotNull { it.minutesBeforeAnchor }
        assertThat(offsets).containsAtLeast(24 * 60L, 2 * 60L, 30L, 5L)
        assertThat(reminders.all { it.primitiveType == PrimitiveType.EVENT }).isTrue()
    }

    @Test
    fun urgentModeSchedulesImmediateHalfwayAndTenMinutesBefore() = runBlocking {
        val nowIst = ZonedDateTime.of(2026, 3, 30, 14, 0, 0, 0, ist)
        val eventIst = nowIst.plusMinutes(90)
        val task = TestFixtures.task(
            title = "Client call",
            deadline = eventIst.toInstant(),
            primitiveType = PrimitiveType.EVENT,
            eventStartTime = eventIst.toInstant(),
            preEventProfile = PreEventProfile.CALL,
        )

        val reminders = policy.schedule(
            task = task,
            context = SchedulingContext(
                profile = TestFixtures.behaviorProfile(),
                interactionHistory = emptyList(),
                now = nowIst.toInstant(),
            ),
        )

        assertThat(reminders).hasSize(3)
        val earliest = reminders.minBy { it.scheduledTime }
        assertThat(Duration.between(nowIst.toInstant(), earliest.scheduledTime).seconds).isAtMost(60)
        assertThat(reminders.any { (it.minutesBeforeAnchor ?: Long.MAX_VALUE) <= 10L }).isTrue()
    }

    @Test
    fun quietHoursKeepsThirtyMinuteReminderAtOriginalTime() = runBlocking {
        val nowIst = ZonedDateTime.of(2026, 3, 30, 2, 0, 0, 0, ist)
        val eventIst = ZonedDateTime.of(2026, 3, 30, 7, 10, 0, 0, ist)
        val task = TestFixtures.task(
            title = "Morning meeting",
            deadline = eventIst.toInstant(),
            primitiveType = PrimitiveType.EVENT,
            eventStartTime = eventIst.toInstant(),
            preEventProfile = PreEventProfile.MEETING,
        )

        val reminders = policy.schedule(
            task = task,
            context = SchedulingContext(
                profile = TestFixtures.behaviorProfile(),
                interactionHistory = emptyList(),
                now = nowIst.toInstant(),
            ),
        )

        val thirtyMinute = reminders.firstOrNull { it.minutesBeforeAnchor == 30L }
        assertThat(thirtyMinute).isNotNull()
        val localTime = thirtyMinute!!.scheduledTime.atZone(ist).toLocalTime()
        assertThat(localTime.hour).isEqualTo(6)
        assertThat(localTime.minute).isEqualTo(40)
    }

    @Test
    fun quietHoursDoesNotDelayReminderPastEventStart() = runBlocking {
        val nowIst = ZonedDateTime.of(2026, 3, 30, 5, 0, 0, 0, ist)
        val eventIst = ZonedDateTime.of(2026, 3, 30, 7, 15, 0, 0, ist)
        val task = TestFixtures.task(
            title = "Early Flight",
            deadline = eventIst.toInstant(),
            primitiveType = PrimitiveType.EVENT,
            eventStartTime = eventIst.toInstant(),
            preEventProfile = PreEventProfile.APPOINTMENT,
        )

        val reminders = policy.schedule(
            task = task,
            context = SchedulingContext(
                profile = TestFixtures.behaviorProfile(),
                interactionHistory = emptyList(),
                now = nowIst.toInstant(),
            ),
        )

        val twoHourReminder = reminders.firstOrNull { it.minutesBeforeAnchor == 120L }
        assertThat(twoHourReminder).isNotNull()
        assertThat(twoHourReminder!!.scheduledTime).isLessThan(eventIst.toInstant())
    }

    @Test
    fun subMinuteImminentEventSchedulesImmediateReminderBeforeEventStart() = runBlocking {
        val nowIst = ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, ist)
        val eventIst = nowIst.plusSeconds(40) // 40 seconds away
        val task = TestFixtures.task(
            title = "Standup Quick Sync",
            deadline = eventIst.toInstant(),
            primitiveType = PrimitiveType.EVENT,
            eventStartTime = eventIst.toInstant(),
            preEventProfile = PreEventProfile.CALL,
        )

        val reminders = policy.schedule(
            task = task,
            context = SchedulingContext(
                profile = TestFixtures.behaviorProfile(),
                interactionHistory = emptyList(),
                now = nowIst.toInstant(),
            ),
        )

        assertThat(reminders).isNotEmpty()
        assertThat(reminders.all { it.scheduledTime.isBefore(eventIst.toInstant()) }).isTrue()
    }
}
