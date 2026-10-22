package com.jasongzy.frpmobile.integration.home

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.jasongzy.frpmobile.R
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.runtime.FrpForegroundService
import com.jasongzy.frpmobile.integration.targetName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActionViewModel(application: Application) : AndroidViewModel(application) {
    val message = MutableStateFlow<String?>(null)
    var started = false
        private set

    fun run(intent: Intent) {
        if (started) return
        started = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            var context = app.applicationContext
            try {
                val repository = AppGraph.repository(app)
                context = app.homeContext(repository.settings.first().languageMode)
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val action = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    withContext(Dispatchers.IO) { HomeWidgets.read(app, widgetId) }
                } else decodeHomeAction(intent.getStringExtra(EXTRA_HOME_ACTION))
                checkNotNull(action) { context.getString(R.string.home_profile_missing) }
                val profile = if (action.profileId.isEmpty()) null else repository.getProfile(action.profileId)
                check(action.profileId.isEmpty() || profile != null) { context.getString(R.string.home_profile_missing) }
                val result = FrpForegroundService.controlFromApp(context, action).await()
                message.value = when {
                    !result.isSuccess -> context.getString(R.string.settings_operation_failed, result.message)
                    action.operation == "toggle" && profile != null -> context.getString(
                        if (result.startedCount > 0) R.string.dashboard_profile_started else R.string.dashboard_profile_stopped,
                        profile.name,
                    )
                    action.operation == "toggle" -> listOfNotNull(
                        result.startedCount.takeIf { it > 0 }?.let {
                            context.resources.getQuantityString(R.plurals.home_profiles_started, it, it)
                        },
                        result.stoppedCount.takeIf { it > 0 }?.let {
                            context.resources.getQuantityString(R.plurals.home_profiles_stopped, it, it)
                        },
                    ).joinToString(" · ")
                    action.operation == "stopAll" -> context.getString(R.string.dashboard_all_stopped)
                    else -> context.getString(
                        when (action.operation) {
                            "start" -> R.string.dashboard_profile_started
                            "stop" -> R.string.dashboard_profile_stopped
                            "restart" -> R.string.dashboard_profile_restarted
                            else -> error("INVALID_OPERATION")
                        },
                        action.targetName(context, profile?.name),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = context.getString(R.string.settings_operation_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }
}

class HomeActionActivity : AppCompatActivity() {
    private val model: HomeActionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the command alive without covering or intercepting the current screen.
        window.setLayout(1, 1)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        if (savedInstanceState?.getBoolean("executing") == true && !model.started) {
            Toast.makeText(this, R.string.home_check_status, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        model.run(intent)
        lifecycleScope.launch {
            model.message.collect { message ->
                if (message != null) {
                    Toast.makeText(this@HomeActionActivity, message, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("executing", model.started)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Toast.makeText(this, R.string.home_action_busy, Toast.LENGTH_SHORT).show()
    }
}
