package com.jasongzy.frpmobile.core.frp

import java.util.UUID

fun newProfileDraft(type: FrpType, toml: String = defaultToml(type)): FrpProfile {
    val id = UUID.randomUUID().toString()
    return FrpProfile(
        id = id,
        name = "${if (type == FrpType.Client) "frpc" else "frps"}-$id".take(13),
        type = type,
        toml = toml,
        autoStart = false,
        updatedAt = System.currentTimeMillis(),
    )
}

private fun defaultToml(type: FrpType): String =
    if (type == FrpType.Client) {
        """
        serverAddr = "127.0.0.1"
        serverPort = 7000
        loginFailExit = false

        [[proxies]]
        name = "nas-web"
        type = "tcp"
        localIP = "127.0.0.1"
        localPort = 8080
        remotePort = 8080
        """.trimIndent()
    } else {
        """
        bindPort = 7000
        """.trimIndent()
    }
