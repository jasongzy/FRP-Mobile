package com.jasongzy.frpmobile.core.data

enum class ProfileBatchAction { EnableAutoStart, DisableAutoStart, Delete }

data class ProfileBatchResult(
    val succeeded: Set<String>,
    val skipped: Set<String>,
    val failures: Map<String, String>,
    val pending: Set<String>,
    val syncError: String?,
)
