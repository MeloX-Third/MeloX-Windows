package melox.plugin

import java.io.Closeable
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Invocation-owned transport. No shared cookies, proxy, authenticators or application interceptors. */
class PluginHttpBroker internal constructor(
    private val manifest: PluginManifest,
    private val accountCurrent: () -> Boolean = { true },
    internalTransport: okhttp3.Interceptor? = null,
    private val credential: PluginCredentialLease? = null,
) : Closeable {
    init { require(credential == null || credential.source == manifest.sourceId) { "credential source mismatch" } }
    private val requests = AtomicInteger()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var closed = false
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(5, TimeUnit.SECONDS).connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
        .dns(PluginPublicDns(manifest.networkDomains)).apply {
            // Internal deterministic transport fixtures; production uses the isolated client above.
            internalTransport?.let(::addInterceptor)
        }.build()

    fun request(json: String): String {
        checkCurrent()
        PluginJson.check(json, 48 * 1024)
        val input = JSONObject(json)
        require(input.opt("url") is String && (!input.has("method") || input.opt("method") is String) &&
            (!input.has("body") || input.opt("body") is String) &&
            (!input.has("headers") || input.opt("headers") is JSONObject)) { "invalid HTTP request model" }
        val wantsCredential = input.has("credential")
        require(!wantsCredential || input.opt("credential") == "active") { "unknown credential reference" }
        var url = input.getString("url").toHttpUrl()
        var method = input.optString("method", "GET")
        require(method in setOf("GET", "POST"))
        val body = input.optString("body", "")
        require(body.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
        var headers = input.optJSONObject("headers") ?: JSONObject()
        require(headers.length() <= 32)
        repeat(4) { redirect ->
            authorize(url)
            checkCurrent()
            require(requests.incrementAndGet() <= 8) { "plugin request quota exceeded" }
            val builder = Request.Builder().url(url)
            headers.keys().forEach { name ->
                require(headers.opt(name) is String && name.lowercase(java.util.Locale.ROOT) !in forbiddenHeaders)
                val value = headers.getString(name)
                require(value.length <= 4096)
                builder.header(name, value)
            }
            if (wantsCredential) {
                val (name, value) = credentialHeader(url)
                builder.header(name, value)
            }
            if (method == "POST") builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            val call = client.newCall(builder.build())
            calls.add(call)
            try {
                checkCurrent()
                call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        require(redirect < 3) { "too many redirects" }
                        val next = requireNotNull(url.resolve(response.header("Location") ?: error("redirect missing location")))
                        authorize(next)
                        if (next.host != url.host) headers = JSONObject()
                        if (response.code == 303 || method == "POST" && response.code in setOf(301, 302)) method = "GET"
                        url = next
                    } else {
                        val bytes = bounded(requireNotNull(response.body), 64 * 1024)
                        val responseHeaders = JSONObject()
                        response.headers.names().filterNot { it.equals("set-cookie", true) }.forEach {
                            responseHeaders.put(it, response.header(it))
                        }
                        checkCurrent()
                        return JSONObject().put("status", response.code).put("headers", responseHeaders)
                            .put("body", bytes.toString(Charsets.UTF_8)).toString()
                    }
                }
            } finally { calls.remove(call) }
        }
        error("too many redirects")
    }

    private fun authorize(url: HttpUrl) {
        require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.host in manifest.networkDomains && url.toString().length <= 4096) { "PERMISSION_DENIED" }
    }

    fun authorizeMediaUrl(url: String) = authorize(url.toHttpUrl())

    private fun credentialHeader(url: HttpUrl): Pair<String, String> {
        val auth = manifest.authentication ?: error("PERMISSION_DENIED")
        require(auth.type in setOf("apiToken", "oauth2Pkce") && url.host in auth.domains) { "PERMISSION_DENIED" }
        val lease = credential ?: error("LOGIN_REQUIRED")
        require(lease.policy == auth) { "AUTH_EXPIRED" }
        val expiresAt = lease.expiresAt
        check(lease.isCurrent() && (expiresAt == null || expiresAt > System.currentTimeMillis())) { "AUTH_EXPIRED" }
        return auth.header to (auth.prefix + lease.token)
    }

    /** No credentials, cookies or redirects, including redirects within the declared domain set. */
    internal fun imageBytes(url: String): ByteArray {
        val destination = url.toHttpUrl()
        authorize(destination)
        checkCurrent()
        val call = client.newCall(Request.Builder().url(destination).build())
        calls.add(call)
        try {
            checkCurrent()
            return call.execute().use { response ->
                require(response.code == 200 && response.header("Content-Type")?.substringBefore(';')?.lowercase(java.util.Locale.ROOT) in
                    setOf("image/png", "image/jpeg", "image/webp")) { "unsupported artwork response" }
                bounded(requireNotNull(response.body), 2 * 1024 * 1024)
            }
        } finally { calls.remove(call) }
    }

    private fun bounded(body: okhttp3.ResponseBody, limit: Int): ByteArray {
        require(body.contentLength() <= limit) { "response body too large" }
        val output = java.io.ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                checkCurrent()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit) { "response body too large" }
                output.write(buffer, 0, count)
            }
        }
        checkCurrent()
        return output.toByteArray()
    }

    private fun checkCurrent() { check(!closed && accountCurrent() && (credential == null || credential.isCurrent())) { "CANCELLED" } }
    override fun close() {
        closed = true
        calls.forEach(Call::cancel)
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        val forbiddenHeaders = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection", "upgrade")
    }
}
