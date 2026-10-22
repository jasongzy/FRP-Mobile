package com.jasongzy.frpmobile.core.frp

import java.io.InputStream
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorOutputTest {
    @Test fun processExitKeepsDiagnosticsAndClosesPipe() {
        var closed = false
        val input = object : InputStream() {
            private val message = "startup failed".byteInputStream()
            override fun read(): Int = message.read().also {
                if (it < 0) throw InterruptedIOException("read interrupted by close() on another thread")
            }
            override fun close() { closed = true }
        }
        assertEquals("startup failed", readSupervisorOutput(input))
        assertTrue(closed)
        val large = ByteArray(8192) { 'x'.code.toByte() }.inputStream()
        assertEquals(2048, readSupervisorOutput(large).length)
        assertEquals(0, large.available())
    }
}
