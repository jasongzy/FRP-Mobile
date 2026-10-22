package com.jasongzy.frpmobile.core.frp

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Base64
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface FrpRuntimeGateway {
    var rootMode: Boolean
    val localRuntimeRunning: Boolean
    val bootCount: Int
    fun validateToml(toml: String): FrpResult
    fun tlsFilePaths(toml: String): List<String>
    fun setLogSink(sink: FrpLogSink)
    suspend fun start(profile: FrpProfile): FrpResult
    suspend fun reload(profile: FrpProfile): FrpResult
    suspend fun stop(id: String): FrpResult
    suspend fun delete(id: String): FrpResult
    suspend fun stopAll(): FrpResult
    suspend fun configure(profiles: List<FrpProfile>): FrpResult
    suspend fun module(action: String): ModuleStatus
    suspend fun listInstances(): FrpRuntimeQueryResult
    suspend fun awaitRuntimeChange()
    suspend fun pruneLogCache(before: Long): FrpResult
}

data class ModuleStatus(
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val running: Boolean = false,
    val version: String = "",
    val versionCode: Int = 0,
    val frpVersion: String = "",
    val bundledVersion: String = "",
    val bundledVersionCode: Int = 0,
    val bundledFrpVersion: String = "",
    val needsReinstall: Boolean = false,
    val error: String? = null,
)

internal suspend fun executeModuleAction(action: String, execute: suspend (String) -> String): ModuleStatus {
    require(action in setOf("status", "install", "start", "stop", "uninstall"))
    return try {
        // Mutating commands report success through their exit code; only status returns JSON.
        if (action != "status") execute(action)
        val output = execute("status")
        check(output.isNotBlank()) { "EMPTY_MODULE_RESPONSE" }
        val response = JSONObject(output)
        val error = response.optString("error").ifBlank { null }
        if (error != null) ModuleStatus(error = error)
        else ModuleStatus(
            installed = response.getBoolean("installed"),
            enabled = response.getBoolean("enabled"),
            running = response.getBoolean("running"),
            version = response.getString("version"),
            versionCode = response.getInt("versionCode"),
            frpVersion = response.getString("frpVersion"),
            bundledVersion = response.getString("bundledVersion"),
            bundledVersionCode = response.getInt("bundledVersionCode"),
            bundledFrpVersion = response.getString("bundledFrpVersion"),
            needsReinstall = response.getBoolean("needsReinstall"),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ModuleStatus(error = e.message ?: "ROOT_UNAVAILABLE")
    }
}

class FrpRuntimeManager(context: Context) : FrpRuntimeGateway {
    override val localRuntimeRunning: Boolean get() = !rootMode && supervisor?.running() == true
    private val resolver = context.applicationContext.contentResolver
    override val bootCount: Int get() = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
    private val root = File(context.noBackupFilesDir, "runtime").canonicalFile
    private val bin = File(context.applicationInfo.nativeLibraryDir)
    private val executable = File(bin, "libfrpmobile.so").absolutePath
    private val validator = TomlValidator()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var supervisor: Process? = null
    private var sink: FrpLogSink? = null
    private var cursor = 0L
    @Volatile private var revision = 0L
    @Volatile override var rootMode = false
        set(value) {
            if (field != value) cursor = 0
            field = value
        }

    override fun validateToml(toml: String) = validator.validate(toml)
    override fun tlsFilePaths(toml: String) = validator.tlsFilePaths(toml)
    override fun setLogSink(sink: FrpLogSink) { this.sink = sink }
    override suspend fun start(profile: FrpProfile) = command("start", profile)
    override suspend fun reload(profile: FrpProfile) = command("reload", profile)
    override suspend fun stop(id: String) = command("stop", id = id)
    override suspend fun delete(id: String) = command("delete", id = id)
    override suspend fun stopAll() = command("stopAll")
    override suspend fun configure(profiles: List<FrpProfile>): FrpResult = result {
        request(JSONObject().put("action", "configure").put("profiles", JSONArray(profiles.map(::encodeProfile))))
    }
    override suspend fun pruneLogCache(before: Long): FrpResult = result {
        request(JSONObject().put("action", "pruneLogs").put("before", before))
    }

    private suspend fun command(action: String, profile: FrpProfile? = null, id: String? = null) = result {
        val message = JSONObject().put("action", action)
        profile?.let { message.put("profile", encodeProfile(it)) }
        id?.let { message.put("id", it) }
        request(message)
    }

    private suspend fun result(block: suspend () -> JSONObject): FrpResult = try {
        FrpResult.fromRaw(block().optString("error"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FrpResult("RUNTIME_ERROR", "RUNTIME_ERROR: ${e.message}")
    }

    override suspend fun listInstances(): FrpRuntimeQueryResult = try {
        val response = request(JSONObject().put("action", "status"))
        val error = response.optString("error")
        if (error.isNotBlank()) FrpRuntimeQueryResult.Failure(error)
        else {
            val states = response.getJSONArray("states")
            FrpRuntimeQueryResult.Success(List(states.length()) { index ->
                val state = states.getJSONObject(index)
                FrpRuntimeState(
                    state.getString("id"),
                    FrpType.entries.first { it.name.equals(state.getString("type"), true) },
                    FrpInstanceStatus.entries.first { it.name.equals(state.getString("state"), true) },
                    state.optString("error").ifBlank { null },
                    state.getBoolean("desired"),
                )
            })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FrpRuntimeQueryResult.Failure("RUNTIME_ERROR: ${e.message}")
    }

    override suspend fun module(action: String): ModuleStatus = withContext(Dispatchers.IO) {
        executeModuleAction(action) { command ->
            process(listOf("su", "-c", "exec ${quote(executable)} module $command"))
        }
    }

    private suspend fun request(message: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            message.put("after", cursor)
            val output = if (rootMode) {
                process(
                    listOf("su", "-c", "exec ${quote(executable)} request /data/adb/frp-mobile"),
                    message.toString(),
                )
            } else {
                val startsRuntime = message.getString("action") in setOf("start", "reload")
                var output: String? = null
                repeat(2) {
                    if (output != null) return@repeat
                    if (supervisor?.running() != true) {
                        if (startsRuntime) ensureLocal()
                        else {
                            if (message.getString("action") != "status") File(root, "exit.json").delete()
                            output = if (message.getString("action") == "status") {
                                cachedLocalStatus()
                            } else null
                            if (output == null) output = """{"states":[],"logs":[],"cursor":0}"""
                            return@repeat
                        }
                    }
                    try {
                        val response = localRequest(message)
                        if (JSONObject(response).optString("error") != "RUNTIME_IDLE") output = response
                    } catch (e: java.io.IOException) {
                        if (supervisor?.running() == true && File(root, "control.sock").exists()) throw e
                    }
                    if (output == null) {
                        withTimeout(5_000) {
                            while (supervisor?.running() == true) delay(20)
                        }
                    }
                }
                checkNotNull(output) { "Runtime unavailable" }
            }
            JSONObject(output).also { response ->
                response.optJSONArray("logs")?.let { logs ->
                    repeat(logs.length()) { index ->
                        val log = logs.getJSONObject(index)
                        if (log.getLong("seq") <= cursor && cursor <= response.optLong("cursor")) return@repeat
                        sink?.onLog(FrpLog(
                            log.getString("id"), log.getString("type"), log.getString("level"),
                            log.getString("message"), log.getLong("time"),
                        ))
                    }
                }
                cursor = response.optLong("cursor", cursor)
                revision = response.optLong("revision", revision)
            }
        }
    }

    private suspend fun ensureLocal() {
        if (supervisor?.running() == true && File(root, "control.sock").exists()) return
        if (!root.isDirectory) check(root.mkdirs()) { "Unable to create runtime directory" }
        File(root, "exit.json").delete()
        cursor = 0
        revision = 0
        val child = ProcessBuilder(
            executable, "serve", root.path, bin.path, android.os.Process.myPid().toString(),
        ).redirectErrorStream(true).start()
        supervisor = child
        val output = scope.async { readSupervisorOutput(child.inputStream) }
        repeat(50) {
            if (!child.running()) error("Supervisor exited (${child.exitValue()}): ${output.await()}")
            try {
                localRequest(JSONObject().put("action", "status"))
                return
            } catch (_: java.io.IOException) {
                delay(100)
            }
        }
        child.destroy()
        error("Supervisor startup timed out")
    }

    private fun Process.running(): Boolean = try { exitValue(); false } catch (_: IllegalThreadStateException) { true }

    private fun cachedLocalStatus(): String? {
        val file = File(root, "exit.json")
        if (!file.isFile) return null
        return try {
            file.readText().also { JSONObject(it).getJSONArray("states") }
        } catch (e: Exception) {
            file.delete()
            sink?.onLog(FrpLog("", "frp", "warn", "RUNTIME_CACHE_UNAVAILABLE", System.currentTimeMillis()))
            null
        }
    }

    override suspend fun awaitRuntimeChange() {
        if (rootMode || supervisor?.running() != true) return
        try {
            localRequest(JSONObject().put("action", "watch").put("revision", revision))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Query status after the supervisor closes or times out the watch.
        }
    }

    internal suspend fun localRequest(message: JSONObject): String =
        suspendCancellableCoroutine { continuation ->
            val socket = LocalSocket()
            continuation.invokeOnCancellation {
                // Android's blocking reads need shutdown before close releases the connection.
                runCatching { socket.shutdownInput() }
                runCatching { socket.shutdownOutput() }
                runCatching { socket.close() }
            }
            scope.launch {
                try {
                    if (continuation.isActive) continuation.resume(exchange(socket, message))
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally {
                    runCatching { socket.close() }
                }
            }
        }

    private fun exchange(socket: LocalSocket, message: JSONObject): String {
        socket.connect(LocalSocketAddress(File(root, "control.sock").path, LocalSocketAddress.Namespace.FILESYSTEM))
        socket.soTimeout = 30_000
        socket.outputStream.write((message.toString() + "\n").toByteArray())
        socket.outputStream.flush()
        return socket.inputStream.readBounded()
    }

    private suspend fun process(args: List<String>, input: String = ""): String = try { withTimeout(30_000) {
        suspendCancellableCoroutine { continuation ->
            val child = ProcessBuilder(args).redirectErrorStream(true).start()
            continuation.invokeOnCancellation { child.destroy() }
            scope.launch {
                try {
                    child.outputStream.use { it.write(input.toByteArray()) }
                    val text = child.inputStream.use { it.readBounded() }
                    val exitCode = child.waitFor()
                    check(exitCode == 0) { text.take(2048).ifBlank { "Process failed ($exitCode)" } }
                    continuation.resume(text)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                } finally {
                    child.destroy()
                }
            }
        }
    } } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw java.io.IOException("PROCESS_TIMEOUT", e)
    }

    private fun InputStream.readBounded(): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= 16 * 1024 * 1024) { "Runtime response too large" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun encodeProfile(profile: FrpProfile) = JSONObject()
        .put("id", profile.id).put("type", profile.type.name.lowercase()).put("toml", profile.toml)
        .put("autoStart", profile.autoStart).put("retry", profile.retry)
        .put("networkReconnect", profile.networkReconnect)
        .put("files", JSONObject(profile.files.mapValues { Base64.encodeToString(it.value, Base64.NO_WRAP) }))
}
