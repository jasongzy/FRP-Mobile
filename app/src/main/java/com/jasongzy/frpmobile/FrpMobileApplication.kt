package com.jasongzy.frpmobile

import android.app.Application
import com.jasongzy.frpmobile.core.runtime.LogCleanupWorker
import com.jasongzy.frpmobile.core.runtime.RuntimeNotifications
import com.jasongzy.frpmobile.core.runtime.RuntimeRecoveryMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FrpMobileApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        LogCleanupWorker.schedule(this)
        RuntimeRecoveryMonitor.start(this)
        scope.launch { RuntimeNotifications.observeModule(this@FrpMobileApplication) }
    }
}
