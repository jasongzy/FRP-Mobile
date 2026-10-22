package com.jasongzy.frpmobile.core.frp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal fun readSupervisorOutput(input: InputStream): String {
    val output = ByteArrayOutputStream()
    try {
        input.use { stream ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, minOf(count, 2048 - output.size()))
            }
        }
    } catch (_: IOException) {
        // Android may close the pipe while its process reaper and reader run concurrently.
    }
    return output.toString(Charsets.UTF_8.name()).trim()
}
