package com.orka.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.orka.data.execution.AlarmRefreshWorker
import com.orka.data.execution.RlTrainingWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val ALARM_REFRESH_WORK_NAME = "orka-alarm-refresh"
private const val RL_TRAINING_WORK_NAME = "orka-rl-training"

@HiltAndroidApp
class OrkaApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        scheduleBackgroundWork()
    }

    private fun scheduleBackgroundWork() {
        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            ALARM_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AlarmRefreshWorker>(6, TimeUnit.HOURS).build(),
        )
        workManager.enqueueUniquePeriodicWork(
            RL_TRAINING_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RlTrainingWorker>(12, TimeUnit.HOURS).build(),
        )
    }
}
