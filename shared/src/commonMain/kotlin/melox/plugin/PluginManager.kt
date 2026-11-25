package melox.plugin

import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import melox.music.model.SourceId
import melox.music.provider.DynamicMusicProviders
import melox.music.provider.MusicProvider
import melox.music.provider.MusicProviderRegistry

/** Serial lifecycle owner. Call from IO; only validated prepared providers enter the shared registry. */
class PluginManager(
    private val store: PluginRepository,
    private val registry: MusicProviderRegistry,
    private val factory: ProviderFactory,
    private val cancelAuthorization: (SourceId) -> Unit = {},
    private val readPersistentDiagnostics: (SourceId) -> List<String> = { emptyList() },
    private val erasePersistentDiagnostics: (SourceId) -> Unit = {},
) : Closeable {
    fun interface ProviderFactory { fun prepare(plugin: PluginArchiveReader.Package): LoadedProvider }
    data class LoadedProvider(val provider: MusicProvider, val resources: Closeable)
    enum class Status { Disabled, Enabled, Failed }
    data class State(val manifest: PluginManifest, val status: Status, val error: String? = null,
        val publisher: PluginPackageTrust.Publisher? = null, val archiveSha256: String = "", val integrityChecked: Boolean = false)
    private val lock = Any()
    private var closed = false
    private val loaded = mutableMapOf<SourceId, LoadedProvider>()
    private val state = MutableStateFlow<Map<SourceId, State>>(emptyMap())
    val plugins = state.asStateFlow()
    private val damaged = MutableStateFlow<List<PluginPackageStore.Installed>>(emptyList())
    val damagedPackages = damaged.asStateFlow()
    private val recoveryIssue = MutableStateFlow<String?>(null)
    val recoveryError = recoveryIssue.asStateFlow()

    fun install(archive: ByteArray, approvedPublisherArchive: String? = null): PluginManifest = synchronized(lock) {
        checkOpen()
        val candidate = PluginArchiveReader.read(archive)
        val source = candidate.manifest.sourceId
        require(store.read(source) == null) { "plugin already installed" }
        val remembered = store.publisherFingerprint(source)
        require(remembered == null || remembered == (candidate.publisher?.fingerprint ?: PluginPackageTrust.UNSIGNED) ||
            approvedPublisherArchive == candidate.archiveSha256) { "PUBLISHER_CHANGE_APPROVAL_REQUIRED" }
        store.setEnabledSources(store.enabledSources() - source)
        store.install(archive).also { publish(candidate, Status.Disabled) }
    }

    fun restore(): List<PluginPackageStore.Installed> = synchronized(lock) {
        checkOpen()
        val installed = store.installed()
        damaged.value = installed.filter { it.manifest == null }
        val desired = try { store.enabledSources().also { recoveryIssue.value = null } }
        catch (_: Exception) { recoveryIssue.value = "插件启用状态无法读取，原包和用户数据已保留，可重置为全部禁用。"; emptySet() }
        installed.forEach { item ->
            val source = item.sourceId ?: return@forEach
            if (source !in loaded) {
                val plugin = try { store.read(source) } catch (_: Exception) { null } ?: return@forEach
                publish(plugin, Status.Disabled)
                if (source in desired) runCatching { enable(source) }
            }
        }
        installed
    }

    fun enable(source: SourceId) = synchronized(lock) {
        checkOpen()
        if (source in loaded) return@synchronized
        val plugin = store.read(source) ?: error("plugin is not installed")
        var candidate: LoadedProvider? = null
        var registered = false
        try {
            val pinned = store.publisherFingerprint(source)
            require(pinned == null || pinned == (plugin.publisher?.fingerprint ?: PluginPackageTrust.UNSIGNED)) { "PUBLISHER_RECORD_MISMATCH" }
            candidate = factory.prepare(plugin)
            require(candidate.provider.sourceId == source) { "provider identity mismatch" }
            registry.registerDynamic(candidate.provider, enabled = false)
            registered = true
            store.setEnabledSources(store.enabledSources() + source)
            loaded[source] = candidate
            registry.setDynamicEnabled(source, true)
            publish(plugin, Status.Enabled)
        } catch (failure: Throwable) {
            if (registered) registry.unregisterDynamic(source)
            loaded.remove(source)
            candidate?.let { runCatching { it.resources.close() } }
            publish(plugin, Status.Failed, PluginErrors.code(failure))
            throw failure
        }
    }

    fun disable(source: SourceId) = synchronized(lock) {
        checkOpen()
        cancelAuthorization(source)
        // Failed persistence must leave the currently running provider intact.
        store.setEnabledSources(store.enabledSources() - source)
        registry.unregisterDynamic(source)
        loaded.remove(source)?.let { runCatching { it.resources.close() } }
        state.value[source]?.let { state.value = state.value + (source to it.copy(status = Status.Disabled, error = null)) }
    }

    fun update(source: SourceId, archive: ByteArray, approvedManifest: PluginManifest? = null,
        approvedPublisherArchive: String? = null): PluginManifest = synchronized(lock) {
        checkOpen()
        cancelAuthorization(source)
        val previous = loaded[source]
        var candidate: LoadedProvider? = null
        var parsed: PluginArchiveReader.Package? = null
        try {
            val manifest = store.update(source, archive) { plugin ->
                val prior = store.read(source) ?: error("plugin is not installed")
                PluginPackageTrust.requireUpdate(prior, plugin, approvedPublisherArchive)
                require(!PluginPermissions.expands(prior.manifest, plugin.manifest) || approvedManifest == plugin.manifest) {
                    "PLUGIN_PERMISSION_APPROVAL_REQUIRED"
                }
                candidate = factory.prepare(plugin)
                require(candidate!!.provider.sourceId == source) { "provider identity mismatch" }
                DynamicMusicProviders().register(candidate!!.provider)
                parsed = plugin
            }
            if (previous != null) {
                registry.replaceDynamic(candidate!!.provider)
                loaded[source] = candidate!!
                candidate = null
                runCatching { previous.resources.close() }
            }
            publish(requireNotNull(parsed), if (previous == null) Status.Disabled else Status.Enabled)
            manifest
        } finally { candidate?.let { runCatching { it.resources.close() } } }
    }

    fun uninstall(source: SourceId) = synchronized(lock) {
        disable(source)
        store.uninstall(source)
        state.value = state.value - source
        runCatching { erasePersistentDiagnostics(source) }
    }

    fun diagnostics(source: SourceId): List<String> = synchronized(lock) {
        val entry = state.value[source] ?: return@synchronized listOf("插件未安装：${source.value}")
        buildList {
            add("${source.value} · ${entry.manifest.version} · API ${entry.manifest.apiVersion} · ${entry.status}")
            entry.error?.let { add("宿主错误：$it · ${PluginErrors.message(it)}") }
            runCatching { readPersistentDiagnostics(source) }.fold(
                onSuccess = { addAll(it) },
                onFailure = { add("持久诊断无法读取，原文件已保留，可清除后重试") },
            )
            if (source !in loaded) add("运行时未加载")
        }
    }

    fun clearDiagnostics(source: SourceId) = synchronized(lock) {
        checkOpen()
        erasePersistentDiagnostics(source)
    }

    fun resetEnabledState() = synchronized(lock) {
        checkOpen()
        store.setEnabledSources(emptySet())
        loaded.forEach { (source, item) ->
            cancelAuthorization(source); registry.unregisterDynamic(source); runCatching { item.resources.close() }
        }
        loaded.clear()
        recoveryIssue.value = null
        restore()
        Unit
    }

    fun dismissDamaged(storageKey: String) = synchronized(lock) {
        checkOpen()
        store.dismissDamaged(storageKey)
        damaged.value = damaged.value.filterNot { it.storageKey == storageKey }
    }
    fun publisherFingerprint(source: SourceId): String? = synchronized(lock) { store.publisherFingerprint(source) }

    fun accountChanged(source: SourceId) = synchronized(lock) {
        checkOpen()
        cancelAuthorization(source)
        val old = loaded.remove(source) ?: return@synchronized
        registry.unregisterDynamic(source)
        runCatching { old.resources.close() }
        enable(source)
    }

    internal fun commitAccount(source: SourceId, expected: PluginManifest, action: () -> Unit) = synchronized(lock) {
        checkOpen()
        check(state.value[source]?.manifest == expected && source in loaded) { "plugin changed during login" }
        replaceAccountContext(source, action)
    }
    internal fun changeAccount(source: SourceId, expected: PluginManifest, action: () -> Unit) = synchronized(lock) {
        checkOpen()
        check(state.value[source]?.manifest == expected) { "plugin changed during account operation" }
        replaceAccountContext(source, action)
    }
    private fun replaceAccountContext(source: SourceId, action: () -> Unit) {
        cancelAuthorization(source)
        val previous = loaded.remove(source)
        if (previous != null) {
            registry.unregisterDynamic(source)
            runCatching { previous.resources.close() }
        }
        var primary: Throwable? = null
        try { action() }
        catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            if (previous != null) try { enable(source) }
            catch (failure: Throwable) { primary?.addSuppressed(failure) ?: throw failure }
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        loaded.forEach { (source, item) ->
            cancelAuthorization(source); registry.unregisterDynamic(source); runCatching { item.resources.close() }
        }
        loaded.clear()
        state.value = emptyMap()
    }
    private fun publish(plugin: PluginArchiveReader.Package, status: Status, error: String? = null) {
        state.value = state.value + (plugin.manifest.sourceId to State(plugin.manifest, status, error,
            plugin.publisher, plugin.archiveSha256, plugin.integrityChecked))
    }
    private fun checkOpen() { check(!closed) { "plugin manager is closed" } }
}
