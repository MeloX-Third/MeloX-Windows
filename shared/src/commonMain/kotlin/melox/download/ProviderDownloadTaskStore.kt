package melox.download

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import melox.music.model.*
import melox.plugin.PluginJson
import org.json.JSONArray
import org.json.JSONObject

/** Retry intent only: no resolved media URLs, headers or automatic network work. */
internal class ProviderDownloadTaskStore(directory: File) {
    data class Task(val track: MusicTrack, val quality: AudioQualityTier, val revision: String,
        val errorCode: String? = null, val accountScope: String? = null)
    private val file = File(directory, "tasks-v1.json")

    fun load(): List<Task> = synchronized(lock) {
        val rows = read()
        buildList {
            for (i in 0 until rows.length()) {
                runCatching {
                    val row = rows.getJSONObject(i)
                    val track = requireNotNull(ProviderDownloadIndexCodec.decodeTrack(row))
                    val quality = AudioQualityTier.valueOf(row.getString("quality"))
                    val revision = row.getString("revision").also { require(it.matches(REVISION)) }
                    val state = row.getString("state").also { require(it in setOf("pending", "failed")) }
                    val code = if (state == "pending") "DOWNLOAD_INTERRUPTED" else
                        row.getString("errorCode").takeIf(melox.plugin.PluginErrors::known) ?: "DOWNLOAD_FAILED"
                    val account = row.optString("accountScope").takeUnless { row.isNull("accountScope") }
                        ?.also { require(it.matches(ACCOUNT)) }
                    Task(track, quality, revision, code, account)
                }.getOrNull()?.let(::add)
            }
        }
    }

    fun save(task: Task) = synchronized(lock) {
        require(task.revision.matches(REVISION))
        require(task.errorCode == null || melox.plugin.PluginErrors.known(task.errorCode))
        require(task.accountScope == null || task.accountScope.matches(ACCOUNT))
        val row = JSONObject().put("source", task.track.id.sourceId.value).put("id", task.track.id.value)
            .put("track", MeloXTrackJson.encode(task.track)).put("quality", task.quality.name).put("revision", task.revision)
            .put("state", if (task.errorCode == null) "pending" else "failed")
            .put("errorCode", task.errorCode ?: JSONObject.NULL).put("accountScope", task.accountScope ?: JSONObject.NULL)
        write(ProviderDownloadIndexCodec.merge(read(), JSONArray().put(row), emptySet()))
    }

    fun remove(id: MusicResourceId, revision: String) = synchronized(lock) {
        val rows = read()
        val remaining = JSONArray()
        val identity = "${id.sourceId.value}:${id.value}"
        for (i in 0 until rows.length()) {
            val row = rows.opt(i)
            if (row is JSONObject && ProviderDownloadIndexCodec.identity(row) == identity && row.optString("revision") == revision) continue
            remaining.put(row)
        }
        write(remaining)
    }

    private fun read(): JSONArray {
        if (!file.exists()) return JSONArray()
        val raw = file.inputStream().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(bytes.size() + n <= MAX_BYTES) { "download task index too large" }
                bytes.write(buffer, 0, n)
            }
            bytes.toString("UTF-8")
        }
        PluginJson.check(raw, MAX_BYTES)
        return JSONArray(raw).also { require(it.length() <= MAX_TASKS) }
    }

    private fun write(rows: JSONArray) {
        require(rows.length() <= MAX_TASKS) { "download task quota exceeded" }
        val raw = rows.toString()
        PluginJson.check(raw, MAX_BYTES)
        file.parentFile.mkdirs()
        val temporary = Files.createTempFile(file.parentFile.toPath(), "tasks-", ".tmp")
        try {
            Files.writeString(temporary, raw)
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private companion object {
        val lock = Any()
        val REVISION = Regex("[a-f0-9-]{36}")
        val ACCOUNT = Regex("[a-f0-9]{64}")
        const val MAX_TASKS = 100
        const val MAX_BYTES = 1024 * 1024
    }
}
