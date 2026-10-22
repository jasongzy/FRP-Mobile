package com.jasongzy.frpmobile.core.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jasongzy.frpmobile.core.data.AppGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class LogCleanupWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    override suspend fun doWork(): Result {
        val repository = AppGraph.repository(applicationContext)
        val settings = repository.settings.first()
        repository.pruneLogs(settings.logRetentionDays)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "frp_log_cleanup"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun schedule(context: Context) {
            val app = context.applicationContext
            scope.launch {
                AppGraph.repository(app).oldestLogTime.map { it != null }.distinctUntilChanged().collect { hasLogs ->
                    val work = WorkManager.getInstance(app)
                    if (hasLogs) {
                        work.enqueueUniquePeriodicWork(
                            WORK_NAME,
                            ExistingPeriodicWorkPolicy.UPDATE,
                            PeriodicWorkRequestBuilder<LogCleanupWorker>(1, TimeUnit.DAYS).build(),
                        )
                    } else work.cancelUniqueWork(WORK_NAME)
                }
            }
        }
    }
}
