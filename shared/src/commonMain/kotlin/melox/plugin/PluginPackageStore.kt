package melox.plugin

import melox.music.model.SourceId
import melox.platform.meloXDataDir
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

interface PluginRepository {
    fun install(archive: ByteArray): PluginManifest
    fun read(sourceId: SourceId): PluginArchiveReader.Package?
    fun update(sourceId: SourceId, archive: ByteArray, prepare: (PluginArchiveReader.Package) -> Unit): PluginManifest
    fun installed(): List<PluginPackageStore.Installed>
    fun enabledSources(): Set<SourceId>
    fun setEnabledSources(sources: Set<SourceId>)
    fun uninstall(sourceId: SourceId)
    fun publisherFingerprint(sourceId: SourceId): String?
    fun dismissDamaged(storageKey: String)
}

/** Validated private packages. A write-ahead decision couples package bytes to publisher records. */
class PluginPackageStore(
    private val directory: File = File(meloXDataDir(), "melox-plugin-packages-v1"),
    private val checkpoint: (String) -> Unit = {},
) : PluginRepository {
    data class Installed(val sourceId: SourceId?, val manifest: PluginManifest?, val error: String?, val storageKey: String? = null)

    override fun install(archive: ByteArray): PluginManifest = synchronized(lock) {
        val plugin = PluginArchiveReader.read(archive)
        recover(plugin.manifest.sourceId)
        require(!fileFor(plugin.manifest.sourceId).exists()) { "plugin already installed" }
        commit(plugin, archive, null)
        plugin.manifest
    }

    override fun update(sourceId: SourceId, archive: ByteArray, prepare: (PluginArchiveReader.Package) -> Unit): PluginManifest = synchronized(lock) {
        val old = read(sourceId) ?: error("plugin is not installed")
        val candidate = PluginArchiveReader.read(archive)
        require(candidate.manifest.sourceId == sourceId) { "plugin identity cannot change during upgrade" }
        require(PluginVersion.compare(candidate.manifest.version, old.manifest.version) > 0) { "upgrade requires a newer version" }
        prepare(candidate)
        commit(candidate, archive, bounded(fileFor(sourceId), MAX_ARCHIVE_BYTES))
        candidate.manifest
    }

    override fun read(sourceId: SourceId): PluginArchiveReader.Package? = synchronized(lock) {
        recover(sourceId)
        val bytes = try { bounded(fileFor(sourceId), MAX_ARCHIVE_BYTES) } catch (_: FileNotFoundException) { return@synchronized null }
        PluginArchiveReader.read(bytes).also { require(it.manifest.sourceId == sourceId) { "stored plugin identity mismatch" } }
    }

    override fun installed(): List<Installed> = synchronized(lock) {
        val interrupted = directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.plugin\\.transaction")) }.mapNotNull { file ->
            try {
                val decision = json(file, 16 * 1024)
                val source = requireNotNull(SourceId.parse(decision.getString("source")))
                require(transaction(source).name == file.name)
                recover(source)
                null
            } catch (failure: Exception) { Installed(null, null, "TRANSACTION_${failure.javaClass.simpleName}", file.name) }
        }
        val packages = directory.listFiles().orEmpty().filter { it.name.matches(packageName) }.sortedBy { it.name }.map { file ->
            if (interrupted.any { it.storageKey == "${file.name}.transaction" }) {
                return@map Installed(null, null, "TRANSACTION_UNRECOVERED", file.name)
            }
            try {
                val plugin = PluginArchiveReader.read(bounded(file, MAX_ARCHIVE_BYTES))
                require(fileFor(plugin.manifest.sourceId).name == file.name)
                Installed(plugin.manifest.sourceId, plugin.manifest, null)
            } catch (failure: Exception) {
                val quarantine = File(directory, "quarantine").apply { check(isDirectory || mkdirs()) }
                val target = File(quarantine, file.name)
                // Preserve any previous quarantined bytes, too; a collision remains visible in place.
                if (!target.exists()) Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                Installed(null, null, failure.javaClass.simpleName, file.name)
            }
        }
        val quarantined = File(directory, "quarantine").listFiles().orEmpty().filter { it.name.matches(packageName) }
            .map { Installed(null, null, "QUARANTINED", it.name) }
        (packages + quarantined + interrupted).distinctBy { it.storageKey ?: it.sourceId?.value }
    }

    override fun dismissDamaged(storageKey: String) = synchronized(lock) {
        require(storageKey.matches(packageName))
        Files.deleteIfExists(File(File(directory, "quarantine"), storageKey).toPath())
        Unit
    }

    override fun publisherFingerprint(sourceId: SourceId): String? = synchronized(lock) {
        recover(sourceId)
        val value = try { json(trust(sourceId), 8192) } catch (_: FileNotFoundException) { return@synchronized null }
        require(value.getString("source") == sourceId.value)
        value.getString("fingerprint")
    }

    override fun enabledSources(): Set<SourceId> = synchronized(lock) {
        val raw = try { bounded(File(directory, "enabled.json"), 64 * 1024).toString(Charsets.UTF_8) }
            catch (_: FileNotFoundException) { return@synchronized emptySet() }
        PluginJson.check(raw, 64 * 1024)
        val values = JSONArray(raw)
        buildSet {
            for (i in 0 until values.length()) {
                require(values.opt(i) is String)
                val source = requireNotNull(SourceId.parse(values.getString(i)))
                require(source.builtIn == null && add(source))
            }
        }
    }

    override fun setEnabledSources(sources: Set<SourceId>) = synchronized(lock) {
        require(sources.all { it.builtIn == null })
        val raw = JSONArray(sources.map { it.value }.sorted()).toString()
        PluginJson.check(raw, 64 * 1024)
        atomic(File(directory, "enabled.json"), raw.toByteArray(Charsets.UTF_8))
    }

    override fun uninstall(sourceId: SourceId) = synchronized(lock) {
        recover(sourceId)
        Files.deleteIfExists(fileFor(sourceId).toPath())
        Unit
    }

    private fun recordPublisher(plugin: PluginArchiveReader.Package) {
        atomic(trust(plugin.manifest.sourceId), JSONObject().put("source", plugin.manifest.sourceId.value)
            .put("fingerprint", plugin.publisher?.fingerprint ?: PluginPackageTrust.UNSIGNED)
            .put("publicKey", plugin.publisher?.publicKey).toString().toByteArray(Charsets.UTF_8))
    }

    private fun recover(source: SourceId) {
        val journal = transaction(source)
        val decision = try { json(journal, 16 * 1024) } catch (_: FileNotFoundException) { return }
        require(decision.getString("source") == source.value)
        val bytes = try { bounded(fileFor(source), MAX_ARCHIVE_BYTES) } catch (_: FileNotFoundException) { null }
        val hash = bytes?.let(PluginPackageTrust::sha256)
        if (hash == decision.getString("next")) {
            val plugin = PluginArchiveReader.read(requireNotNull(bytes))
            require(plugin.manifest.sourceId == source)
            recordPublisher(plugin)
        } else {
            require(hash == decision.getString("previous").takeIf(String::isNotEmpty)) { "package transaction cannot be recovered" }
            if (decision.has("previousTrust")) {
                if (decision.isNull("previousTrust")) Files.deleteIfExists(trust(source).toPath())
                else atomic(trust(source), decision.getString("previousTrust").toByteArray(Charsets.UTF_8))
            }
        }
        Files.delete(journal.toPath())
    }

    private fun commit(plugin: PluginArchiveReader.Package, bytes: ByteArray, previous: ByteArray?) {
        val source = plugin.manifest.sourceId
        val journal = transaction(source)
        val oldTrust = try { bounded(trust(source), 8192) } catch (_: FileNotFoundException) { null }
        val decision = JSONObject().put("source", source.value).put("next", plugin.archiveSha256)
            .put("previous", previous?.let(PluginPackageTrust::sha256).orEmpty())
            .put("previousTrust", oldTrust?.toString(Charsets.UTF_8) ?: JSONObject.NULL).toString()
        PluginJson.check(decision, 16 * 1024)
        atomic(journal, decision.toByteArray(Charsets.UTF_8))
        checkpoint("decision-durable")
        try {
            atomic(fileFor(source), bytes)
            checkpoint("archive-durable")
            recordPublisher(plugin)
            checkpoint("publisher-durable")
            Files.delete(journal.toPath())
        } catch (failure: Throwable) {
            try {
                if (previous == null) Files.deleteIfExists(fileFor(source).toPath()) else atomic(fileFor(source), previous)
                checkpoint("rollback-archive-durable")
                if (oldTrust == null) Files.deleteIfExists(trust(source).toPath()) else atomic(trust(source), oldTrust)
                checkpoint("rollback-publisher-durable")
                Files.delete(journal.toPath())
            } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    private fun fileFor(source: SourceId): File {
        require(source.builtIn == null)
        return File(directory, "${PluginPackageTrust.sha256(source.value.toByteArray(Charsets.UTF_8))}.plugin")
    }
    private fun trust(source: SourceId) = File(directory, "trust/${fileFor(source).name}.json")
    private fun transaction(source: SourceId) = File(directory, "${fileFor(source).name}.transaction")
    private fun json(file: File, limit: Int): JSONObject {
        val raw = bounded(file, limit).toString(Charsets.UTF_8)
        PluginJson.check(raw, limit)
        return JSONObject(raw)
    }
    private fun bounded(file: File, limit: Int): ByteArray = file.inputStream().use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= limit) { "stored plugin file too large" }
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    }
    private fun atomic(file: File, bytes: ByteArray) {
        check(file.parentFile.isDirectory || file.parentFile.mkdirs())
        val temporary = Files.createTempFile(file.parentFile.toPath(), "plugin-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    private companion object {
        const val MAX_ARCHIVE_BYTES = 4 * 1024 * 1024
        val packageName = Regex("[a-f0-9]{64}\\.plugin")
        val lock = Any()
    }
}
