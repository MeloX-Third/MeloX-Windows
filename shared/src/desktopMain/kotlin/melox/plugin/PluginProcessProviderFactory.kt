package melox.plugin

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/** Validates the candidate through its isolated runtime before exposing it to PluginManager. */
internal class PluginProcessProviderFactory(
    private val mediaAccess: ((PluginArchiveReader.Package) -> PluginMediaAccess)? = null,
    private val createRuntime: (PluginArchiveReader.Package, () -> Boolean) -> PluginProcessRuntime,
) : PluginManager.ProviderFactory {
    override fun prepare(plugin: PluginArchiveReader.Package): PluginManager.LoadedProvider {
        val refreshRequested = AtomicBoolean()
        val runtime = createRuntime(plugin) { refreshRequested.getAndSet(false) }
        try {
            val declared = JSONArray(runBlocking { runtime.invoke("__validate", JSONArray()) })
            require(declared.length() <= 64) { "INVALID_PLUGIN" }
            val methods = (0 until declared.length()).mapTo(mutableSetOf()) { index ->
                require(declared.opt(index) is String) { "INVALID_PLUGIN" }
                declared.getString(index)
            }
            require(plugin.manifest.capabilities.all { capability -> methods.containsAll(capability.methods) }) { "INVALID_PLUGIN" }
            val provider = PluginMusicProvider(plugin.manifest, PluginInvoker(runtime::invoke))
            provider.authorizationRefresh = { refreshRequested.set(true) }
            mediaAccess?.let { capture -> provider.mediaAccess = { capture(plugin) } }
            return PluginManager.LoadedProvider(provider, runtime)
        } catch (failure: Throwable) {
            runtime.close()
            throw failure
        }
    }
}
