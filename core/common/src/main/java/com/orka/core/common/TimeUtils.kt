package com.orka.core.common
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class UrgencyTier {
    CALM,
    URGENT,
    CRITICAL,
}

object TimeFormatter {
    private val IST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private val ABSOLUTE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a", Locale.ENGLISH)

    fun humanizeDuration(duration: Duration): String {
        val totalMinutes = duration.toMinutes().coerceAtLeast(0)
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes % (60 * 24)) / 60
        val minutes = totalMinutes % 60

        return buildList {
            if (days > 0) add("${days}d")
            if (hours > 0) add("${hours}h")
            if (minutes > 0 || isEmpty()) add("${minutes}m")
        }.joinToString(" ")
    }

    /** Formats an absolute instant in ORKA's IST baseline, e.g. "Wed, 2 Apr · 9:00 PM". */
    fun formatInstant(instant: Instant, zoneId: ZoneId = IST_ZONE): String =
        ABSOLUTE_TIME_FORMATTER.format(instant.atZone(zoneId))
}

object UrgencyCalculator {
    fun urgencyScore(deadline: Instant, now: Instant, estimatedEffortMinutes: Int): Float {
        val hoursRemaining = Duration.between(now, deadline).toHours().coerceAtLeast(0)
        val deadlineFactor = when {
            hoursRemaining <= 24 -> 5f
            hoursRemaining <= 72 -> 4f
            hoursRemaining <= 120 -> 3f
            else -> 2f
        }
        val effortFactor = (estimatedEffortMinutes / 60f).coerceIn(0.5f, 3f)
        return (deadlineFactor + effortFactor).coerceIn(1f, 5f)
    }

    fun tier(deadline: Instant, now: Instant): UrgencyTier {
        val hoursRemaining = Duration.between(now, deadline).toHours()
        return when {
            hoursRemaining <= 24 -> UrgencyTier.CRITICAL
            hoursRemaining <= 24 * 5 -> UrgencyTier.URGENT
            else -> UrgencyTier.CALM
        }
    }
}

class ClockProvider(
    val clock: Clock = Clock.systemDefaultZone(),
) {
    fun now(): Instant = clock.instant()
}
