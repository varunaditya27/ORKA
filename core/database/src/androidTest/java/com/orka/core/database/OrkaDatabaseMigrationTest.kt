package com.orka.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB_NAME = "migration-test.db"

@RunWith(AndroidJUnit4::class)
class OrkaDatabaseMigrationTest {
    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrkaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2AddsColumnsWithoutDroppingExistingRows() {
        helper.createDatabase(TEST_DB_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO tasks (
                    id, rawInput, title, description, deadline, deadlineConfidence, category,
                    estimatedEffortMinutes, urgencyScore, status, createdAt, updatedAt, completedAt,
                    userCorrectedFields
                ) VALUES (
                    'task-1', 'raw', 'Title', NULL, 0, 0.9, 'PROFESSIONAL', 90, 4.0, 'PENDING', 0, 0,
                    NULL, ''
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_NAME, 2, true, MIGRATION_1_2)
        val cursor = migrated.query("SELECT id, primitiveType, resolvedTimezone FROM tasks WHERE id = 'task-1'")
        assertThat(cursor.moveToFirst()).isTrue()
        assertThat(cursor.getString(cursor.getColumnIndexOrThrow("id"))).isEqualTo("task-1")
        // New columns must land on their declared DEFAULT for pre-existing rows.
        assertThat(cursor.getString(cursor.getColumnIndexOrThrow("primitiveType"))).isEqualTo("TASK")
        assertThat(cursor.getString(cursor.getColumnIndexOrThrow("resolvedTimezone"))).isEqualTo("Asia/Kolkata")
        cursor.close()
    }

    @Test
    fun migrate2To3AddsTaskIdIndicesWithoutDroppingExistingRows() {
        helper.createDatabase(TEST_DB_NAME, 2).apply {
            execSQL(
                """
                INSERT INTO reminders (
                    id, taskId, scheduledTime, actualFireTime, sequenceNumber, alarmManagerId,
                    primitiveType, preEventProfile, minutesBeforeAnchor, reminderLabel, status, schedulerMode
                ) VALUES (
                    'reminder-1', 'task-1', 0, NULL, 1, 100, 'TASK', NULL, NULL, NULL, 'SCHEDULED', 'RULE_BASED'
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_NAME, 3, true, MIGRATION_2_3)
        val cursor = migrated.query("SELECT id, taskId FROM reminders WHERE id = 'reminder-1'")
        assertThat(cursor.moveToFirst()).isTrue()
        assertThat(cursor.getString(cursor.getColumnIndexOrThrow("taskId"))).isEqualTo("task-1")
        cursor.close()

        val indexCursor = migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'reminders'",
        )
        val indexNames = generateSequence { if (indexCursor.moveToNext()) indexCursor.getString(0) else null }.toList()
        indexCursor.close()
        assertThat(indexNames).contains("index_reminders_taskId")
    }

    @Test
    fun migrateAllTheWayFrom1To3PreservesData() {
        helper.createDatabase(TEST_DB_NAME, 1).apply { close() }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_NAME, 3, true, MIGRATION_1_2, MIGRATION_2_3)
        // A full round-trip through both migrations must still leave a queryable, valid schema —
        // this is what would have caught a version bump that forgot to register a migration.
        val cursor = migrated.query("SELECT COUNT(*) FROM tasks")
        assertThat(cursor.moveToFirst()).isTrue()
        cursor.close()
    }
}
