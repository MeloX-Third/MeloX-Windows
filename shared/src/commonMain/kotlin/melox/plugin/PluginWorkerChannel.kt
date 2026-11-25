package melox.plugin

import java.io.InputStream
import java.io.OutputStream
import org.json.JSONObject

/** Host half of one worker invocation. The launcher owns timeout, process isolation and kill. */
internal class PluginWorkerChannel(
    private val token: String,
    private val hostCalls: PluginHostCalls,
    private val current: () -> Boolean,
) {
    fun receive(input: InputStream, output: OutputStream): String = hostCalls.use {
        var messages = 0
        while (true) {
            check(current()) { "CANCELLED" }
            require(++messages <= PluginIpcPolicy.MAX_HOST_CALLS + 1) { "PLUGIN_HOST_CALL_LIMIT" }
            val frame = JSONObject(PluginIpcFrames.read(input))
            require(PluginIpcPolicy.validToken(token, frame.opt("token") as? String)) { "INVALID_PLUGIN_IPC" }
            check(current()) { "CANCELLED" }
            when (frame.opt("type")) {
                "host" -> {
                    val id = frame.opt("id")
                    require(id is Int && frame.opt("payload") is String) { "INVALID_PLUGIN_IPC" }
                    val result = hostCalls.execute(token, id, frame.getString("payload"))
                    check(current()) { "CANCELLED" }
                    PluginIpcFrames.write(output, JSONObject().put("token", token).put("type", "response")
                        .put("id", id).put("payload", result).toString())
                }
                "result" -> {
                    require(frame.opt("payload") is String && frame.opt("error") is Boolean) { "INVALID_PLUGIN_IPC" }
                    val result = frame.getString("payload")
                    PluginJson.check(result)
                    check(current()) { "CANCELLED" }
                    if (frame.getBoolean("error")) error(PluginIpcPolicy.errorCode(result))
                    return@use result
                }
                else -> error("INVALID_PLUGIN_IPC")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("INVALID_PLUGIN_IPC")
    }
}
