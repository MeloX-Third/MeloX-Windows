package melox.plugin

import kotlin.test.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody

class PluginOAuthBrowserLoginTest {
    @Test fun realLoopbackCallbackCommitsAccountAndClosesListener(): Unit = kotlinx.coroutines.runBlocking {
        val root = java.nio.file.Files.createTempDirectory("plugin-browser-login").toFile()
        val source = melox.music.model.SourceId.parse("plugin:example.browser")!!
        val registry = melox.music.provider.MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()))
        val auth = org.json.JSONObject().put("type", "oauth2Pkce").put("domains", listOf("api.example.com"))
            .put("authorizationEndpoint", "https://api.example.com/authorize").put("tokenEndpoint", "https://api.example.com/token")
            .put("clientId", "client").put("scopes", emptyList<String>())
        val manifestJson = org.json.JSONObject().put("id", "example.browser").put("name", "Browser").put("version", "1.0.0")
            .put("apiVersion", 1).put("entry", "index.js").put("capabilities", listOf("authentication"))
            .put("networkDomains", listOf("api.example.com")).put("authentication", auth).toString()
        val archive = java.io.ByteArrayOutputStream().apply {
            java.util.zip.ZipOutputStream(this).use { zip ->
                mapOf("manifest.json" to manifestJson, "index.js" to "globalThis.meloxPlugin={};").forEach { (name, text) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()
        val protection = object : melox.platform.SecretProtection {
            override fun protect(plain: ByteArray) = plain.reversedArray()
            override fun unprotect(encrypted: ByteArray) = encrypted.reversedArray()
        }
        val vault = PluginAccountVault(source, root, protection)
        var oauth: PluginDesktopOAuth? = null
        val manager = PluginManager(PluginPackageStore(root), registry, PluginManager.ProviderFactory { plugin ->
            PluginManager.LoadedProvider(PluginMusicProvider(plugin.manifest, PluginInvoker { _, _ -> error("unused") }), java.io.Closeable {})
        }, cancelAuthorization = { oauth?.cancel(it) })
        try {
            val manifest = manager.install(archive)
            manager.enable(source)
            var token = "fixture-token"
            var revokeDuringExchange = false
            val loginOAuth = PluginDesktopOAuth(manager, { vault }, { m, active ->
                PluginHttpBroker(m, active, okhttp3.Interceptor { chain ->
                    if (revokeDuringExchange) vault.activate(null)
                    okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture")
                        .body("""{"access_token":"$token","refresh_token":"refresh-token","token_type":"Bearer"}""".toResponseBody()).build()
                })
            })
            oauth = loginOAuth
            var callback = ""
            PluginOAuthBrowserLogin(loginOAuth).login(manifest, "Loopback") { authorization ->
                val url = authorization.toString().toHttpUrl()
                callback = checkNotNull(url.queryParameter("redirect_uri")) + "?state=" + url.queryParameter("state") + "&code=code"
                val invalid = java.net.URI(callback.replace("state=", "state=wrong")).toURL().openConnection() as java.net.HttpURLConnection
                invalid.connectTimeout = 2000; invalid.readTimeout = 2000
                try { assertEquals(400, invalid.responseCode) } finally { invalid.disconnect() }
                val connection = java.net.URI(callback).toURL().openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 2000; connection.readTimeout = 2000
                try { assertEquals(200, connection.responseCode); assertEquals("no-store", connection.getHeaderField("Cache-Control")) }
                finally { connection.disconnect() }
            }
            assertEquals("fixture-token", vault.lease()?.token)
            assertEquals("Loopback", vault.accounts().single().displayName)
            assertNotNull(registry[source])
            assertFails { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", java.net.URI(callback).port), 1000) } }
            val account = vault.accounts().single()
            token = "refreshed-token"
            loginOAuth.refresh(manifest)
            assertEquals("refreshed-token", vault.lease()?.token)
            assertEquals(account, vault.accounts().single())
            revokeDuringExchange = true
            token = "must-not-save"
            assertFails { loginOAuth.refresh(manifest) }
            vault.activate(account.id)
            assertEquals("refreshed-token", vault.lease()?.token)
        } finally { oauth?.close(); manager.close(); root.deleteRecursively() }
    }

    @Test fun callbackRequiresUniqueStateAndExactlyOneOutcome(): Unit {
        val state = "a".repeat(43)
        assertTrue(PluginOAuthBrowserLogin.validQuery("state=$state&code=code", state))
        assertTrue(PluginOAuthBrowserLogin.validQuery("state=$state&error=access_denied", state))
        for (query in listOf("state=wrong&code=code", "state=$state&code=", "state=$state", "state=$state&code=code&error=error",
            "state=$state&state=$state&code=code", "state=$state&%73tate=$state&code=code", "state=$state&code=%0A",
            "state=$state&code=%", "state=$state&code=" + "x".repeat(8192))) {
            assertFalse(PluginOAuthBrowserLogin.validQuery(query, state), query.take(100))
        }
    }
}
