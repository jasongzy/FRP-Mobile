package com.jasongzy.frpmobile.integration.home

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.core.net.toUri
import com.jasongzy.frpmobile.core.runtime.AutomationCommand
import com.jasongzy.frpmobile.integration.targetName
import com.jasongzy.frpmobile.R
import com.jasongzy.frpmobile.core.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

class HomeWidgetProvider : AppWidgetProvider() {
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) onUpdate(context, AppWidgetManager.getInstance(context), HomeWidgets.ids(context))
        else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = AppGraph.repository(context)
                val localized = context.homeContext(repository.settings.first().languageMode)
                if (Build.VERSION.SDK_INT >= 25) HomeShortcuts.refresh(localized, repository.profiles.first().associate { it.id to it.name })
                HomeWidgets.refresh(localized, ids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("HomeWidgets", "Could not update widgets", e)
            }
            finally { pending.finish() }
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { id ->
            HomeWidgets.preferences(context).edit { remove(id.toString()) }
            PendingIntent.getActivity(context, id, HomeWidgets.actionIntent(context, id),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.cancel()
        }
    }
}

internal object HomeWidgets {
    fun preferences(context: Context) = context.getSharedPreferences("home_widgets", Context.MODE_PRIVATE)

    fun read(context: Context, id: Int) = decodeHomeAction(preferences(context).getString(id.toString(), null))

    @SuppressLint("UseKtx") // Preserve the commit result to report storage failures.
    fun save(context: Context, id: Int, action: AutomationCommand) {
        check(preferences(context).edit().putString(id.toString(), action.encodeHomeAction()).commit()) { "WIDGET_SAVE_FAILED" }
    }

    fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, HomeWidgetProvider::class.java))

    fun actionIntent(context: Context, id: Int) = Intent(context, HomeActionActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData("frpmobile://widget/$id".toUri())
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    suspend fun refresh(context: Context, ids: IntArray = ids(context)) {
        if (ids.isEmpty()) return
        val repository = AppGraph.repository(context)
        val manager = AppWidgetManager.getInstance(context)
        for (id in ids) {
            val action = read(context, id)
            val name = action?.profileId?.takeIf { it.isNotEmpty() }?.let { repository.getProfile(it)?.name }
            val options = manager.getAppWidgetOptions(id)
            fun views(width: Int, height: Int): RemoteViews {
                val views = RemoteViews(context.packageName, R.layout.home_widget)
                val side = minOf(width, height, 96)
                val density = context.resources.displayMetrics.density
                val horizontal = ((width - side) * density / 2).roundToInt()
                val vertical = ((height - side) * density / 2).roundToInt()
                views.setViewPadding(R.id.widget_container, horizontal, vertical, horizontal, vertical)
                views.setTextViewText(R.id.widget_profile, action?.homeTargetName(context, name) ?: context.getString(R.string.home_profile_missing))
                views.setTextViewText(R.id.widget_operation, context.getString(action?.operation?.let(::homeOperationLabel) ?: R.string.home_widget))
                views.setImageViewResource(R.id.widget_icon, action?.operation?.let(::operationDrawable) ?: R.drawable.ic_home_toggle)
                views.setContentDescription(R.id.widget_content, context.getString(R.string.home_action_label,
                    action?.targetName(context, name) ?: context.getString(R.string.home_profile_missing),
                    context.getString(action?.operation?.let(::homeOperationLabel) ?: R.string.home_widget)))
                views.setOnClickPendingIntent(R.id.widget_content, PendingIntent.getActivity(context, id,
                    actionIntent(context, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                return views
            }
            manager.updateAppWidget(id, RemoteViews(
                views(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)),
                views(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)),
            ))
        }
    }
}
