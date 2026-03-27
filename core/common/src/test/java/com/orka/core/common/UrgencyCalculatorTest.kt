package com.orka.core.common

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class UrgencyCalculatorTest {
    @Test
    fun urgencyScoreIncreasesAsDeadlineApproaches() {
        val now = Instant.parse("2026-03-30T09:00:00Z")

        val calm = UrgencyCalculator.urgencyScore(
            deadline = now.plusSeconds(60L * 60 * 24 * 10),
            now = now,
            estimatedEffortMinutes = 60,
        )
        val critical = UrgencyCalculator.urgencyScore(
            deadline = now.plusSeconds(60L * 60 * 8),
            now = now,
            estimatedEffortMinutes = 60,
        )

        assertThat(critical).isGreaterThan(calm)
    }

    @Test
    fun tierUsesExpectedThresholds() {
        val now = Instant.parse("2026-03-30T09:00:00Z")

        assertThat(UrgencyCalculator.tier(now.plusSeconds(60L * 60 * 6), now)).isEqualTo(UrgencyTier.CRITICAL)
        assertThat(UrgencyCalculator.tier(now.plusSeconds(60L * 60 * 48), now)).isEqualTo(UrgencyTier.URGENT)
        assertThat(UrgencyCalculator.tier(now.plusSeconds(60L * 60 * 24 * 8), now)).isEqualTo(UrgencyTier.CALM)
    }
}
