package melox.plugin

import java.io.Closeable
import melox.music.model.SourceId

/** Windows account adapter. Browser/callback transport supplies the exact registered redirect. */
internal class PluginDesktopOAuth(private val manager: PluginManager,
    private val vault: (SourceId) -> PluginAccountVault = ::PluginAccountVault,
    private val broker: (PluginManifest, () -> Boolean) -> PluginHttpBroker = { manifest, active ->
        PluginHttpBroker(manifest, accountCurrent = active)
    },
) : Closeable {
    private val session = PluginOAuthSession(
        current = { manifest -> manager.plugins.value[manifest.sourceId]?.let {
            it.status == PluginManager.Status.Enabled && it.manifest == manifest
        } == true },
        commit = { manifest, action -> manager.commitOAuthAccount(manifest.sourceId, manifest, action) },
        save = { manifest, label, tokens ->
            vault(manifest.sourceId).importOAuthAndActivate(label,
                PluginAccountVault.Tokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt),
                requireNotNull(manifest.authentication))
        },
        broker = broker,
    )
    fun begin(manifest: PluginManifest, label: String, redirect: String) = session.begin(manifest, label, redirect)
    fun beginReplacement(manifest: PluginManifest, redirect: String): String {
        val accountVault = vault(manifest.sourceId)
        val previous = checkNotNull(accountVault.lease()) { "LOGIN_REQUIRED" }
        return session.begin(manifest, previous.account.displayName, redirect) { tokens ->
            accountVault.reauthorize(previous,
                PluginAccountVault.Tokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt),
                requireNotNull(manifest.authentication))
        }
    }

    /** Caller owns IO dispatch. CAS refuses a response arriving after selection or credential changes. */
    fun refresh(manifest: PluginManifest) {
        val accountVault = vault(manifest.sourceId)
        val lease = checkNotNull(accountVault.lease()) { "LOGIN_REQUIRED" }
        require(lease.policy == manifest.authentication && lease.policy.type == "oauth2Pkce") { "AUTH_EXPIRED" }
        val config = requireNotNull(lease.policy.oauth)
        val refresh = lease.refreshToken ?: error("AUTH_EXPIRED")
        fun active() = lease.isCurrent() && manager.plugins.value[manifest.sourceId]?.let {
            it.status == PluginManager.Status.Enabled && it.manifest == manifest
        } == true
        check(active()) { "AUTH_EXPIRED" }
        val tokens = broker(manifest, ::active).use {
            PluginOAuthContract.parseTokens(it.tokenRequest(config.tokenEndpoint, mapOf(
                "grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to config.clientId)))
        }
        manager.commitAccount(manifest.sourceId, manifest) {
            accountVault.replaceTokens(lease, PluginAccountVault.Tokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt))
        }
    }
    fun complete(callback: String) = session.complete(callback)
    fun cancel(source: SourceId) = session.cancel(source)
    override fun close() = session.close()
}
