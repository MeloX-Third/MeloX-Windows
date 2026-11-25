package melox.plugin

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.UUID
import melox.music.model.SourceId
import melox.platform.DesktopSecretProtection
import melox.platform.SecretProtection
import melox.platform.meloXDataDir
import org.json.JSONArray
import org.json.JSONObject

/** One DPAPI-protected record per local slot; unreadable records are preserved for recovery. */
class PluginAccountVault internal constructor(
    private val source: SourceId,
    base: File,
    private val protection: SecretProtection?,
) {
    constructor(source: SourceId) : this(source, File(meloXDataDir()), DesktopSecretProtection.current)
    init { require(source.value.startsWith("plugin:")) }
    data class Account(val id: String, val displayName: String)
    internal class Lease(override val source: SourceId, val account: Account, override val token: String,
        override val policy: PluginAuthentication, val refreshToken: String? = null, override val expiresAt: Long? = null,
        val selectionStamp: String? = null, private val current: () -> Boolean = { false }) : PluginCredentialLease {
        override fun isCurrent(): Boolean = current()
    }
    internal class Tokens(val accessToken: String, val refreshToken: String? = null, val expiresAt: Long? = null)
    private val root = File(base, "melox-plugin-accounts-v1/${PluginPackageTrust.sha256(source.value.toByteArray(Charsets.UTF_8))}")
    private val selectedFile get() = File(root, "selected")
    private fun file(id: String): File {
        require(id.matches(accountId)) { "invalid account id" }
        return File(root, "$id.dpapi")
    }

    fun importToken(displayName: String, token: String, policy: PluginAuthentication): Account = synchronized(lock) {
        require(policy.type == "apiToken" && policy.domains.isNotEmpty())
        create(displayName, Tokens(token), policy)
    }
    fun importTokenAndActivate(displayName: String, token: String, policy: PluginAuthentication): Account = synchronized(lock) {
        activateNew { importToken(displayName, token, policy) }
    }
    internal fun importOAuthAndActivate(displayName: String, tokens: Tokens, policy: PluginAuthentication): Account = synchronized(lock) {
        require(policy.type == "oauth2Pkce" && policy.oauth != null)
        activateNew { create(displayName, tokens, policy) }
    }
    private fun activateNew(create: () -> Account): Account {
        val account = create()
        try { activate(account.id) } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(file(account.id).toPath()) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        return account
    }
    private fun create(label: String, tokens: Tokens, policy: PluginAuthentication): Account {
        require(label.isNotBlank() && label.length <= 120)
        require(accounts().size < 16) { "account quota exceeded" }
        val account = Account(UUID.randomUUID().toString(), label)
        encrypt(Lease(source, account, tokens.accessToken, policy, tokens.refreshToken, tokens.expiresAt))
        return account
    }
    private fun validateToken(token: String) {
        require(token.length in 1..4096 && token.all { it.code in 33..126 }) { "invalid account token" }
    }
    private fun encrypt(lease: Lease) {
        require(lease.source == source)
        validateToken(lease.token)
        lease.refreshToken?.let(::validateToken)
        val auth = lease.policy
        val policy = JSONObject().put("type", auth.type).put("header", auth.header).put("prefix", auth.prefix)
            .put("domains", JSONArray(auth.domains.toList()))
        auth.oauth?.let { policy.put("oauth", JSONObject().put("authorizationEndpoint", it.authorizationEndpoint)
            .put("tokenEndpoint", it.tokenEndpoint).put("clientId", it.clientId).put("scopes", JSONArray(it.scopes))) }
        val plain = JSONObject().put("source", source.value).put("id", lease.account.id).put("displayName", lease.account.displayName)
            .put("token", lease.token).put("policy", policy).put("refreshToken", lease.refreshToken).put("expiresAt", lease.expiresAt)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            require(plain.size <= 15 * 1024)
            val protector = checkNotNull(protection) { "Windows credential protection unavailable" }
            val encrypted = protector.protect(plain)
            val verified = protector.unprotect(encrypted)
            try { check(plain.contentEquals(verified)) { "credential protection verification failed" } }
            finally { verified.fill(0) }
            require(encrypted.size <= MAX_RECORD)
            PluginPrivateFiles.write(file(lease.account.id), encrypted)
        } finally { plain.fill(0) }
    }
    private fun decrypt(id: String): Lease {
        val encrypted = PluginPrivateFiles.read(file(id), MAX_RECORD)
        val plain = checkNotNull(protection) { "Windows credential protection unavailable" }.unprotect(encrypted)
        try {
            val raw = plain.toString(Charsets.UTF_8)
            PluginJson.check(raw, 15 * 1024)
            val value = JSONObject(raw)
            require(value.getString("source") == source.value && value.getString("id") == id) { "credential identity mismatch" }
            val policy = value.getJSONObject("policy")
            fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
            val oauth = policy.optJSONObject("oauth")?.let {
                PluginOAuthConfiguration(it.getString("authorizationEndpoint"), it.getString("tokenEndpoint"),
                    it.getString("clientId"), strings(it.getJSONArray("scopes")))
            }
            return Lease(source, Account(id, value.getString("displayName")), value.getString("token"),
                PluginAuthentication(policy.getString("type"), policy.getString("header"), policy.getString("prefix"),
                    strings(policy.getJSONArray("domains")).toSet(), oauth),
                if (value.has("refreshToken")) value.getString("refreshToken") else null,
                if (value.has("expiresAt")) value.getLong("expiresAt") else null)
        } finally { plain.fill(0) }
    }
    fun accounts(): List<Account> = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.name.endsWith(".dpapi") && it.name.removeSuffix(".dpapi").matches(accountId) }.map {
            val id = it.name.removeSuffix(".dpapi")
            runCatching { decrypt(id).account }.getOrElse { Account(id, "无法解密的账号 ${id.take(8)}（原数据已保留）") }
        }
    }
    private fun selection(): String? = try { PluginPrivateFiles.read(selectedFile, 128).toString(Charsets.UTF_8) }
        catch (_: FileNotFoundException) { null }
    fun selected(): String? = synchronized(lock) { selection()?.substringBefore('\n')?.takeIf(String::isNotEmpty) }
    internal fun sessionStamp(): String? = synchronized(lock) {
        val selection = selection() ?: return@synchronized null
        val id = selection.substringBefore('\n').takeIf(String::isNotEmpty) ?: return@synchronized selection
        val fingerprint = try { PluginPackageTrust.sha256(PluginPrivateFiles.read(file(id), MAX_RECORD)) }
            catch (_: FileNotFoundException) { "missing" }
        "$selection\ncredential:$fingerprint"
    }
    fun activate(id: String?) = synchronized(lock) {
        if (id != null) decrypt(id)
        PluginPrivateFiles.write(selectedFile, (id.orEmpty() + "\n" + UUID.randomUUID()).toByteArray(Charsets.UTF_8))
    }
    internal fun lease(): Lease? = synchronized(lock) {
        val stamp = sessionStamp()
        selected()?.let { id -> decrypt(id).let {
            Lease(source, it.account, it.token, it.policy, it.refreshToken, it.expiresAt, stamp,
                current = { synchronized(lock) { sessionStamp() == stamp } })
        } }
    }
    internal fun replaceTokens(previous: Lease, tokens: Tokens): Lease = synchronized(lock) {
        replaceAuthorization(previous, tokens, previous.policy, true)
    }
    internal fun reauthorize(previous: Lease, tokens: Tokens, policy: PluginAuthentication): Lease = synchronized(lock) {
        require(policy.type == "oauth2Pkce" && policy.oauth != null)
        replaceAuthorization(previous, tokens, policy, false)
    }
    private fun replaceAuthorization(previous: Lease, tokens: Tokens, policy: PluginAuthentication, retainRefresh: Boolean): Lease {
        require(previous.source == source)
        check(selected() == previous.account.id && previous.selectionStamp != null && sessionStamp() == previous.selectionStamp) { "account session changed" }
        val current = decrypt(previous.account.id)
        check(current.token == previous.token && current.policy == previous.policy) { "account changed" }
        encrypt(Lease(source, current.account, tokens.accessToken, policy,
            tokens.refreshToken ?: current.refreshToken.takeIf { retainRefresh }, tokens.expiresAt))
        return checkNotNull(lease())
    }
    fun remove(id: String) = synchronized(lock) {
        file(id) // Validate before changing selection.
        if (selected() == id) activate(null)
        Files.deleteIfExists(file(id).toPath())
        Unit
    }
    fun clear() = synchronized(lock) {
        activate(null)
        accounts().forEach { Files.deleteIfExists(file(it.id).toPath()) }
    }
    private companion object {
        val lock = Any()
        val accountId = Regex("[a-f0-9-]{36}")
        const val MAX_RECORD = 32 * 1024
    }
}
