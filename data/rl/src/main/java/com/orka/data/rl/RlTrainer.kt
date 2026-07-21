package com.orka.data.rl

import android.content.Context
import com.orka.core.database.TaskDao
import com.orka.core.database.asExternalModel
import com.orka.core.model.BehaviorProfile
import com.orka.core.model.BehaviorProfileRepository
import com.orka.core.model.InteractionEvent
import com.orka.core.model.InteractionType
import com.orka.core.model.RlReadiness
import com.orka.core.model.RlRecommendation
import com.orka.core.model.RlTrainer
import com.orka.core.model.RlTrainingSummary
import com.orka.core.model.SchedulingContext
import com.orka.core.model.Task
import com.orka.core.model.TaskStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class DefaultRlTrainer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: BehaviorProfileRepository,
    private val taskDao: TaskDao,
    private val interactionDao: com.orka.core.database.InteractionDao,
) : RlTrainer {

    private val actionOptions = intArrayOf(1, 2, 4, 6, 12, 24, 48, 72)

    override fun observeReadiness(): Flow<RlReadiness> = profileRepository.observeProfile().map { profile ->
        if (profile.totalInteractions >= 40 && profile.totalCompletions >= 10) {
            RlReadiness.Ready
        } else {
            RlReadiness.NotReady("Needs 40 interactions and 10 completions before RL mode can activate.")
        }
    }

    override suspend fun maybeTrain(): RlTrainingSummary {
        val profile = profileRepository.getProfile()
        val ready = profile.totalInteractions >= 40 && profile.totalCompletions >= 10
        if (!ready) {
            return RlTrainingSummary(
                trained = false,
                episodesUsed = 0,
                message = "Not enough data to train the RL policy yet. Needs 40 interactions and 10 completions."
            )
        }

        val taskEntities = taskDao.getAllTasks()
        val interactionEntities = interactionDao.getAllInteractions()

        val interactionsByTask = interactionEntities.groupBy { it.taskId }
        val weights = loadWeights(context)

        var episodesUsed = 0

        repeat(10) {
            for (te in taskEntities) {
                val task = te.asExternalModel()
                val taskInteractions = interactionsByTask[task.id]?.sortedBy { it.timestamp } ?: continue
                if (taskInteractions.isEmpty()) continue

                episodesUsed++
                var consecutiveSnoozes = 0

                for (j in 0 until taskInteractions.size - 1) {
                    val currentInt = taskInteractions[j].asExternalModel()
                    val nextInt = taskInteractions[j + 1].asExternalModel()

                    val s = extractStateVector(task, profile, currentInt, consecutiveSnoozes, currentInt.timestamp)

                    if (currentInt.type.name.startsWith("SNOOZE")) {
                        consecutiveSnoozes++
                    } else {
                        consecutiveSnoozes = 0
                    }

                    val hoursDelay = java.time.Duration.between(currentInt.timestamp, nextInt.timestamp).toHours().toInt().coerceAtLeast(1)
                    val a = getActionIndex(hoursDelay)

                    var r = 0f
                    when (nextInt.type) {
                        InteractionType.START_TASK -> r += 2.0f
                        InteractionType.IGNORE -> r -= 0.5f
                        // A stronger negative signal than a snooze/ignore: the user gave up on
                        // the task entirely rather than just delaying it.
                        InteractionType.DISMISS_TASK -> r -= 1.0f
                        else -> {
                            if (nextInt.type.name.startsWith("SNOOZE")) {
                                r -= 0.5f
                            }
                        }
                    }

                    val nextHour = nextInt.timestamp.atZone(java.time.ZoneId.of("Asia/Kolkata")).hour
                    if (nextHour < profile.productiveStartHour || nextHour > profile.productiveEndHour) {
                        r -= 0.3f
                    }

                    val isTerminal = nextInt.type == InteractionType.MARK_DONE || j + 1 == taskInteractions.size - 1
                    val sPr = if (isTerminal) null else extractStateVector(task, profile, nextInt, consecutiveSnoozes, nextInt.timestamp)

                    updateQ(weights, s, a, r, sPr)
                }

                if (task.status == TaskStatus.COMPLETED && taskInteractions.isNotEmpty()) {
                    val lastInt = taskInteractions.last().asExternalModel()
                    val s = extractStateVector(task, profile, lastInt, consecutiveSnoozes, lastInt.timestamp)
                    val a = bestAction(weights, s)

                    var r = 0f
                    val completedAt = task.completedAt
                    if (completedAt != null && !completedAt.isAfter(task.deadline)) {
                        r += 10.0f
                    } else {
                        r += 3.0f
                    }

                    val totalReminders = taskInteractions.size
                    if (totalReminders > 3) {
                        r -= (totalReminders - 3) * 0.2f
                    }

                    updateQ(weights, s, a, r, null)
                }
            }
        }

        saveWeights(context, weights)

        return RlTrainingSummary(
            trained = true,
            episodesUsed = episodesUsed,
            message = "On-device reinforcement learning policy successfully updated with $episodesUsed episodes."
        )
    }

    override suspend fun recommend(task: Task, context: SchedulingContext): RlRecommendation? {
        val profile = profileRepository.getProfile()
        val ready = profile.totalInteractions >= 40 && profile.totalCompletions >= 10
        if (!ready) return null

        val weights = loadWeights(this.context)
        val s = extractStateVector(task, profile, null, 0, context.now)
        val bestActIdx = bestAction(weights, s)
        val nextDelay = actionOptions[bestActIdx]

        val qValues = actionOptions.indices.map { dotProduct(weights[it], s) }
        val maxQ = qValues[bestActIdx]
        val minQ = qValues.minOrNull() ?: 0f
        val range = maxQ - minQ
        val confidence = if (range > 0f) {
            (maxQ / (range + 0.01f)).coerceIn(0.5f, 0.95f)
        } else {
            0.62f
        }

        return RlRecommendation(nextReminderDelayHours = nextDelay, confidence = confidence)
    }

    private fun extractStateVector(
        task: Task,
        profile: BehaviorProfile,
        interaction: InteractionEvent?,
        consecutiveSnoozes: Int,
        now: Instant
    ): FloatArray {
        val state = FloatArray(16)

        val deadline = task.deadline
        val diffHours = java.time.Duration.between(now, deadline).toHours().toFloat()
        val daysLeft = (diffHours / (24f * 14f)).coerceIn(0f, 1f)
        state[0] = daysLeft

        state[1] = (task.estimatedEffortMinutes.toFloat() / 180f).coerceIn(0f, 1f)

        val catIndex = task.category.ordinal
        if (catIndex in 0..6) {
            state[2 + catIndex] = 1f
        }

        val zdt = now.atZone(java.time.ZoneId.of("Asia/Kolkata"))
        val hour = zdt.hour
        val timeAngle = 2.0 * Math.PI * hour / 24.0
        state[9] = Math.sin(timeAngle).toFloat()
        state[10] = Math.cos(timeAngle).toFloat()

        val day = zdt.dayOfWeek.value
        val dayAngle = 2.0 * Math.PI * day / 7.0
        state[11] = Math.sin(dayAngle).toFloat()
        state[12] = Math.cos(dayAngle).toFloat()

        state[13] = profile.categorySnoozeRates[task.category] ?: profile.snoozeRate
        state[14] = (consecutiveSnoozes.toFloat() / 5f).coerceIn(0f, 1f)
        state[15] = (task.urgencyScore / 5f).coerceIn(0f, 1f)

        return state
    }

    private fun getActionIndex(hours: Int): Int {
        var minDiff = Int.MAX_VALUE
        var bestIdx = 3
        for (i in actionOptions.indices) {
            val diff = Math.abs(actionOptions[i] - hours)
            if (diff < minDiff) {
                minDiff = diff
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun bestAction(weights: Array<FloatArray>, s: FloatArray): Int {
        var bestIdx = 0
        var maxQ = -Float.MAX_VALUE
        for (i in actionOptions.indices) {
            val q = dotProduct(weights[i], s)
            if (q > maxQ) {
                maxQ = q
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun updateQ(
        weights: Array<FloatArray>,
        s: FloatArray,
        a: Int,
        r: Float,
        sPr: FloatArray?,
        alpha: Float = 0.05f,
        gamma: Float = 0.9f
    ) {
        val qCurrent = dotProduct(weights[a], s)
        val maxQNext = if (sPr != null) {
            actionOptions.indices.maxOf { nextA -> dotProduct(weights[nextA], sPr) }
        } else {
            0f
        }
        val target = r + gamma * maxQNext
        val tdError = target - qCurrent

        for (i in s.indices) {
            weights[a][i] += alpha * tdError * s[i]
            weights[a][i] = weights[a][i].coerceIn(-10f, 10f)
        }
    }

    private fun dotProduct(w: FloatArray, x: FloatArray): Float {
        var sum = 0f
        for (i in w.indices) {
            sum += w[i] * x[i]
        }
        return sum
    }

    private fun loadWeights(context: Context): Array<FloatArray> {
        val file = File(context.filesDir, "rl_weights.txt")
        if (!file.exists()) {
            return Array(8) { FloatArray(16) }
        }
        return try {
            val lines = file.readLines()
            val arr = Array(8) { FloatArray(16) }
            for (i in 0 until 8) {
                val parts = lines.getOrNull(i)?.split(",") ?: continue
                for (j in 0 until 16) {
                    arr[i][j] = parts.getOrNull(j)?.toFloatOrNull() ?: 0f
                }
            }
            arr
        } catch (e: Exception) {
            Array(8) { FloatArray(16) }
        }
    }

    private fun saveWeights(context: Context, weights: Array<FloatArray>) {
        val file = File(context.filesDir, "rl_weights.txt")
        try {
            val content = weights.joinToString("\n") { row ->
                row.joinToString(",") { it.toString() }
            }
            file.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
