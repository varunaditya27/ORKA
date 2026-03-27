package com.orka.data.rl

import com.orka.core.model.BehaviorProfileRepository
import com.orka.core.model.RlReadiness
import com.orka.core.model.RlRecommendation
import com.orka.core.model.RlTrainer
import com.orka.core.model.RlTrainingSummary
import com.orka.core.model.SchedulingContext
import com.orka.core.model.Task
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class DefaultRlTrainer @Inject constructor(
    private val profileRepository: BehaviorProfileRepository,
) : RlTrainer {
    override fun observeReadiness(): Flow<RlReadiness> = profileRepository.observeProfile().map { profile ->
        if (profile.totalInteractions >= 40 && profile.totalCompletions >= 10) {
            RlReadiness.Ready
        } else {
            RlReadiness.NotReady("Needs 40 interactions and 10 completions before RL mode can activate.")
        }
    }

    override suspend fun maybeTrain(): RlTrainingSummary {
        val profile = profileRepository.getProfile()
        val trained = profile.totalInteractions >= 40 && profile.totalCompletions >= 10
        return RlTrainingSummary(
            trained = trained,
            episodesUsed = profile.totalCompletions,
            message = if (trained) "Heuristic RL policy refreshed." else "Not enough data to train the RL policy yet.",
        )
    }

    override suspend fun recommend(task: Task, context: SchedulingContext): RlRecommendation? {
        val profile = profileRepository.getProfile()
        val ready = profile.totalInteractions >= 40 && profile.totalCompletions >= 10
        if (!ready) return null

        val categorySnoozeRate = profile.categorySnoozeRates[task.category] ?: profile.snoozeRate
        val nextDelay = when {
            categorySnoozeRate >= 0.7f -> 1
            categorySnoozeRate >= 0.45f -> 2
            task.estimatedEffortMinutes > 120 -> 4
            else -> 6
        }
        return RlRecommendation(nextReminderDelayHours = nextDelay, confidence = 0.62f)
    }
}
