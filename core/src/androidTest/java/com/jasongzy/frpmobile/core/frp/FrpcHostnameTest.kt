package com.jasongzy.frpmobile.core.frp

import android.os.Process
import android.system.Os
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpcHostnameTest {
    @Test fun reportsHostnameFromOrdinaryAppProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.jasongzy.frpmobile.core.test")
        assertTrue("Expected an ordinary app UID", Process.myUid() >= 10_000)
        val root = File(context.noBackupFilesDir, "hostname-test")
        check(root.mkdir())
        var client: java.lang.Process? = null
        try {
            val propertyReader = ProcessBuilder("/system/bin/getprop", "net.hostname").start()
            val property = try {
                check(propertyReader.waitFor(5, TimeUnit.SECONDS))
                check(propertyReader.exitValue() == 0)
                propertyReader.inputStream.bufferedReader().use { it.readText().trim() }
            } finally {
                propertyReader.destroyForcibly()
            }
            val kernel = Os.uname().nodename
            val expected = if ((kernel.isEmpty() || kernel == "localhost") && property.isNotEmpty()) property else kernel
            InstrumentationRegistry.getArguments().getString("expectedHostname")?.let {
                assertEquals("Property is unavailable to the app UID", it, expected)
            }
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 15_000
                val config = File(root, "frpc.toml").apply {
                    writeText("""
                        serverAddr = "127.0.0.1"
                        serverPort = ${server.localPort}
                        loginFailExit = true
                        transport.tcpMux = false
                        transport.tls.enable = false
                        transport.wireProtocol = "v1"
                    """.trimIndent())
                }
                client = ProcessBuilder(
                    File(context.applicationInfo.nativeLibraryDir, "libfrpc.so").path, "-c", config.path,
                ).redirectErrorStream(true).redirectOutput(File(root, "frpc.log")).start()
                server.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val input = DataInputStream(peer.getInputStream())
                    assertEquals('o'.code, input.readUnsignedByte())
                    val length = input.readLong()
                    check(length in 1..65_536)
                    val bytes = ByteArray(length.toInt()).also(input::readFully)
                    val login = JSONObject(bytes.toString(Charsets.UTF_8))
                    assertEquals(expected, login.getString("hostname"))
                    Log.i("FrpcHostnameTest", "uid=${Process.myUid()} kernel=$kernel property=$property reported=${login.getString("hostname")}")
                }
            }
        } finally {
            client?.let {
                it.destroy()
                if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly()
                assertTrue("frpc process did not exit", it.waitFor(5, TimeUnit.SECONDS))
            }
            assertTrue("Test files were not removed", root.deleteRecursively())
        }
    }
}
