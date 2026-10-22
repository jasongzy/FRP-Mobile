package com.jasongzy.frpmobile.core.data

import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.frp.FrpType
import com.jasongzy.frpmobile.core.frp.LanguageMode
import com.jasongzy.frpmobile.core.frp.ThemeMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigBackupTest {
    private val backup = ConfigBackup(
        listOf(FrpProfile("sample", "示例", FrpType.Client, "unfinished draft =", true, 123,
            mapOf("key.pem" to byteArrayOf(0, 1, -1, 127)))),
        FrpSettings(themeMode = ThemeMode.Dark, languageMode = LanguageMode.Chinese, bootStartEnabled = true),
    )

    @Test fun roundTripPreservesDraftsBinaryCertificatesAndSettings() {
        val restored = ConfigBackupFormat.decode(ConfigBackupFormat.encode(backup))
        assertEquals(backup.settings, restored.settings)
        assertEquals(backup.profiles.single().toml, restored.profiles.single().toml)
        assertArrayEquals(backup.profiles.single().files["key.pem"], restored.profiles.single().files["key.pem"])
    }

    @Test fun rejectsUnsafeIdsDuplicatesAndFutureFormats() {
        val json = ConfigBackupFormat.encode(backup).toString(Charsets.UTF_8)
        listOf<(JSONObject) -> Unit>(
            { it.put("version", 2) },
            { it.put("version", 1.5) },
            { it.getJSONArray("profiles").getJSONObject(0).put("id", "../outside") },
            { it.getJSONArray("profiles").put(it.getJSONArray("profiles").getJSONObject(0)) },
            { it.getJSONArray("profiles").getJSONObject(0).getJSONObject("certificates").put("../outside", "aA==") },
        ).forEach { mutate ->
            val input = JSONObject(json).also(mutate).toString().toByteArray()
            assertThrows(IllegalArgumentException::class.java) { ConfigBackupFormat.decode(input) }
        }
    }

    @Test fun boundedInputRejectsBeforePersisting() {
        assertThrows(IllegalArgumentException::class.java) { ByteArray(17).inputStream().readLimited(16) }
    }

    @Test fun retentionMatchesTheEditableRange() {
        for (days in listOf(1, 30)) {
            val value = backup.copy(settings = backup.settings.copy(logRetentionDays = days))
            assertEquals(days, ConfigBackupFormat.decode(ConfigBackupFormat.encode(value)).settings.logRetentionDays)
        }
        for (days in listOf(0, 31, 365)) {
            val json = JSONObject(ConfigBackupFormat.encode(backup).toString(Charsets.UTF_8))
            json.getJSONObject("settings").put("logRetentionDays", days)
            assertThrows(IllegalArgumentException::class.java) { ConfigBackupFormat.decode(json.toString().toByteArray()) }
        }
    }
}
