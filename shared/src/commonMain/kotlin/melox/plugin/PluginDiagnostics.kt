package melox.plugin

import melox.music.model.SourceId
import melox.platform.meloXDataDir
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject

/** Host event codes only: no script text, arguments, URLs, credentials or account details. */
internal class PluginDiagnostics(source: SourceId, base: File = File(meloXDataDir())) {
    init { require(source.builtIn == null) }
    private val file = File(base, "melox-plugin-diagnostics-v1/${PluginPackageTrust.sha256(source.value.toByteArray(Charsets.UTF_8))}.json")
    private val methods = PluginCapability.entries.flatMap { it.methods }.toSet() + "__validate"

    fun record(method: String, code: String, elapsedMs: Long) = synchronized(lock) {
        require(PluginErrors.known(code))
        val previous = read()
        val events = JSONArray()
        for (i in (previous.length() - 99).coerceAtLeast(0) until previous.length()) events.put(previous.get(i))
        events.put(JSONObject().put("time", System.currentTimeMillis()).put("method", method.takeIf { it in methods } ?: "unknown")
            .put("code", code).put("elapsedMs", elapsedMs.coerceIn(0, 3_600_000)))
        val bytes = events.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        PluginPrivateFiles.write(file, bytes)
    }

    fun lines(): List<String> = synchronized(lock) {
        val values = read()
        (0 until values.length()).map { i ->
            val event = values.getJSONObject(i)
            val method = event.getString("method").takeIf { it in methods } ?: "unknown"
            val code = event.getString("code").takeIf(PluginErrors::known) ?: "PLUGIN_EXECUTION_FAILED"
            val time = event.getLong("time").coerceAtLeast(0)
            val elapsed = event.getLong("elapsedMs").coerceIn(0, 3_600_000)
            "$time · $method · $code · ${PluginErrors.message(code)} · ${elapsed}ms"
        }
    }

    fun clear() = synchronized(lock) { Files.deleteIfExists(file.toPath()); Unit }

    private fun read(): JSONArray = try {
        val raw = PluginPrivateFiles.read(file, MAX_BYTES).toString(Charsets.UTF_8)
        PluginJson.check(raw, MAX_BYTES)
        JSONArray(raw).also { require(it.length() <= 100) }
    } catch (_: FileNotFoundException) { JSONArray() }

    private companion object {
        const val MAX_BYTES = 32 * 1024
        val lock = Any()
    }
}
