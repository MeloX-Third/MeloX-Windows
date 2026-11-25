package melox.plugin

import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import melox.music.model.SourceId
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Listener and pending authorization live only for the calling login coroutine. */
internal class PluginOAuthBrowserLogin(private val oauth: PluginDesktopOAuth) {
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<SourceId>()

    suspend fun login(manifest: PluginManifest, label: String,
        replaceSelected: Boolean = false,
        openBrowser: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
    ) = withContext(Dispatchers.IO) {
        check(active.add(manifest.sourceId)) { "plugin login already in progress" }
        var listener: HttpServer? = null
        try {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4)
            listener = server
            val redirect = "http://127.0.0.1:${server.address.port}/oauth2redirect"
            val authorization = (if (replaceSelected) oauth.beginReplacement(manifest, redirect)
                else oauth.begin(manifest, label, redirect)).toHttpUrl()
            val expectedState = checkNotNull(authorization.queryParameter("state"))
            val callback = CompletableDeferred<String>()
            server.createContext("/oauth2redirect") { exchange ->
                exchange.use {
                    val query = exchange.requestURI.rawQuery.orEmpty()
                    val valid = exchange.requestMethod == "GET" && exchange.requestURI.rawPath == "/oauth2redirect" &&
                        exchange.requestURI.rawFragment == null && exchange.remoteAddress.address.isLoopbackAddress &&
                        validQuery(query, expectedState)
                    val accepted = valid && callback.complete("$redirect?$query")
                    val body = (if (accepted) "Authorization received. Return to MeloX to finish signing in."
                        else "Invalid or expired authorization callback.").toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    exchange.sendResponseHeaders(if (accepted) 200 else 400, body.size.toLong())
                    exchange.responseBody.write(body)
                }
            }
            server.start()
            openBrowser(URI(authorization.toString()))
            withTimeout(600_000L) { oauth.complete(callback.await()) }
        } finally {
            listener?.stop(0)
            oauth.cancel(manifest.sourceId)
            active.remove(manifest.sourceId)
        }
    }

    internal companion object {
        fun validQuery(query: String, expected: String): Boolean = runCatching {
            require(query.length <= 8192)
            val pairs = query.split('&').map {
                val parts = it.split('=', limit = 2)
                require(parts.size == 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }
            require(pairs.map { it.first }.distinct().size == pairs.size)
            val values = pairs.toMap()
            val state = requireNotNull(values["state"])
            require(state.length == 43 && MessageDigest.isEqual(state.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8)))
            require(("code" in values) != ("error" in values))
            values["code"]?.let { require(it.length in 1..4096 && it.all { char -> char.code in 32..126 }) }
            true
        }.getOrDefault(false)
    }
}
