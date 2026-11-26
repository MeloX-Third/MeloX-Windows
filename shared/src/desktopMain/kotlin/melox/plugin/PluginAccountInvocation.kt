package melox.plugin

import java.io.File
import org.json.JSONObject

/** Capture one account selection for the entire invocation; secrets stay in the host broker. */
internal class PluginAccountInvocation(
    private val manifest: PluginManifest,
    private val vault: PluginAccountVault,
    private val storageBase: File,
    private val broker: (PluginManifest, () -> Boolean, PluginCredentialLease?) -> PluginHttpBroker = { manifest, active, lease ->
        PluginHttpBroker(manifest, accountCurrent = active, credential = lease)
    },
) {
    fun capture(method: String = "__validate", forceRefresh: Boolean = false): PluginInvocationContext {
        var stamp = vault.sessionStamp()
        val selected = vault.selected()
        // Unreadable credentials leave public methods available; explicit credential calls fail.
        var lease = runCatching { vault.lease() }.getOrNull()?.takeIf { it.policy == manifest.authentication }
        check(vault.sessionStamp() == stamp) { "CANCELLED" }
        val previous = lease
        if (method != "__validate" && previous?.policy?.type == "oauth2Pkce" &&
            (forceRefresh || previous.expiresAt?.let { it <= System.currentTimeMillis() + 30_000 } == true)) {
            val configuration = requireNotNull(previous.policy.oauth)
            val refresh = previous.refreshToken
            if (refresh != null) try {
                val active = { previous.isCurrent() && vault.sessionStamp() == stamp }
                val tokens = broker(manifest, active, null).use {
                    PluginOAuthContract.parseTokens(it.tokenRequest(configuration.tokenEndpoint, mapOf(
                        "grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to configuration.clientId)))
                }
                check(active()) { "CANCELLED" }
                val refreshed = vault.replaceTokens(previous, PluginAccountVault.Tokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt))
                lease = refreshed
                stamp = refreshed.selectionStamp
            } catch (failure: Exception) {
                if (failure is InterruptedException || failure is java.util.concurrent.CancellationException) throw failure
                check(vault.sessionStamp() == stamp) { "CANCELLED" }
                // Keep the account for reauthorization; expired credentials remain rejected by the broker.
            }
        }
        val account = lease?.account?.let {
            JSONObject().put("id", it.id).put("displayName", it.displayName)
        }
        val current = { vault.sessionStamp() == stamp }
        return PluginInvocationContext(account, current) { token, active ->
            PluginHostCalls(token, broker(manifest, { active() && current() }, lease),
                PluginStorage(manifest.sourceId, selected, storageBase), { active() && current() })
        }
    }
}
