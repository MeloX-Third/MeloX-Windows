package melox.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/** Validates the candidate through its isolated runtime before exposing it to PluginManager. */
internal class PluginProcessProviderFactory(
    private val createRuntime: (PluginArchiveReader.Package) -> PluginProcessRuntime,
) : PluginManager.ProviderFactory {
    override fun prepare(plugin: PluginArchiveReader.Package): PluginManager.LoadedProvider {
        val runtime = createRuntime(plugin)
        try {
            val declared = JSONArray(runBlocking { runtime.invoke("__validate", JSONArray()) })
            require(declared.length() <= 64) { "INVALID_PLUGIN" }
            val methods = (0 until declared.length()).mapTo(mutableSetOf()) { index ->
                require(declared.opt(index) is String) { "INVALID_PLUGIN" }
                declared.getString(index)
            }
            require(plugin.manifest.capabilities.all { capability -> methods.containsAll(capability.methods) }) { "INVALID_PLUGIN" }
            return PluginManager.LoadedProvider(PluginMusicProvider(plugin.manifest, PluginInvoker(runtime::invoke)), runtime)
        } catch (failure: Throwable) {
            runtime.close()
            throw failure
        }
    }
}
