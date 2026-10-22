package com.jasongzy.frpmobile

import android.app.Application
import android.os.Build
import android.util.Log
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.integration.home.HomeShortcuts
import com.jasongzy.frpmobile.integration.home.HomeWidgets
import com.jasongzy.frpmobile.integration.home.homeContext
import com.jasongzy.frpmobile.core.runtime.LogCleanupWorker
import com.jasongzy.frpmobile.core.runtime.RuntimeNotifications
import com.jasongzy.frpmobile.core.runtime.RuntimeRecoveryMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class FrpMobileApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        LogCleanupWorker.schedule(this)
        RuntimeRecoveryMonitor.start(this)
        scope.launch { RuntimeNotifications.observeModule(this@FrpMobileApplication) }
        scope.launch {
            val repository = AppGraph.repository(this@FrpMobileApplication)
            combine(repository.profiles, repository.settings) { profiles, settings ->
                profiles.associate { it.id to it.name } to settings.languageMode
            }.distinctUntilChanged().collect { (profiles, language) ->
                try {
                    val context = homeContext(language)
                    if (Build.VERSION.SDK_INT >= 25) HomeShortcuts.refresh(context, profiles)
                    HomeWidgets.refresh(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("HomeActions", "Could not refresh home actions", e)
                }
            }
        }
    }
}
