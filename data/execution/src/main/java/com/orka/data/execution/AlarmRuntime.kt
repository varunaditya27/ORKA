package com.orka.data.execution

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.orka.core.database.AlarmRegistryDao
import com.orka.core.database.AlarmRegistryEntity
import com.orka.core.model.ActionEmphasis
import com.orka.core.model.AlarmActionOption
import com.orka.core.model.AlarmActionResolver
import com.orka.core.model.AlarmRegistrar
import com.orka.core.model.InteractionEvent
import com.orka.core.model.InteractionType
import com.orka.core.model.ReminderEvent
import com.orka.core.model.Task
import com.orka.core.model.TaskRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val ORKA_ALARM_CHANNEL_ID = "orka_alarm"
private const val ORKA_ALARM_ACTION = "com.orka.app.ALARM_TRIGGERED"
private const val ORKA_OPEN_APP_ACTION = "com.orka.app.action.OPEN_APP"
private const val ORKA_SHOW_ALARM_ACTION = "com.orka.app.action.SHOW_ALARM"

@Singleton
class DefaultAlarmActionResolver @Inject constructor() : AlarmActionResolver {
    override fun resolve(
        task: Task,
        history: List<InteractionEvent>,
        now: Instant,
    ): List<AlarmActionOption> {
        val hoursRemaining = java.time.Duration.between(now, task.deadline).toHours()
        val consecutiveSnoozes = history.takeWhile { it.type.name.startsWith("SNOOZE") || it.type == InteractionType.IGNORE }.count()

        val contextAction = when {
            hoursRemaining <= 0 -> AlarmActionOption(
                InteractionType.RESCHEDULE,
                "Change deadline",
                ActionEmphasis.SECONDARY,
                requiresInput = true,
            )

            task.estimatedEffortMinutes > 120 && hoursRemaining > 48 -> AlarmActionOption(
                InteractionType.SPLIT_TASK,
                "Break this down",
                ActionEmphasis.SECONDARY,
            )

            hoursRemaining > 48 -> AlarmActionOption(
                InteractionType.SNOOZE_LONG,
                "Snooze 3 hours",
                ActionEmphasis.SECONDARY,
            )

            else -> AlarmActionOption(
                InteractionType.SNOOZE_SHORT,
                "Snooze 30 min",
                ActionEmphasis.SECONDARY,
            )
        }

        return listOfNotNull(
            AlarmActionOption(InteractionType.START_TASK, "Start Now", ActionEmphasis.PRIMARY),
            contextAction,
            AlarmActionOption(InteractionType.MARK_DONE, "Done", ActionEmphasis.SECONDARY),
            AlarmActionOption(
                InteractionType.ACKNOWLEDGE,
                if (consecutiveSnoozes >= 3) "I know, remind me later" else "Acknowledge",
                ActionEmphasis.TERTIARY,
            ),
        )
    }
}

@Singleton
class AlarmManagerRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager,
    private val notificationManager: NotificationManager,
    private val registryDao: AlarmRegistryDao,
    private val taskRepository: TaskRepository,
) : AlarmRegistrar {
    init {
        createChannel()
    }

    override suspend fun register(reminders: List<ReminderEvent>) {
        reminders.forEach { reminder ->
            val broadcastIntent = Intent(context, OrkaAlarmReceiver::class.java).apply {
                action = ORKA_ALARM_ACTION
                putExtra("reminder_id", reminder.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                reminder.alarmManagerId,
                broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val triggerAtMillis = reminder.scheduledTime.toEpochMilli()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        }

        registryDao.insertAll(
            reminders.map {
                AlarmRegistryEntity(
                    reminderId = it.id,
                    taskId = it.taskId,
                    scheduledTime = it.scheduledTime,
                    alarmManagerId = it.alarmManagerId,
                )
            },
        )
    }

    override suspend fun cancel(reminders: List<ReminderEvent>) {
        reminders.forEach { reminder ->
            val intent = Intent(context, OrkaAlarmReceiver::class.java).apply { action = ORKA_ALARM_ACTION }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                reminder.alarmManagerId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(pendingIntent)
            registryDao.deleteByReminder(reminder.id)
        }
    }

    override suspend fun cancelForTask(taskId: String) {
        val reminders = taskRepository.observeReminders(taskId)
        val current = reminders.first()
        cancel(current)
        registryDao.deleteForTask(taskId)
    }

    override suspend fun refreshAll() {
        // Runs on every boot and periodically via AlarmRefreshWorker. Registry rows for
        // reminders that already fired or whose time has passed must be pruned rather than
        // re-registered — otherwise setExactAndAllowWhileIdle() fires them again immediately.
        val now = Instant.now()
        val entities = registryDao.getAll()
        val remindersToRegister = mutableListOf<ReminderEvent>()
        val staleReminderIds = mutableListOf<String>()

        entities.forEach { entity ->
            val reminder = taskRepository.getReminder(entity.reminderId)
            if (reminder != null &&
                reminder.status == com.orka.core.model.ReminderStatus.SCHEDULED &&
                reminder.scheduledTime.isAfter(now)
            ) {
                remindersToRegister += reminder
            } else {
                staleReminderIds += entity.reminderId
            }
        }

        staleReminderIds.forEach { registryDao.deleteByReminder(it) }
        if (remindersToRegister.isNotEmpty()) {
            register(remindersToRegister)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ORKA_ALARM_CHANNEL_ID,
                "ORKA alarms",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Full-screen and heads-up alarm delivery for ORKA reminders"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}

@AndroidEntryPoint
class OrkaAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var taskRepository: TaskRepository

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra("reminder_id") ?: return
        val reminderAndTask = kotlinx.coroutines.runBlocking {
            val reminder = taskRepository.getReminder(reminderId)
            val task = reminder?.taskId?.let { taskRepository.getTask(it) }
            reminder to task
        }
        val notificationText = reminderAndTask.first?.reminderLabel
            ?: reminderAndTask.second?.title
            ?: "Task reminder triggered"

        kotlinx.coroutines.runBlocking {
            taskRepository.markReminderDelivered(reminderId, Instant.now())
        }
        val fullScreenIntent = Intent(ORKA_SHOW_ALARM_ACTION)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("reminder_id", reminderId)

        val pendingFullScreen = PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openAppIntent = PendingIntent.getActivity(
            context,
            reminderId.hashCode() + 1,
            Intent(ORKA_OPEN_APP_ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ORKA_ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_orka)
            .setContentTitle("ORKA")
            .setContentText(notificationText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .setFullScreenIntent(pendingFullScreen, true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(reminderId.hashCode(), notification)

        // setFullScreenIntent() above is the OS-sanctioned way to force this open, and is all
        // that's needed while the device is locked. This direct call additionally forces it open
        // when the screen is already unlocked (where the OS would otherwise only show a heads-up
        // notification) — but background-activity-start restrictions (Android 10+) can refuse it
        // in some states, so it must not be allowed to crash notification delivery, which already
        // succeeded above.
        try {
            context.startActivity(fullScreenIntent)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}

@AndroidEntryPoint
class OrkaBootReceiver : BroadcastReceiver() {
    @Inject lateinit var alarmRegistrar: AlarmRegistrar

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            kotlinx.coroutines.runBlocking {
                alarmRegistrar.refreshAll()
            }
        }
    }
}

@HiltWorker
class AlarmRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val alarmRegistrar: AlarmRegistrar,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        alarmRegistrar.refreshAll()
        Result.success()
    }.getOrElse { Result.retry() }
}

@HiltWorker
class RlTrainingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val rlTrainer: com.orka.core.model.RlTrainer,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        rlTrainer.maybeTrain()
        Result.success()
    }.getOrElse { Result.retry() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AlarmBindingsModule {
    @Binds
    abstract fun bindAlarmActionResolver(impl: DefaultAlarmActionResolver): AlarmActionResolver

    @Binds
    abstract fun bindAlarmRegistrar(impl: AlarmManagerRegistrar): AlarmRegistrar
}
