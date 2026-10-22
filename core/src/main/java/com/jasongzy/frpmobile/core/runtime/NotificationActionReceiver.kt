package com.jasongzy.frpmobile.core.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FrpForegroundService.ACTION_STOP_ALL) return
        val pending = goAsync()
        val command = FrpForegroundService.stopAll(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8000) { command.join() }
            } finally {
                pending.finish()
            }
        }
    }
}
