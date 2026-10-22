package com.jasongzy.frpmobile.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.withTransaction
import com.jasongzy.frpmobile.core.frp.DEFAULT_THEME_SEED_COLOR
import com.jasongzy.frpmobile.core.frp.LanguageMode
import com.jasongzy.frpmobile.core.frp.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val LOG_RETENTION_DAYS = 1..30

@Entity(tableName = "settings")
data class FrpSettings(
    @PrimaryKey val id: Int = 0,
    val bootStartEnabled: Boolean = false,
    val networkReconnectEnabled: Boolean = true,
    val autoRetryEnabled: Boolean = false,
    val screenOffKeepAliveEnabled: Boolean = false,
    val logRetentionDays: Int = 7,
    val themeSeedColor: Int = DEFAULT_THEME_SEED_COLOR,
    val languageMode: LanguageMode = LanguageMode.System,
    val pendingStart: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val rootMode: Boolean = false,
    val automationToken: String = "",
    val tileProfileId: String = "",
    val runtimeSyncPending: Boolean = false,
    @ColumnInfo(defaultValue = "0") val launchStartEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "0") val excludeFromRecents: Boolean = false,
    @ColumnInfo(defaultValue = "0") val externalReadEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "0") val externalWriteEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "-1") val runtimeBootCount: Int = -1,
    @ColumnInfo(defaultValue = "0") val liveUpdatesEnabled: Boolean = false,
)

interface SettingsGateway {
    suspend fun setLaunchStartEnabled(enabled: Boolean)
    suspend fun setExcludeFromRecents(enabled: Boolean)
    suspend fun setLiveUpdatesEnabled(enabled: Boolean)
    suspend fun setExternalReadEnabled(enabled: Boolean)
    suspend fun setExternalWriteEnabled(enabled: Boolean)
    suspend fun setRuntimeBootCount(count: Int)
    val settings: Flow<FrpSettings>

    suspend fun setBootStartEnabled(enabled: Boolean)
    suspend fun setNetworkReconnectEnabled(enabled: Boolean)
    suspend fun setAutoRetryEnabled(enabled: Boolean)
    suspend fun setScreenOffKeepAliveEnabled(enabled: Boolean)
    suspend fun setLogRetentionDays(days: Int)
    suspend fun setThemeSeedColor(color: Int)
    suspend fun setLanguageMode(mode: LanguageMode)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setRootMode(enabled: Boolean)
    suspend fun setAutomationToken(encrypted: String)
    suspend fun setTileProfileId(id: String)
    suspend fun setRuntimeSyncPending(pending: Boolean)
    suspend fun setPendingStart(pending: Boolean)
}


class SettingsStore(private val database: FrpDatabase) : SettingsGateway {
    private val dao = database.frpDao()
    override val settings: Flow<FrpSettings> = dao.observeSettings().map { it ?: FrpSettings() }
    override suspend fun setLaunchStartEnabled(enabled: Boolean) = update { it.copy(launchStartEnabled = enabled) }
    override suspend fun setExcludeFromRecents(enabled: Boolean) = update { it.copy(excludeFromRecents = enabled) }
    override suspend fun setLiveUpdatesEnabled(enabled: Boolean) = update { it.copy(liveUpdatesEnabled = enabled) }
    override suspend fun setExternalReadEnabled(enabled: Boolean) = update { it.copy(externalReadEnabled = enabled) }
    override suspend fun setExternalWriteEnabled(enabled: Boolean) = update { it.copy(externalWriteEnabled = enabled) }
    override suspend fun setRuntimeBootCount(count: Int) = update { it.copy(runtimeBootCount = count) }
    override suspend fun setBootStartEnabled(enabled: Boolean) = update { it.copy(bootStartEnabled = enabled) }
    override suspend fun setNetworkReconnectEnabled(enabled: Boolean) = update { it.copy(networkReconnectEnabled = enabled) }
    override suspend fun setAutoRetryEnabled(enabled: Boolean) = update { it.copy(autoRetryEnabled = enabled) }
    override suspend fun setScreenOffKeepAliveEnabled(enabled: Boolean) = update { it.copy(screenOffKeepAliveEnabled = enabled) }
    override suspend fun setLogRetentionDays(days: Int) {
        require(days in LOG_RETENTION_DAYS) { "Invalid retention period" }
        update { it.copy(logRetentionDays = days) }
    }
    override suspend fun setThemeSeedColor(color: Int) = update { it.copy(themeSeedColor = color) }
    override suspend fun setLanguageMode(mode: LanguageMode) = update { it.copy(languageMode = mode) }
    override suspend fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    override suspend fun setRootMode(enabled: Boolean) = update { it.copy(rootMode = enabled) }
    override suspend fun setAutomationToken(encrypted: String) = update { it.copy(automationToken = encrypted) }
    override suspend fun setTileProfileId(id: String) = update { it.copy(tileProfileId = id) }
    override suspend fun setRuntimeSyncPending(pending: Boolean) = update { it.copy(runtimeSyncPending = pending) }
    override suspend fun setPendingStart(pending: Boolean) = update { it.copy(pendingStart = pending) }

    private suspend fun update(transform: (FrpSettings) -> FrpSettings) {
        database.withTransaction { dao.upsertSettings(transform(dao.getSettings() ?: FrpSettings())) }
    }
}
