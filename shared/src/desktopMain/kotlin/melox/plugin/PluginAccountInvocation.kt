package melox.plugin

import java.io.File
import org.json.JSONObject

/** Capture one account selection for the entire invocation; secrets stay in the host broker. */
internal class PluginAccountInvocation(
    private val manifest: PluginManifest,
    private val vault: PluginAccountVault,
    private val storageBase: File,
) {
    fun capture(): PluginInvocationContext {
        val stamp = vault.sessionStamp()
        val selected = vault.selected()
        // Unreadable credentials leave public methods available; explicit credential calls fail.
        val lease = runCatching { vault.lease() }.getOrNull()?.takeIf { it.policy == manifest.authentication }
        check(vault.sessionStamp() == stamp) { "CANCELLED" }
        val account = selected?.let {
            JSONObject().put("id", it).put("displayName", lease?.account?.displayName ?: "")
        }
        val current = { vault.sessionStamp() == stamp }
        return PluginInvocationContext(account, current) { token, active ->
            PluginHostCalls(token, PluginHttpBroker(manifest, accountCurrent = { active() && current() }, credential = lease),
                PluginStorage(manifest.sourceId, selected, storageBase), { active() && current() })
        }
    }
}
