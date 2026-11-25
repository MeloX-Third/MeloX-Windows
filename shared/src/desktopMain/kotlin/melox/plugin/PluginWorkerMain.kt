package melox.plugin

import com.caoccao.javet.annotations.V8Function
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.V8Runtime
import com.caoccao.javet.interop.options.V8RuntimeOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** One invocation per process. Production must launch with OS restrictions and inherited pipes. */
internal object PluginWorkerMain {
    @JvmStatic fun main(args: Array<String>) {
        val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "plugin-worker-watchdog").apply { isDaemon = true } }
        watchdog.schedule({ Runtime.getRuntime().halt(74) }, 8, TimeUnit.SECONDS)
        var token: String? = null
        try {
            val request = JSONObject(PluginIpcFrames.read(System.`in`))
            token = request.getString("token")
            require(token.length in 1..64 && token.all { it.code in 33..126 })
            val method = request.getString("method")
            require(method.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")))
            val script = request.getString("script")
            require(script.toByteArray(Charsets.UTF_8).size <= PluginIpcFrames.MAX_BYTES)
            val arguments = request.getJSONArray("args")
            val account = request.optJSONObject("account")?.also { PluginJson.check(it.toString(), 4096) }
            val bridge = PluginWorkerBridge(token)
            V8RuntimeOptions.V8_FLAGS.setMaxHeapSize(16).setCustomFlags("--stack-size=256")
            V8Host.getV8Instance().createV8Runtime<V8Runtime>().use { runtime ->
                runtime.createV8ValueObject().use { native ->
                    native.bind(bridge)
                    runtime.globalObject.set("__native", native)
                    runtime.getGuard(7500).use {
                        runtime.getExecutor(bootstrap).executeVoid()
                        runtime.allowEval(false)
                        runtime.getExecutor(script).executeVoid()
                        runtime.getExecutor("__invoke(${JSONObject.quote(method)},${arguments},${account ?: "null"})").executeVoid()
                        while (!bridge.finished) {
                            runtime.performMicrotaskCheckpoint()
                            if (!bridge.finished) Thread.yield()
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            token?.let { value -> runCatching {
                PluginIpcFrames.write(System.out, JSONObject().put("token", value).put("type", "result")
                    .put("error", true).put("payload", """{"code":"PLUGIN_EXECUTION_FAILED"}""").toString())
            } }
        } finally { watchdog.shutdownNow() }
    }

    private val bootstrap = """
        (() => {
          const native = globalThis.__native;
          delete globalThis.__native;
          let account = null;
          const callHost = value => Promise.resolve().then(() => {
            const response = JSON.parse(native.request(JSON.stringify(value)));
            if (!response.ok) throw {code:response.error};
            return response.value;
          });
          let logs = 0;
          const log = () => { if (++logs > 32) throw {code:'PLUGIN_ERROR'}; };
          const host = Object.freeze({
            apiVersion:1, platform:'windows', get account() { return account; },
            http:Object.freeze({request:request => callHost({operation:'http',request})}),
            storage:Object.freeze({
              get:key => callHost({operation:'storage.get',key}),
              set:(key,value) => callHost({operation:'storage.set',key,value}),
              remove:key => callHost({operation:'storage.remove',key})
            }),
            log:Object.freeze({info:log,warn:log,error:log})
          });
          globalThis.__invoke = (method,args,selectedAccount) => {
            account = selectedAccount ? Object.freeze(selectedAccount) : null;
            Promise.resolve().then(() => {
              const plugin = globalThis.meloxPlugin;
              if (!plugin || typeof plugin !== 'object') throw {code:'INVALID_PLUGIN'};
              if (method === '__validate') return Object.keys(plugin).filter(key => typeof plugin[key] === 'function');
              if (!Object.prototype.hasOwnProperty.call(plugin,method) || typeof plugin[method] !== 'function')
                throw {code:'UNSUPPORTED_OPERATION'};
              return plugin[method](...args,host);
            }).then(value => native.finish(false,JSON.stringify(value ?? null)),
              error => native.finish(true,JSON.stringify({code:typeof error?.code === 'string' ? error.code : 'PLUGIN_ERROR'})));
          };
        })();
    """.trimIndent()
}

/** Only JSON host requests and terminal JSON values cross the native bridge. */
class PluginWorkerBridge internal constructor(private val token: String) {
    private var calls = 0
    var finished = false
        private set

    @V8Function fun request(payload: String): String {
        check(!finished)
        require(PluginIpcPolicy.validHostCall(++calls, calls, payload.toByteArray(Charsets.UTF_8).size))
        PluginJson.check(payload, 48 * 1024)
        PluginIpcFrames.write(System.out, JSONObject().put("token", token).put("type", "host")
            .put("id", calls).put("payload", payload).toString())
        val response = JSONObject(PluginIpcFrames.read(System.`in`))
        require(PluginIpcPolicy.validToken(token, response.opt("token") as? String) &&
            response.opt("type") == "response" && response.opt("id") == calls && response.opt("payload") is String)
        return response.getString("payload").also { PluginJson.check(it) }
    }

    @V8Function fun finish(error: Boolean, payload: String) {
        check(!finished)
        PluginJson.check(payload)
        PluginIpcFrames.write(System.out, JSONObject().put("token", token).put("type", "result")
            .put("error", error).put("payload", payload).toString())
        finished = true
    }
}
