package com.orka.data.parser

import com.google.common.truth.Truth.assertThat
import com.orka.core.model.TaskCategory
import com.orka.core.model.TaskDraft
import java.time.Instant
import org.junit.Test

class DefaultTaskDraftValidatorTest {
    private val validator = DefaultTaskDraftValidator()

    @Test
    fun rejectsDraftWithoutDeadline() {
        val result = validator.validate(
            TaskDraft(
                rawInput = "finish assignment",
                title = "Finish assignment",
                category = TaskCategory.ACADEMIC,
                estimatedEffortMinutes = 60,
            ),
        )

        assertThat(result.isValid).isFalse()
        assertThat(result.errors).contains("Deadline is required.")
    }

    @Test
    fun acceptsCompleteDraft() {
        val result = validator.validate(
            TaskDraft(
                rawInput = "finish assignment tomorrow",
                title = "Finish assignment",
                deadline = Instant.parse("2026-03-28T17:00:00Z"),
                category = TaskCategory.ACADEMIC,
                estimatedEffortMinutes = 60,
            ),
        )

        assertThat(result.isValid).isTrue()
        assertThat(result.errors).isEmpty()
    }
}
