package com.orka.data.scheduler

import com.orka.core.model.AlarmRegistrar
import com.orka.core.model.BehaviorProfile
import com.orka.core.model.PreEventProfile
import com.orka.core.model.PrimitiveType
import com.orka.core.model.ReminderEvent
import com.orka.core.model.RlTrainer
import com.orka.core.model.SchedulerMode
import com.orka.core.model.SchedulerPolicy
import com.orka.core.model.SchedulingContext
import com.orka.core.model.SettingsRepository
import com.orka.core.model.Task
import com.orka.core.model.TaskRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

private val IST_ZONE_ID: ZoneId = ZoneId.of("Asia/Kolkata")
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

class PreEventSchedulerPolicy @Inject constructor() {
    fun schedule(task: Task, context: SchedulingContext): List<ReminderEvent> {
        val anchor = task.eventStartTime ?: task.deadline
        val profile = task.preEventProfile ?: PreEventProfile.MEETING
        val now = context.now
        val secondsUntilEvent = Duration.between(now, anchor).seconds
        if (secondsUntilEvent <= 0) return emptyList()
        val minutesUntilEvent = Duration.between(now, anchor).toMinutes()

        val rawReminders = if (minutesUntilEvent <= 120) {
            urgentPreEventReminders(task, profile, anchor, now)
        } else {
            profileOffsets(profile)
                .mapNotNull { offsetMinutes ->
                    val scheduled = anchor.minus(Duration.ofMinutes(offsetMinutes))
                    if (scheduled.isAfter(now)) {
                        val adjusted = applyQuietHoursPolicy(scheduled, offsetMinutes, anchor)
                        ReminderEvent(
                            taskId = task.id,
                            scheduledTime = adjusted,
                            sequenceNumber = 0,
                            alarmManagerId = 0,
                            primitiveType = PrimitiveType.EVENT,
                            preEventProfile = profile,
                            minutesBeforeAnchor = offsetMinutes,
                            reminderLabel = reminderLabel(task.title, profile, offsetMinutes, anchor),
                            schedulerMode = SchedulerMode.RULE_BASED,
                        )
                    } else {
                        null
                    }
                }
        }

        return rawReminders
            .filter { it.scheduledTime.isBefore(anchor) || it.scheduledTime == anchor }
            .distinctBy { it.scheduledTime }
            .sortedBy { it.scheduledTime }
            .mapIndexed { index, reminder ->
                reminder.copy(
                    sequenceNumber = index + 1,
                    alarmManagerId = stableAlarmId(task.id, index),
                )
            }
    }

    private fun urgentPreEventReminders(
        task: Task,
        profile: PreEventProfile,
        anchor: Instant,
        now: Instant,
    ): List<ReminderEvent> {
        val minutesUntilEvent = Duration.between(now, anchor).toMinutes().coerceAtLeast(1)
        val immediate = now.plusSeconds(10)
        val halfway = now.plus(Duration.ofMinutes((minutesUntilEvent / 2).coerceAtLeast(1)))
        val tenBefore = anchor.minus(Duration.ofMinutes(10))

        return listOf(immediate, halfway, tenBefore)
            .distinct()
            .filter { it.isAfter(now) }
            .map { scheduled ->
                val minutesBefore = Duration.between(scheduled, anchor).toMinutes().coerceAtLeast(0)
                ReminderEvent(
                    taskId = task.id,
                    scheduledTime = scheduled,
                    sequenceNumber = 0,
                    alarmManagerId = 0,
                    primitiveType = PrimitiveType.EVENT,
                    preEventProfile = profile,
                    minutesBeforeAnchor = minutesBefore,
                    reminderLabel = reminderLabel(task.title, profile, minutesBefore, anchor),
                    schedulerMode = SchedulerMode.RULE_BASED,
                )
            }
    }

    private fun profileOffsets(profile: PreEventProfile): List<Long> = when (profile) {
        PreEventProfile.MEETING -> listOf(24 * 60L, 2 * 60L, 30L, 5L)
        PreEventProfile.EXAM -> listOf(2 * 24 * 60L, 24 * 60L, 3 * 60L, 60L, 15L)
        PreEventProfile.APPOINTMENT -> listOf(24 * 60L, 2 * 60L, 30L)
        PreEventProfile.TRAVEL -> listOf(24 * 60L, 3 * 60L, 90L, 30L)
        PreEventProfile.CALL -> listOf(60L, 10L, 2L)
        PreEventProfile.DEADLINE_EVENT -> listOf(3 * 24 * 60L, 24 * 60L, 3 * 60L, 30L)
    }

    private fun applyQuietHoursPolicy(scheduled: Instant, minutesBefore: Long, anchor: Instant): Instant {
        val zoned = scheduled.atZone(IST_ZONE_ID)
        val localTime = zoned.toLocalTime()
        val inQuietHours = localTime >= LocalTime.of(23, 0) || localTime < LocalTime.of(7, 0)
        val shouldBypass = minutesBefore <= 30L
        if (!inQuietHours || shouldBypass) return scheduled

        val targetDate = if (localTime >= LocalTime.of(23, 0)) {
            zoned.toLocalDate().plusDays(1)
        } else {
            zoned.toLocalDate()
        }
        val adjusted = ZonedDateTime.of(targetDate, LocalTime.of(7, 30), IST_ZONE_ID).toInstant()
        return if (adjusted.isBefore(anchor)) adjusted else scheduled
    }

    private fun reminderLabel(
        title: String,
        profile: PreEventProfile,
        minutesBefore: Long,
        anchor: Instant,
    ): String {
        val timeLabel = TIME_FORMATTER.format(anchor.atZone(IST_ZONE_ID))
        return when (profile) {
            PreEventProfile.MEETING -> when (minutesBefore) {
                24 * 60L -> "Tomorrow: $title at $timeLabel IST"
                2 * 60L -> "$title in 2 hours — at $timeLabel IST"
                30L -> "$title in 30 minutes"
                else -> "$title starting now"
            }
            PreEventProfile.EXAM -> when (minutesBefore) {
                2 * 24 * 60L -> "$title — 2 days. Start review now."
                24 * 60L -> "$title tomorrow at $timeLabel. Final review."
                3 * 60L -> "$title in 3 hours."
                60L -> "$title in 1 hour. Leave now if needed."
                else -> "$title starting in 15 minutes."
            }
            PreEventProfile.APPOINTMENT -> when (minutesBefore) {
                24 * 60L -> "$title tomorrow at $timeLabel"
                2 * 60L -> "$title in 2 hours. Confirm if needed."
                else -> "$title in 30 minutes. Leave time to travel."
            }
            PreEventProfile.TRAVEL -> when (minutesBefore) {
                24 * 60L -> "$title tomorrow. Check documents."
                3 * 60L -> "$title in 3 hours. Finish packing."
                90L -> "$title in 90 minutes. Start heading out."
                else -> "$title in 30 minutes."
            }
            PreEventProfile.CALL -> when (minutesBefore) {
                60L -> "Call $title at $timeLabel"
                10L -> "Call $title in 10 minutes"
                else -> "Call $title now"
            }
            PreEventProfile.DEADLINE_EVENT -> when (minutesBefore) {
                3 * 24 * 60L -> "$title closes in 3 days. Begin now."
                24 * 60L -> "$title closes tomorrow at $timeLabel."
                3 * 60L -> "$title in 3 hours. Final window."
                else -> "$title closing in 30 minutes."
            }
        }
    }
}

class RuleBasedSchedulerPolicy @Inject constructor() : SchedulerPolicy {
    override val mode: SchedulerMode = SchedulerMode.RULE_BASED

    override suspend fun schedule(task: Task, context: SchedulingContext): List<ReminderEvent> {
        val now = context.now
        val hoursRemaining = max(1, Duration.between(now, task.deadline).toHours().toInt())
        val baseOffsets = when {
            hoursRemaining <= 24 -> listOf(1, 4, 8, 12)
            hoursRemaining <= 72 -> listOf(6, 18, 36)
            hoursRemaining <= 24 * 5 -> listOf(12, 48, 96)
            else -> listOf(24, 72, 144)
        }

        return baseOffsets.mapIndexed { index, offsetHours ->
            ReminderEvent(
                taskId = task.id,
                scheduledTime = task.deadline.minus(Duration.ofHours(offsetHours.toLong())).coerceAtLeast(now.plusSeconds(30)),
                sequenceNumber = index + 1,
                alarmManagerId = stableAlarmId(task.id, index),
                primitiveType = PrimitiveType.TASK,
                preEventProfile = null,
                minutesBeforeAnchor = Duration.between(
                    task.deadline.minus(Duration.ofHours(offsetHours.toLong())).coerceAtLeast(now.plusSeconds(30)),
                    task.deadline,
                ).toMinutes(),
                schedulerMode = mode,
            )
        }.distinctBy { it.scheduledTime }
            .sortedBy { it.scheduledTime }
    }
}

class AdaptiveSchedulerPolicy @Inject constructor() : SchedulerPolicy {
    override val mode: SchedulerMode = SchedulerMode.ADAPTIVE

    override suspend fun schedule(task: Task, context: SchedulingContext): List<ReminderEvent> {
        val ruleBased = RuleBasedSchedulerPolicy().schedule(task, context)
        val snoozeRate = context.profile.categorySnoozeRates[task.category] ?: context.profile.snoozeRate
        val shiftHours = if (snoozeRate > 0.5f) -1 else 1
        val minValidTime = context.now.plusSeconds(30)
        val maxSafeTime = task.deadline.minus(Duration.ofMinutes(15)).takeIf { it.isAfter(minValidTime) } ?: task.deadline

        val reminders = ruleBased.map { reminder ->
            val shifted = if (shiftHours > 0) {
                minOf(reminder.scheduledTime.plus(Duration.ofHours(shiftHours.toLong())), maxSafeTime)
            } else {
                reminder.scheduledTime.plus(Duration.ofHours(shiftHours.toLong()))
            }
            val aligned = alignToProductiveWindow(shifted, context.profile)
            val finalTime = aligned.coerceAtLeast(minValidTime)
            reminder.copy(
                scheduledTime = finalTime,
                minutesBeforeAnchor = Duration.between(finalTime, task.deadline).toMinutes(),
                schedulerMode = mode,
            )
        }.filter { it.scheduledTime.isBefore(task.deadline) || it.scheduledTime == task.deadline }
            .distinctBy { it.scheduledTime }
            .sortedBy { it.scheduledTime }

        val finalReminders = if (reminders.isEmpty() && task.deadline.isAfter(minValidTime)) {
            val fallbackTime = minOf(task.deadline.minus(Duration.ofMinutes(15)), task.deadline)
                .coerceAtLeast(minValidTime)
            listOf(
                ReminderEvent(
                    taskId = task.id,
                    scheduledTime = fallbackTime,
                    sequenceNumber = 1,
                    alarmManagerId = stableAlarmId(task.id, 0),
                    primitiveType = task.primitiveType,
                    preEventProfile = task.preEventProfile,
                    minutesBeforeAnchor = Duration.between(fallbackTime, task.deadline).toMinutes(),
                    schedulerMode = mode,
                ),
            )
        } else {
            reminders
        }

        return finalReminders.mapIndexed { index, reminder ->
            reminder.copy(
                sequenceNumber = index + 1,
                alarmManagerId = stableAlarmId(task.id, index),
            )
        }
    }

    private fun alignToProductiveWindow(time: Instant, profile: BehaviorProfile): Instant {
        val zoned = time.atZone(IST_ZONE_ID)
        val start = profile.productiveStartHour
        val end = profile.productiveEndHour
        val currentHour = zoned.hour

        val adjustedHour = when {
            start <= end -> currentHour.coerceIn(start, end)
            currentHour >= start || currentHour <= end -> currentHour
            else -> {
                val distToStart = (start - currentHour + 24) % 24
                val distToEnd = (currentHour - end + 24) % 24
                if (distToStart <= distToEnd) start else end
            }
        }
        return zoned.withHour(adjustedHour).toInstant()
    }
}

@Singleton
class SchedulerOrchestrator @Inject constructor(
    private val ruleBased: RuleBasedSchedulerPolicy,
    private val adaptive: AdaptiveSchedulerPolicy,
    private val preEvent: PreEventSchedulerPolicy,
    private val alarmRegistrar: AlarmRegistrar,
    private val taskRepository: TaskRepository,
    private val settingsRepository: SettingsRepository,
    private val rlTrainer: RlTrainer,
) {
    suspend fun schedule(task: Task, context: SchedulingContext): Pair<SchedulerMode, List<ReminderEvent>> {
        if (task.primitiveType == PrimitiveType.EVENT) {
            return SchedulerMode.RULE_BASED to preEvent.schedule(task, context)
        }

        if (task.primitiveType == PrimitiveType.DERIVED_TASK_EVENT) {
            val eventTask = task.copy(
                primitiveType = PrimitiveType.EVENT,
                deadline = task.eventStartTime ?: task.deadline,
            )
            val eventReminders = preEvent.schedule(eventTask, context)
            val (_, taskReminders) = scheduleAdaptiveOrRule(
                task.copy(
                    primitiveType = PrimitiveType.TASK,
                    preEventProfile = null,
                    eventStartTime = null,
                ),
                context,
                settingsRepository.current().adaptiveSchedulingEnabled,
            )

            val merged = (eventReminders + taskReminders)
                .distinctBy { it.scheduledTime to it.reminderLabel }
                .sortedBy { it.scheduledTime }
                .mapIndexed { index, reminder ->
                    reminder.copy(
                        sequenceNumber = index + 1,
                        alarmManagerId = stableAlarmId(task.id, index),
                    )
                }
            return SchedulerMode.RULE_BASED to merged
        }

        val settings = settingsRepository.current()
        return when {
            settings.rlSchedulingEnabled -> {
                val recommendation = rlTrainer.recommend(task, context)
                if (recommendation != null) {
                    val minValidTime = context.now.plusSeconds(30)
                    val primaryTime = context.now
                        .plus(Duration.ofHours(recommendation.nextReminderDelayHours.toLong()))
                        .coerceAtLeast(minValidTime)

                    val candidateTimes = mutableListOf<Instant>()
                    if (primaryTime.isBefore(task.deadline)) {
                        candidateTimes.add(primaryTime)
                    }

                    val safety15 = task.deadline.minus(Duration.ofMinutes(15))
                    if (safety15.isAfter(minValidTime)) {
                        candidateTimes.add(safety15)
                    }

                    val safety5 = task.deadline.minus(Duration.ofMinutes(5))
                    if (safety5.isAfter(minValidTime)) {
                        candidateTimes.add(safety5)
                    }

                    val finalTimes = if (candidateTimes.isEmpty() && task.deadline.isAfter(minValidTime)) {
                        listOf(task.deadline.minus(Duration.ofMinutes(1)).coerceAtLeast(minValidTime))
                    } else {
                        candidateTimes.distinct().filter { it.isBefore(task.deadline) }.sorted()
                    }

                    val reminders = finalTimes.mapIndexed { index, time ->
                        ReminderEvent(
                            taskId = task.id,
                            scheduledTime = time,
                            sequenceNumber = index + 1,
                            alarmManagerId = stableAlarmId(task.id, index),
                            primitiveType = PrimitiveType.TASK,
                            preEventProfile = null,
                            minutesBeforeAnchor = Duration.between(time, task.deadline).toMinutes(),
                            schedulerMode = SchedulerMode.RL,
                        )
                    }

                    SchedulerMode.RL to reminders
                } else {
                    scheduleAdaptiveOrRule(task, context, settings.adaptiveSchedulingEnabled)
                }
            }

            else -> scheduleAdaptiveOrRule(task, context, settings.adaptiveSchedulingEnabled)
        }
    }

    private suspend fun scheduleAdaptiveOrRule(
        task: Task,
        context: SchedulingContext,
        adaptiveEnabled: Boolean,
    ): Pair<SchedulerMode, List<ReminderEvent>> {
        return if (adaptiveEnabled && context.profile.totalInteractions >= 12) {
            SchedulerMode.ADAPTIVE to adaptive.schedule(task, context)
        } else {
            SchedulerMode.RULE_BASED to ruleBased.schedule(task, context)
        }
    }

    suspend fun persistSchedule(taskId: String, reminders: List<ReminderEvent>) {
        alarmRegistrar.cancelForTask(taskId)
        taskRepository.replaceReminders(taskId, reminders)
        if (reminders.isNotEmpty()) {
            alarmRegistrar.register(reminders)
        }
    }
}

internal fun stableAlarmId(taskId: String, index: Int): Int = (taskId.hashCode() * 31) + index

private fun Instant.coerceAtLeast(minimum: Instant): Instant = if (this.isBefore(minimum)) minimum else this
