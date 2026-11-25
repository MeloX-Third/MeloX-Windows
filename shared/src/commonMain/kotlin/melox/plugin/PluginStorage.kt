package melox.plugin

import melox.music.model.SourceId
import melox.platform.meloXDataDir
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.json.JSONObject

/** Non-secret storage, isolated by source and local account slot. Credentials belong in the vault. */
class PluginStorage(
    source: SourceId,
    accountId: String? = null,
    base: File = File(meloXDataDir()),
) {
    init { require(source.builtIn == null) }
    private val root = File(base, "melox-plugin-storage-v2/${digest(source.value)}")
    private val file = File(root, "${digest(accountId ?: "anonymous")}.json")

    fun execute(operation: String, key: String, value: String? = null): Any = synchronized(lock) {
        require(key.matches(keyPattern)) { "invalid storage key" }
        val previous = try {
            val raw = PluginPrivateFiles.read(file, MAX_BYTES).toString(Charsets.UTF_8)
            PluginJson.check(raw, MAX_BYTES)
            JSONObject(raw)
        } catch (_: FileNotFoundException) { JSONObject() }
        require(previous.length() <= 64) { "storage quota exceeded" }
        previous.keys().forEach { stored ->
            require(stored.matches(keyPattern) && previous.opt(stored) is String) { "invalid stored value" }
            require(previous.getString(stored).toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "storage value too large" }
        }
        when (operation) {
            "storage.get" -> return@synchronized if (previous.has(key)) previous.getString(key) else JSONObject.NULL
            "storage.set" -> {
                require(value != null && value.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "storage value too large" }
                previous.put(key, value)
            }
            "storage.remove" -> previous.remove(key)
            else -> error("unsupported storage operation")
        }
        val bytes = previous.toString().toByteArray(Charsets.UTF_8)
        require(previous.length() <= 64 && bytes.size <= MAX_BYTES) { "storage quota exceeded" }
        PluginPrivateFiles.write(file, bytes)
        JSONObject.NULL
    }

    companion object {
        private const val MAX_BYTES = 64 * 1024
        private val lock = Any()
        private val keyPattern = Regex("[A-Za-z0-9_.-]{1,128}")
        private fun digest(value: String) = PluginPackageTrust.sha256(value.toByteArray(Charsets.UTF_8))
        fun clearAccount(source: SourceId, accountId: String, base: File = File(meloXDataDir())) = synchronized(lock) {
            Files.deleteIfExists(PluginStorage(source, accountId, base).file.toPath())
            Unit
        }
        fun clear(source: SourceId, base: File = File(meloXDataDir())) = synchronized(lock) {
            val storage = PluginStorage(source, base = base)
            check(!storage.root.exists() || storage.root.deleteRecursively()) { "cannot clear plugin storage" }
        }
    }
}
