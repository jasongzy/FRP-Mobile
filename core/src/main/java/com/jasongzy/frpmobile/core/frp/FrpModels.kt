package com.jasongzy.frpmobile.core.frp

enum class FrpType {
    Client,
    Server,
}

enum class FrpInstanceStatus {
    Stopped,
    Stopping,
    Running,
    Failed,
}

const val DEFAULT_THEME_SEED_COLOR: Int = -11567226

data class FrpProfile(
    val id: String,
    val name: String,
    val type: FrpType,
    val toml: String,
    val autoStart: Boolean,
    val updatedAt: Long,
    val files: Map<String, ByteArray> = emptyMap(),
    val retry: Boolean = false,
    val storageError: Boolean = false,
    val networkReconnect: Boolean = false,
)

data class FrpRuntimeState(
    val id: String,
    val type: FrpType,
    val state: FrpInstanceStatus,
    val lastError: String?,
    val desiredRunning: Boolean = false,
    val runToken: String = "",
) {
    val canStop: Boolean
        get() = desiredRunning || state == FrpInstanceStatus.Running || state == FrpInstanceStatus.Stopping
}

sealed interface FrpRuntimeQueryResult {
    data class Success(val states: List<FrpRuntimeState>) : FrpRuntimeQueryResult
    data class Failure(val message: String) : FrpRuntimeQueryResult
}

data class FrpLog(
    val instanceId: String,
    val type: String,
    val level: String,
    val message: String,
    val time: Long,
    val uid: Long = 0,
)

data class FrpResult(
    val code: String?,
    val message: String,
) {
    val isSuccess: Boolean = code == null
    val isInvalidToml: Boolean = code == "INVALID_TOML"
    val isTlsFileMissing: Boolean = code == "TLS_FILE_MISSING"
    val isRetryable: Boolean get() = !isSuccess && !isInvalidToml && !isTlsFileMissing &&
        code !in setOf("CONFIG_DECRYPT_FAILED", "CONFIG_UNAVAILABLE", "PROFILE_MISSING", "MODULE_DISABLED")

    companion object {
        fun fromRaw(raw: String?): FrpResult {
            val value = raw.orEmpty()
            if (value.isBlank()) return FrpResult(code = null, message = "")
            val code = value.substringBefore(":", missingDelimiterValue = value).trim()
            return FrpResult(code = code.ifBlank { "UNKNOWN" }, message = value)
        }
    }
}

enum class LanguageMode {
    System,
    Chinese,
    English,
}

enum class ThemeMode { System, Light, Dark }

enum class RecoveryReason { AutoRetry, Network }
