package com.jasongzy.frpmobile.core.runtime

import com.jasongzy.frpmobile.core.frp.FrpInstanceStatus
import com.jasongzy.frpmobile.core.frp.FrpRuntimeState
import com.jasongzy.frpmobile.core.frp.FrpType

internal object FrpRuntimePolicy {
    fun notificationTypes(states: List<FrpRuntimeState>): List<FrpType> =
        states.filter { it.state == FrpInstanceStatus.Running || it.state == FrpInstanceStatus.Stopping }.map { it.type }

    fun moduleNotificationTypes(rootMode: Boolean, states: List<FrpRuntimeState>): List<FrpType>? =
        if (rootMode && states.any { it.canStop }) {
            notificationTypes(states)
        } else null

    fun shouldHoldScreenOffKeepAlive(enabled: Boolean, screenOff: Boolean, hasActiveInstances: Boolean): Boolean =
        enabled && screenOff && hasActiveInstances

    fun shouldHoldWifiLock(keepAlive: Boolean, defaultNetworkIsWifi: Boolean): Boolean =
        keepAlive && defaultNetworkIsWifi

    fun retryDelaySeconds(attempt: Int): Long =
        when (attempt.coerceAtLeast(1)) {
            1 -> 10
            2 -> 30
            3 -> 60
            4 -> 120
            5 -> 300
            else -> 600
        }
}
