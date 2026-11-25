package melox.plugin

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** One invocation owns its replay window, broker and account-scoped storage. */
internal class PluginHostCalls(
    private val invocation: String,
    private val broker: PluginHttpBroker,
    private val storage: PluginStorage,
    private val current: () -> Boolean,
) : Closeable {
    private val closed = AtomicBoolean()
    private val seen = mutableSetOf<Int>()
    private var count = 0

    fun execute(token: String?, id: Int, raw: String): String {
        synchronized(seen) {
            check(!closed.get() && current()) { "CANCELLED" }
            require(PluginIpcPolicy.validToken(invocation, token)) { "INVALID_PLUGIN_IPC" }
            if (!PluginIpcPolicy.validHostCall(id, ++count, raw.toByteArray(Charsets.UTF_8).size) || !seen.add(id)) {
                close()
                error("PLUGIN_HOST_CALL_LIMIT")
            }
        }
        return try {
            PluginJson.check(raw, 48 * 1024)
            val input = JSONObject(raw)
            require(input.opt("operation") is String)
            val operation = input.getString("operation")
            val value = when (operation) {
                "http" -> {
                    require(input.opt("request") is JSONObject)
                    JSONObject(broker.request(input.getJSONObject("request").toString()))
                }
                "storage.get", "storage.set", "storage.remove" -> synchronized(seen) {
                    check(!closed.get() && current()) { "CANCELLED" }
                    require(input.opt("key") is String && (operation != "storage.set" || input.opt("value") is String))
                    storage.execute(operation, input.getString("key"), if (operation == "storage.set") input.getString("value") else null)
                }
                else -> error("UNSUPPORTED_OPERATION")
            }
            check(!closed.get() && current()) { "CANCELLED" }
            JSONObject().put("ok", true).put("value", value).toString()
        } catch (failure: Exception) {
            if (closed.get() || !current() || failure is InterruptedException || failure is java.util.concurrent.CancellationException) throw failure
            val code = PluginErrors.code(failure).takeIf { it in PluginIpcPolicy.remoteErrors } ?: "NETWORK_REQUEST_FAILED"
            JSONObject().put("ok", false).put("error", code).toString()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) broker.close()
    }
}
