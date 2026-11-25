package melox.plugin

import java.io.Closeable
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import melox.music.model.SourceId
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Host login state. The lifecycle owner must hold its gate while invoking the commit action. */
internal class PluginOAuthSession(
    private val current: (PluginManifest) -> Boolean,
    private val commit: (PluginManifest, () -> Unit) -> Unit,
    private val save: (PluginManifest, String, PluginOAuthContract.Tokens) -> Unit,
    private val broker: (PluginManifest, () -> Boolean) -> PluginHttpBroker = { manifest, active ->
        PluginHttpBroker(manifest, accountCurrent = active)
    },
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : Closeable {
    private class Pending(val manifest: PluginManifest, val label: String, val redirect: String,
        val state: String, val verifier: String, val started: Long,
        val saveReplacement: ((PluginOAuthContract.Tokens) -> Unit)? = null,
        var exchanging: Boolean = false, var transport: PluginHttpBroker? = null)
    private val lock = Any()
    private val pending = mutableMapOf<SourceId, Pending>()
    private var closed = false

    fun begin(manifest: PluginManifest, label: String, redirect: String,
        saveReplacement: ((PluginOAuthContract.Tokens) -> Unit)? = null,
    ): String {
        require(label.isNotBlank() && label.length <= 120)
        require(manifest.authentication?.type == "oauth2Pkce")
        val config = requireNotNull(manifest.authentication.oauth)
        validateRedirect(redirect)
        check(current(manifest)) { "plugin unavailable" }
        val attempt = Pending(manifest, label, redirect, random(), random(), elapsedMillis(), saveReplacement)
        val url = config.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code").addQueryParameter("client_id", config.clientId)
            .addQueryParameter("redirect_uri", redirect).addQueryParameter("scope", config.scopes.joinToString(" "))
            .addQueryParameter("state", attempt.state).addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", PluginOAuthContract.challenge(attempt.verifier)).build().toString()
        synchronized(lock) {
            check(!closed)
            pending.put(manifest.sourceId, attempt)?.transport?.close()
        }
        return url
    }

    fun cancel(source: SourceId) = synchronized(lock) { pending.remove(source)?.transport?.close(); Unit }

    fun complete(raw: String) {
        require(raw.length <= 8192)
        val uri = URI(raw)
        require(uri.rawFragment == null)
        val pairs = requireNotNull(uri.rawQuery).split('&').map {
            val parts = it.split('=', limit = 2)
            require(parts.size == 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) { "duplicate OAuth parameter" }
        val values = pairs.toMap()
        val state = requireNotNull(values["state"])
        require(state.matches(Regex("[A-Za-z0-9_-]{43}")))
        val attempt = synchronized(lock) {
            check(!closed)
            val found = pending.values.firstOrNull {
                MessageDigest.isEqual(it.state.toByteArray(Charsets.US_ASCII), state.toByteArray(Charsets.US_ASCII))
            } ?: error("unknown OAuth state")
            val expected = URI(found.redirect)
            require(uri.scheme == expected.scheme && uri.rawAuthority == expected.rawAuthority && uri.rawPath == expected.rawPath)
            check(!found.exchanging) { "OAuth callback replayed" }
            found.exchanging = true
            found
        }
        val source = attempt.manifest.sourceId
        try {
            check(elapsedMillis() - attempt.started in 0..600_000L) { "OAuth login expired" }
            require("error" !in values && values["code"] != null) { "OAuth authorization declined" }
            val code = checkNotNull(values["code"])
            require(code.length in 1..4096 && code.none { it.code < 32 || it.code == 127 })
            fun active() = synchronized(lock) { pending[source] === attempt } && current(attempt.manifest)
            check(active()) { "OAuth login cancelled" }
            val config = requireNotNull(attempt.manifest.authentication?.oauth)
            val tokens = broker(attempt.manifest, ::active).use { transport ->
                synchronized(lock) {
                    check(pending[source] === attempt)
                    attempt.transport = transport
                }
                PluginOAuthContract.parseTokens(transport.tokenRequest(config.tokenEndpoint, mapOf(
                    "grant_type" to "authorization_code", "code" to code, "client_id" to config.clientId,
                    "redirect_uri" to attempt.redirect, "code_verifier" to attempt.verifier)))
            }
            commit(attempt.manifest) {
                synchronized(lock) {
                    check(pending[source] === attempt) { "OAuth login cancelled" }
                    check(elapsedMillis() - attempt.started in 0..600_000L) { "OAuth login expired" }
                    attempt.saveReplacement?.invoke(tokens) ?: save(attempt.manifest, attempt.label, tokens)
                    pending.remove(source)
                }
            }
        } finally {
            synchronized(lock) { if (pending[source] === attempt) pending.remove(source) }
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        pending.values.forEach { it.transport?.close() }
        pending.clear()
    }

    private fun validateRedirect(raw: String) {
        val uri = URI(raw)
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null)
        require(raw == "com.lladlam.melox.plugin:/oauth2redirect" ||
            (uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535 && uri.rawPath == "/oauth2redirect"))
    }
    private fun random(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
}
