package com.jasongzy.frpmobile.core.data

import kotlin.io.encoding.Base64
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.frp.FrpType
import com.jasongzy.frpmobile.core.frp.LanguageMode
import com.jasongzy.frpmobile.core.frp.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

data class ConfigBackup(val profiles: List<FrpProfile>, val settings: FrpSettings)

object ConfigBackupFormat {
    const val MAX_BYTES = 16 * 1024 * 1024

    fun encode(backup: ConfigBackup): ByteArray {
        require(backup.profiles.size <= 1000) { "Too many profiles" }
        val settings = backup.settings
        val data = JSONObject()
            .put("format", "frp-mobile").put("version", 1)
            .put("settings", JSONObject()
                .put("launchStart", settings.launchStartEnabled)
                .put("excludeFromRecents", settings.excludeFromRecents)
                .put("bootStart", settings.bootStartEnabled)
                .put("networkReconnect", settings.networkReconnectEnabled)
                .put("autoRetry", settings.autoRetryEnabled)
                .put("screenOffKeepAlive", settings.screenOffKeepAliveEnabled)
                .put("logRetentionDays", settings.logRetentionDays)
                .put("themeColor", settings.themeSeedColor)
                .put("theme", settings.themeMode.name)
                .put("language", settings.languageMode.name))
            .put("tileProfileId", settings.tileProfileId)
            .put("profiles", JSONArray(backup.profiles.map { profile ->
                check(!profile.storageError) { "CONFIG_DECRYPT_FAILED: ${profile.name}" }
                JSONObject().put("id", profile.id).put("name", profile.name).put("type", profile.type.name)
                    .put("toml", profile.toml).put("autoStart", profile.autoStart).put("updatedAt", profile.updatedAt)
                    .put("certificates", JSONObject(profile.files.mapValues {
                        Base64.encode(it.value)
                    }))
            })).toString(2).toByteArray()
        require(data.size <= MAX_BYTES) { "Backup exceeds size limit" }
        return data
    }

    fun decode(data: ByteArray): ConfigBackup {
        require(data.size <= MAX_BYTES) { "Backup exceeds size limit" }
        val root = JSONObject(data.decodeToString(throwOnInvalidSequence = true))
        require(root.getString("format") == "frp-mobile" && root.get("version") == 1) { "Unsupported backup format" }
        val list = root.getJSONArray("profiles")
        require(list.length() <= 1000) { "Too many profiles" }
        val profiles = List(list.length()) { index ->
            val value = list.getJSONObject(index)
            val id = value.getString("id")
            val name = value.getString("name")
            val toml = value.getString("toml")
            require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "Invalid profile ID" }
            require(name.isNotBlank() && name.length <= 200) { "Invalid profile name" }
            require(toml.toByteArray().size <= 1024 * 1024) { "Configuration exceeds size limit: $name" }
            val certificates = value.getJSONObject("certificates")
            require(certificates.length() <= 3) { "Too many certificates" }
            val files = certificates.keys().asSequence().associateWith { file ->
                require(TlsFileRole.entries.any { it.fileName == file }) { "Invalid certificate name" }
                val bytes = Base64.decode(certificates.getString(file))
                require(bytes.isNotEmpty() && bytes.size <= 1024 * 1024) { "Invalid certificate size" }
                bytes
            }
            FrpProfile(id, name, FrpType.valueOf(value.getString("type")), toml,
                value.getBoolean("autoStart"), value.getLong("updatedAt"), files)
        }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate profile ID" }
        val settings = root.getJSONObject("settings")
        val retention = settings.getInt("logRetentionDays")
        require(retention in LOG_RETENTION_DAYS) { "Invalid retention period" }
        val tileProfileId = root.getString("tileProfileId")
        require(tileProfileId.isEmpty() || profiles.any { it.id == tileProfileId }) { "Unknown quick settings profile" }
        return ConfigBackup(profiles, FrpSettings(
            launchStartEnabled = settings.getBoolean("launchStart"),
            excludeFromRecents = settings.getBoolean("excludeFromRecents"),
            bootStartEnabled = settings.getBoolean("bootStart"),
            networkReconnectEnabled = settings.getBoolean("networkReconnect"),
            autoRetryEnabled = settings.getBoolean("autoRetry"),
            screenOffKeepAliveEnabled = settings.getBoolean("screenOffKeepAlive"),
            logRetentionDays = retention,
            themeSeedColor = settings.getInt("themeColor"),
            themeMode = ThemeMode.valueOf(settings.getString("theme")),
            languageMode = LanguageMode.valueOf(settings.getString("language")),
            tileProfileId = tileProfileId,
        ))
    }
}
