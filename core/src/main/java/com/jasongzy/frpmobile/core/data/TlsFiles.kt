package com.jasongzy.frpmobile.core.data

data class TlsFileInfo(
    val role: TlsFileRole,
    val name: String,
    val path: String,
)

enum class TlsFileRole(val fileName: String) {
    TrustedCa("ca.pem"),
    Certificate("cert.pem"),
    PrivateKey("key.pem");

    val fileInfo: TlsFileInfo
        get() = TlsFileInfo(this, fileName, "frpmobile://$fileName")
}
