package com.jasongzy.frpmobile.core.runtime

import com.jasongzy.frpmobile.core.R
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jasongzy.frpmobile.core.data.AppGraph
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

object RuntimeNotifications {
    const val SERVICE_ID = 22333
    private const val MODULE_ID = 22334
    private const val CHANNEL_ID = "frp_runtime"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun build(context: Context, runningCount: Int, liveUpdates: Boolean = false): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, flags)
        }
        val stopAllIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, NotificationActionReceiver::class.java).setAction(FrpForegroundService.ACTION_STOP_ALL),
            flags,
        )
        val logsIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(FrpForegroundService.EXTRA_START_DESTINATION, FrpForegroundService.DESTINATION_LOGS)
        }?.let { PendingIntent.getActivity(context, 2, it, flags) }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_frp)
            .setContentTitle(context.getString(R.string.notification_running_title))
            .setContentText(context.getString(R.string.notification_running_content, runningCount))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notification_stop_all), stopAllIntent)
        if (liveUpdates && runningCount > 0) {
            builder.setRequestPromotedOngoing(true)
                .setShortCriticalText(context.getString(R.string.notification_running_short, runningCount))
        }
        if (openAppIntent != null) {
            builder.setContentIntent(openAppIntent)
            builder.addAction(0, context.getString(R.string.notification_open_app), openAppIntent)
        }
        if (logsIntent != null) builder.addAction(0, context.getString(R.string.notification_view_logs), logsIntent)
        return builder.build()
    }

    suspend fun observeModule(context: Context) {
        val repository = AppGraph.repository(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(context)
        try {
            if (repository.settings.first().rootMode) repository.syncRuntimeStates()
            combine(repository.settings, repository.runtimeStates) { settings, states ->
                val count = FrpRuntimePolicy.moduleNotificationCount(settings.rootMode, states)
                Triple(settings.rootMode && (settings.runtimeSyncPending || count != null), count, settings.liveUpdatesEnabled)
            }.distinctUntilChanged().collectLatest { (active, count, liveUpdates) ->
                if (count == null) {
                    manager.cancel(MODULE_ID)
                } else if (Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    manager.notify(MODULE_ID, build(context, count, liveUpdates))
                }
                while (active && currentCoroutineContext().isActive) {
                    repository.syncRuntimeStates()
                    delay(2000)
                }
            }
        } finally {
            manager.cancel(MODULE_ID)
        }
    }
}
