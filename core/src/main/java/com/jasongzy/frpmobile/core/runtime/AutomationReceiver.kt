package com.jasongzy.frpmobile.core.runtime

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val token = intent.getStringExtra("token").orEmpty()
        val pending = goAsync()
        pending.resultCode = Activity.RESULT_CANCELED
        scope.launch {
            try {
                val command = AutomationCommand(
                    intent.getStringExtra("operation").orEmpty(),
                    intent.getStringExtra("profile_id").orEmpty(),
                    intent.getStringExtra("target").orEmpty(),
                )
                val completion = FrpForegroundService.control(context, command, token)
                val result = withTimeoutOrNull(8000) { completion.await() }
                pending.resultCode = if (result == null || result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED
                pending.resultData = result?.code ?: "ACCEPTED"
            } catch (e: Exception) {
                pending.resultCode = Activity.RESULT_CANCELED
                pending.resultData = e.message ?: "CONTROL_FAILED"
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.jasongzy.frpmobile.CONTROL"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
