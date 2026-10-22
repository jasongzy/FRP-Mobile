package com.jasongzy.frpmobile.core.runtime

import com.jasongzy.frpmobile.core.R
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jasongzy.frpmobile.core.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class FrpTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { AppGraph.repository(this) }
    private var updates: Job? = null
    override fun onStartListening() {
        updates?.cancel()
        updates = scope.launch {
            if (repository.settings.first().tileProfileId.isNotEmpty()) repository.syncRuntimeStates()
            combine(repository.settings, repository.profiles, repository.runtimeStates) { settings, profiles, states ->
                profiles.firstOrNull { it.id == settings.tileProfileId } to states
            }.distinctUntilChanged().collect { (profile, states) ->
                qsTile?.apply {
                    label = profile?.name ?: getString(R.string.tile_name)
                    state = when {
                        profile == null -> Tile.STATE_INACTIVE
                        states.any { it.id == profile.id && it.canStop } -> Tile.STATE_ACTIVE
                        else -> Tile.STATE_INACTIVE
                    }
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() { updates?.cancel(); updates = null }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onClick() {
        unlockAndRun {
            scope.launch {
                val settings = repository.settings.first()
                val profile = repository.getProfile(settings.tileProfileId)
                if (profile == null) {
                    openApp()
                    return@launch
                }
                repository.syncRuntimeStates()
                val canStop = repository.runtimeStates.first().any { it.id == profile.id && it.canStop }
                if (repository.settings.first().tileProfileId != profile.id) return@launch
                if (!canStop && (profile.storageError ||
                    (!settings.rootMode && !FrpRuntimePermissions.canStart(this@FrpTileService)))) {
                    openApp()
                    return@launch
                }
                val result = if (canStop) FrpForegroundService.stopProfile(this@FrpTileService, profile.id).await()
                else FrpForegroundService.startProfile(this@FrpTileService, profile.id).await()
                if (!result.isSuccess) openApp()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ) else startActivityAndCollapse(intent)
    }
}
