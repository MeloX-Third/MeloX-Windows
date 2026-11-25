package melox.plugin

import java.io.Closeable
import melox.music.model.SourceId

/** Windows account adapter. Browser/callback transport supplies the exact registered redirect. */
internal class PluginDesktopOAuth(manager: PluginManager,
    vault: (SourceId) -> PluginAccountVault = ::PluginAccountVault,
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
    )
    fun begin(manifest: PluginManifest, label: String, redirect: String) = session.begin(manifest, label, redirect)
    fun complete(callback: String) = session.complete(callback)
    fun cancel(source: SourceId) = session.cancel(source)
    override fun close() = session.close()
}
