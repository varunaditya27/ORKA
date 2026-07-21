package com.orka.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.orka.core.testing.TestFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrkaDatabaseDaoTest {
    private lateinit var database: OrkaDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, OrkaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun taskAndReminderQueriesRoundTrip() = runBlocking {
        val task = TestFixtures.task(id = "db-task")
        val firstReminder = TestFixtures.reminder(
            id = "db-reminder-1",
            taskId = task.id,
            scheduledTime = TestFixtures.now.plusSeconds(60L * 60),
            alarmManagerId = 201,
        )
        val secondReminder = TestFixtures.reminder(
            id = "db-reminder-2",
            taskId = task.id,
            scheduledTime = TestFixtures.now.plusSeconds(60L * 60 * 2),
            sequenceNumber = 2,
            alarmManagerId = 202,
        )

        database.taskDao().upsert(task.asEntity())
        database.reminderDao().insertAll(listOf(firstReminder.asEntity(), secondReminder.asEntity()))

        assertThat(database.taskDao().getTask(task.id)?.title).isEqualTo(task.title)
        assertThat(database.reminderDao().observeNextReminder().first()?.id).isEqualTo(firstReminder.id)

        database.reminderDao().markDelivered(firstReminder.id, TestFixtures.now)

        assertThat(database.reminderDao().observeLastTriggeredReminder().first()?.id).isEqualTo(firstReminder.id)
    }

    @Test
    fun activeAndArchivedQueriesRespectTaskStatus() = runBlocking {
        val pending = TestFixtures.task(id = "task-pending")
        val completed = TestFixtures.task(
            id = "task-completed",
            status = com.orka.core.model.TaskStatus.COMPLETED,
            completedAt = TestFixtures.now,
        )
        val dismissed = TestFixtures.task(
            id = "task-dismissed",
            status = com.orka.core.model.TaskStatus.DISMISSED,
        )

        database.taskDao().upsert(pending.asEntity())
        database.taskDao().upsert(completed.asEntity())
        database.taskDao().upsert(dismissed.asEntity())

        val activeIds = database.taskDao().observeActiveTasks().first().map { it.id }
        val archivedIds = database.taskDao().observeArchivedTasks().first().map { it.id }

        assertThat(activeIds).containsExactly("task-pending")
        assertThat(archivedIds).containsAtLeast("task-completed", "task-dismissed")
    }

    @Test
    fun markOverdueTasksOnlyAffectsPendingTasksPastDeadline() = runBlocking {
        val overduePending = TestFixtures.task(
            id = "task-overdue-pending",
            deadline = TestFixtures.now.minusSeconds(3600),
        )
        val futurePending = TestFixtures.task(
            id = "task-future-pending",
            deadline = TestFixtures.now.plusSeconds(3600),
        )
        val overdueButActive = TestFixtures.task(
            id = "task-overdue-active",
            deadline = TestFixtures.now.minusSeconds(3600),
            status = com.orka.core.model.TaskStatus.ACTIVE,
        )
        val overdueButCompleted = TestFixtures.task(
            id = "task-overdue-completed",
            deadline = TestFixtures.now.minusSeconds(3600),
            status = com.orka.core.model.TaskStatus.COMPLETED,
            completedAt = TestFixtures.now,
        )

        listOf(overduePending, futurePending, overdueButActive, overdueButCompleted).forEach {
            database.taskDao().upsert(it.asEntity())
        }

        database.taskDao().markOverdueTasks(TestFixtures.now)

        assertThat(database.taskDao().getTask(overduePending.id)?.status).isEqualTo(com.orka.core.model.TaskStatus.OVERDUE)
        assertThat(database.taskDao().getTask(overduePending.id)?.urgencyScore).isEqualTo(5.0f)
        assertThat(database.taskDao().getTask(futurePending.id)?.status).isEqualTo(com.orka.core.model.TaskStatus.PENDING)
        assertThat(database.taskDao().getTask(overdueButActive.id)?.status).isEqualTo(com.orka.core.model.TaskStatus.ACTIVE)
        assertThat(database.taskDao().getTask(overdueButCompleted.id)?.status).isEqualTo(com.orka.core.model.TaskStatus.COMPLETED)
    }

    @Test
    fun deleteScheduledForTaskRemovesOnlyScheduledEntries() = runBlocking {
        val task = TestFixtures.task(id = "task-reminders")
        val scheduled = TestFixtures.reminder(id = "scheduled-1", taskId = task.id)
        val fired = TestFixtures.reminder(
            id = "fired-1",
            taskId = task.id,
            status = com.orka.core.model.ReminderStatus.FIRED,
            actualFireTime = TestFixtures.now,
            alarmManagerId = 909,
        )

        database.taskDao().upsert(task.asEntity())
        database.reminderDao().insertAll(listOf(scheduled.asEntity(), fired.asEntity()))

        database.reminderDao().deleteScheduledForTask(task.id)

        val remaining = database.reminderDao().observeForTask(task.id).first().map { it.id }
        assertThat(remaining).containsExactly("fired-1")
    }
}
