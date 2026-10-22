package com.jasongzy.frpmobile.core.frp

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeSocketTest {
    @Test fun exchangesRequestsAndCancelsBlockedReads() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.jasongzy.frpmobile.core.test")
        val root = File(context.noBackupFilesDir, "runtime").canonicalFile
        check(root.mkdir())
        val path = File(root, "control.sock")
        val runtime = FrpRuntimeManager(context)
        try {
            LocalSocket().use { bound ->
                bound.bind(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
                bound.soTimeout = 5000
                LocalServerSocket(bound.fileDescriptor).use { server ->
                    val reply = async {
                        runtime.localRequest(JSONObject().put("action", "status"))
                    }
                    server.accept().use { peer ->
                        peer.soTimeout = 5000
                        val request = JSONObject(peer.inputStream.bufferedReader().readLine())
                        assertEquals("status", request.getString("action"))
                        peer.outputStream.write("""{"states":[],"logs":[],"cursor":0}""".toByteArray())
                    }
                    assertEquals(0, JSONObject(withTimeout(5000) { reply.await() }).getJSONArray("states").length())
                    val blocked = async { runtime.localRequest(JSONObject().put("action", "status")) }
                    server.accept().use { peer ->
                        peer.soTimeout = 2000
                        val input = peer.inputStream.bufferedReader()
                        assertEquals("status", JSONObject(input.readLine()).getString("action"))
                        withTimeout(2000) { blocked.cancelAndJoin() }
                        assertEquals("Cancellation must close the client socket", -1, input.read())
                    }
                }
            }
        } finally {
            val socketDeleted = !path.exists() || path.delete()
            val directoryDeleted = root.delete()
            assertTrue("Socket was not removed", socketDeleted)
            assertTrue("Test directory was not removed", directoryDeleted)
        }
    }
}
