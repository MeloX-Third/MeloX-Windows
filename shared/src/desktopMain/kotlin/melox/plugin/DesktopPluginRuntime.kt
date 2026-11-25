package melox.plugin

import java.io.File
import java.util.UUID
import melox.music.provider.MeloXMusicProviders
import melox.platform.meloXDataDir

/** Process-wide plugin lifecycle shared by navigation, playback and account management. */
object DesktopPluginRuntime {
    private val lock = Any()
    private var restored = false
    private var closed = false
    private val dataRoot by lazy { File(meloXDataDir()) }
    // One immutable bundle per application session. Never reuse unverified files after restart.
    private val launcher by lazy {
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator)
            .filter(String::isNotBlank).map { File(it).absoluteFile }
        WindowsPluginWorkerBundle.prepare(File(dataRoot, "melox-plugin-workers-v1/${UUID.randomUUID()}"),
            File(System.getProperty("java.home")), classpath)
    }
    private val lifecycle: PluginManager by lazy {
        PluginManager(PluginPackageStore(), MeloXMusicProviders.create(), PluginProcessProviderFactory(mediaAccess = { plugin ->
            val vault = PluginAccountVault(plugin.manifest.sourceId)
            PluginAccountInvocation(plugin.manifest, vault, dataRoot).capture("resolvePlayback")
            val stamp = vault.sessionStamp()
            val lease = vault.lease() ?: error("LOGIN_REQUIRED")
            require(lease.policy == plugin.manifest.authentication) { "AUTH_EXPIRED" }
            check(vault.sessionStamp() == stamp) { "CANCELLED" }
            PluginMediaAccess({ lease.isCurrent() && vault.sessionStamp() == stamp }) { active ->
                PluginHttpBroker(plugin.manifest, accountCurrent = active, credential = lease)
            }
        }) { plugin ->
            val accounts = PluginAccountInvocation(plugin.manifest, PluginAccountVault(plugin.manifest.sourceId), dataRoot)
            PluginProcessRuntime(plugin, launcher, { _, _ -> error("missing account invocation context") }, context = accounts::capture)
        }, cancelAuthorization = { source -> oauth.cancel(source) },
            readPersistentDiagnostics = { PluginDiagnostics(it).lines() },
            erasePersistentDiagnostics = { PluginDiagnostics(it).clear() })
    }
    private val oauth by lazy { PluginDesktopOAuth(lifecycle) }
    private val browserLogin by lazy { PluginOAuthBrowserLogin(oauth) }

    /** Call on IO. Restore never enables a newly installed plugin implicitly. */
    fun initialize() = synchronized(lock) {
        check(!closed) { "CANCELLED" }
        if (!restored) { lifecycle.restore(); restored = true }
    }

    fun manager(): PluginManager { initialize(); return lifecycle }

    fun accounts(source: melox.music.model.SourceId) = PluginAccountVault(source).accounts()
    fun selectedAccount(source: melox.music.model.SourceId) = PluginAccountVault(source).selected()
    fun selectAccount(source: melox.music.model.SourceId, id: String?) {
        val manager = manager()
        val expected = manager.plugins.value[source]?.manifest ?: error("SOURCE_UNAVAILABLE")
        manager.changeAccount(source, expected) { PluginAccountVault(source).activate(id) }
    }
    fun removeAccount(source: melox.music.model.SourceId, id: String) {
        val manager = manager()
        val expected = manager.plugins.value[source]?.manifest ?: error("SOURCE_UNAVAILABLE")
        manager.changeAccount(source, expected) {
            PluginAccountVault(source).remove(id)
            PluginStorage.clearAccount(source, id)
        }
    }
    fun importToken(source: melox.music.model.SourceId, label: String, token: String) {
        val manager = manager()
        val expected = manager.plugins.value[source]?.manifest ?: error("SOURCE_UNAVAILABLE")
        manager.changeAccount(source, expected) {
            PluginAccountVault(source).importTokenAndActivate(label, token, requireNotNull(expected.authentication))
        }
    }

    suspend fun login(source: melox.music.model.SourceId, label: String, replaceSelected: Boolean = false) {
        val state = manager().plugins.value[source] ?: error("SOURCE_UNAVAILABLE")
        browserLogin.login(state.manifest, label, replaceSelected)
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            if (restored) { oauth.close(); lifecycle.close() }
        }
    }
}
