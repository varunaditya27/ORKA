package com.orka.data.scheduler

import com.orka.core.model.AlarmRegistrar
import com.orka.core.model.BehaviorProfile
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

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

        return ruleBased.map { reminder ->
            reminder.copy(
                scheduledTime = alignToProductiveWindow(
                    reminder.scheduledTime.plus(Duration.ofHours(shiftHours.toLong())),
                    context.profile,
                ),
                schedulerMode = mode,
            )
        }.sortedBy { it.scheduledTime }
    }

    private fun alignToProductiveWindow(time: Instant, profile: BehaviorProfile): Instant {
        val zoned = time.atZone(java.time.ZoneId.systemDefault())
        val adjustedHour = zoned.hour.coerceIn(profile.productiveStartHour, profile.productiveEndHour)
        return zoned.withHour(adjustedHour).withMinute(0).withSecond(0).toInstant()
    }
}

@Singleton
class SchedulerOrchestrator @Inject constructor(
    private val ruleBased: RuleBasedSchedulerPolicy,
    private val adaptive: AdaptiveSchedulerPolicy,
    private val alarmRegistrar: AlarmRegistrar,
    private val taskRepository: TaskRepository,
    private val settingsRepository: SettingsRepository,
    private val rlTrainer: RlTrainer,
) {
    suspend fun schedule(task: Task, context: SchedulingContext): Pair<SchedulerMode, List<ReminderEvent>> {
        val settings = settingsRepository.current()
        return when {
            settings.rlSchedulingEnabled -> {
                val recommendation = rlTrainer.recommend(task, context)
                if (recommendation != null) {
                    SchedulerMode.RL to listOf(
                        ReminderEvent(
                            taskId = task.id,
                            scheduledTime = context.now.plus(Duration.ofHours(recommendation.nextReminderDelayHours.toLong())),
                            sequenceNumber = 1,
                            alarmManagerId = stableAlarmId(task.id, 0),
                            schedulerMode = SchedulerMode.RL,
                        ),
                    )
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
