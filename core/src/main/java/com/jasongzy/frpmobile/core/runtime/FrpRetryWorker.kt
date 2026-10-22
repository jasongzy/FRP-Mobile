package com.jasongzy.frpmobile.core.runtime

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.frp.RecoveryReason
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class FrpRetryWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    override suspend fun doWork(): Result {
        val profileId = inputData.getString(KEY_PROFILE_ID) ?: return Result.failure()
        val token = inputData.getString(KEY_TOKEN) ?: return Result.success()
        val attempt = inputData.getInt(KEY_ATTEMPT, 1)
        val reason = inputData.getString(KEY_REASON)?.let { value ->
            runCatching { RecoveryReason.valueOf(value) }.getOrNull()
        } ?: return Result.failure()
        val repository = AppGraph.repository(applicationContext)
        if (!repository.canRecover(profileId, token, reason)) return Result.success()
        val result = FrpForegroundService.recoverProfile(applicationContext, profileId, token, attempt, reason).await()
        if (result.isRetryable) enqueue(applicationContext, profileId, token, attempt + 1, reason)
        return Result.success()
    }

    companion object {
        private const val KEY_PROFILE_ID = "profile_id"
        private const val KEY_TOKEN = "run_token"
        private const val KEY_ATTEMPT = "attempt"
        private const val KEY_REASON = "reason"
        private const val TAG_RECOVERY = "frp_recovery"

        suspend fun enqueue(context: Context, profileId: String, token: String, attempt: Int, reason: RecoveryReason) {
            if (!AppGraph.repository(context).canRecover(profileId, token, reason)) return
            val delay = FrpRuntimePolicy.retryDelaySeconds(attempt) * 1_000
            val request = OneTimeWorkRequestBuilder<FrpRetryWorker>()
                .setInputData(
                    workDataOf(
                        KEY_PROFILE_ID to profileId,
                        KEY_TOKEN to token,
                        KEY_ATTEMPT to attempt,
                        KEY_REASON to reason.name,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(Random.nextLong(delay * 9 / 10, delay + 1), TimeUnit.MILLISECONDS)
                .addTag(TAG_RECOVERY)
                .addTag(profileTag(profileId))
                .addTag("frp_recovery_token_$token")
                .addTag(reasonTag(reason))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(profileId, token),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context, profileId: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag(profileTag(profileId))
        }

        fun cancel(context: Context, profileId: String, token: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(profileId, token))
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG_RECOVERY)
        }

        private fun uniqueWorkName(profileId: String, token: String): String = "frp_recovery_${profileId}_$token"
        private fun profileTag(profileId: String): String = "frp_recovery_profile_$profileId"
        private fun reasonTag(reason: RecoveryReason): String = "frp_recovery_${reason.name}"
    }
}
