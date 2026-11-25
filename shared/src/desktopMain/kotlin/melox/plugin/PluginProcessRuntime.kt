package melox.plugin

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject

/** A launcher must enforce OS restrictions before exposing a worker's pipes. No unrestricted fallback. */
internal fun interface PluginWorkerLauncher {
    fun start(): PluginWorkerProcess
}

internal interface PluginWorkerProcess : Closeable {
    val input: InputStream
    val output: OutputStream
}

internal class PluginInvocationContext(
    val account: JSONObject?,
    val current: () -> Boolean,
    val hostCalls: (String, () -> Boolean) -> PluginHostCalls,
)

/** Lifecycle owner of fresh invocation processes and their private host-call resources. */
internal class PluginProcessRuntime(
    private val plugin: PluginArchiveReader.Package,
    private val launcher: PluginWorkerLauncher,
    private val hostCalls: (String, () -> Boolean) -> PluginHostCalls,
    private val account: () -> JSONObject? = { null },
    private val current: () -> Boolean = { true },
    private val context: (() -> PluginInvocationContext)? = null,
) : Closeable {
    private val closed = AtomicBoolean()
    private val active = AtomicReference<Closeable?>()
    private val busy = AtomicBoolean()

    suspend fun invoke(method: String, args: JSONArray): String = runInterruptible(Dispatchers.IO) {
        check(!closed.get() && current()) { "CANCELLED" }
        require(PluginIpcPolicy.methodAllowed(plugin.manifest, method)) { "UNSUPPORTED_OPERATION" }
        require(busy.compareAndSet(false, true)) { "BUSY" }
        try {
            val token = UUID.randomUUID().toString()
            val selected = context?.invoke()
            val valid = { !closed.get() && current() && (selected?.current?.invoke() ?: true) }
            val request = JSONObject().put("token", token).put("method", method).put("args", args)
                .put("account", (if (selected != null) selected.account else account()) ?: JSONObject.NULL).put("script", plugin.script).toString()
            // Validate before process creation; oversized scripts must not strand a worker.
            PluginJson.check(request)
            val process = launcher.start()
            val calls = try { (selected?.hostCalls ?: hostCalls)(token, valid) } catch (failure: Throwable) { process.close(); throw failure }
            val owner = Closeable { try { process.close() } finally { calls.close() } }
            active.set(owner)
            val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "plugin-invocation-ipc").apply { isDaemon = true } }
            try {
                check(valid()) { "CANCELLED" }
                val future = executor.submit<String> {
                    PluginIpcFrames.write(process.output, request)
                    PluginWorkerChannel(token, calls, valid).receive(process.input, process.output)
                }
                try { future.get(8, TimeUnit.SECONDS).also { check(valid()) { "CANCELLED" } } }
                catch (_: TimeoutException) { error("PLUGIN_TIMEOUT") }
                catch (failure: java.util.concurrent.ExecutionException) { throw failure.cause ?: failure }
            } finally {
                active.compareAndSet(owner, null)
                try { owner.close() } finally { executor.shutdownNow() }
            }
        } finally { busy.set(false) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) active.getAndSet(null)?.close()
    }
}
