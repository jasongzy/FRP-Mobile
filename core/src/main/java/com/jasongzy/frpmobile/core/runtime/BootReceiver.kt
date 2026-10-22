package com.jasongzy.frpmobile.core.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jasongzy.frpmobile.core.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8000) {
                    val repository = AppGraph.repository(context)
                    repository.ensureRuntimeReady()
                    val settings = repository.settings.first()
                    if (settings.bootStartEnabled && !settings.rootMode) {
                        runCatching {
                            val result = FrpForegroundService.restoreDesired(context).await()
                            if (!result.isSuccess) repository.setPendingStart(true)
                        }.onFailure {
                            if (it is CancellationException) throw it
                            repository.setPendingStart(true)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
