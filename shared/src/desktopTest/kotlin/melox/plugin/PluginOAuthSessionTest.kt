package melox.plugin

import melox.music.model.SourceId
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class PluginOAuthSessionTest {
    private val config = PluginOAuthConfiguration("https://api.example.com/authorize", "https://api.example.com/token", "client", listOf("music"))
    private val manifest = PluginManifest(SourceId.parse("plugin:example.login")!!, "Login", "1.0.0", 1, "index.js", emptySet(),
        setOf("api.example.com"), PluginAuthentication("oauth2Pkce", domains = setOf("api.example.com"), oauth = config))
    private val redirect = "http://127.0.0.1:43210/oauth2redirect"

    @Test fun reauthorizationUsesCapturedReplacementInsteadOfCreatingAnotherSlot(): Unit {
        var replacements = 0
        PluginOAuthSession({ true }, { _, action -> action() }, { _, _, _ -> error("must not create slot") },
            broker = { m, active -> PluginHttpBroker(m, active, Interceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body("""{"access_token":"replacement","token_type":"Bearer"}""".toResponseBody()).build()
            }) }).use { session ->
            val url = session.begin(manifest, "Existing", redirect) { tokens ->
                assertEquals("replacement", tokens.accessToken)
                replacements++
            }.toHttpUrl()
            session.complete("$redirect?state=${url.queryParameter("state")}&code=code")
            assertEquals(1, replacements)
        }
    }

    @Test fun claimsStateBeforeExchangeAndCommitsOnlyOnce(): Unit {
        var calls = 0
        var saves = 0
        lateinit var session: PluginOAuthSession
        var callback = ""
        session = PluginOAuthSession({ true }, { _, action -> action() }, { _, label, tokens ->
            assertEquals("Account", label)
            assertEquals("host-token", tokens.accessToken)
            saves++
        }, broker = { m, active -> PluginHttpBroker(m, active, Interceptor { chain ->
            calls++
            assertFails { session.complete(callback) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"access_token":"host-token","token_type":"Bearer"}""".toResponseBody()).build()
        }) })
        session.use {
            val url = it.begin(manifest, "Account", redirect).toHttpUrl()
            assertEquals("S256", url.queryParameter("code_challenge_method"))
            assertEquals(43, url.queryParameter("code_challenge")?.length)
            callback = "$redirect?state=${url.queryParameter("state")}&code=authorization-code"
            assertFails { it.complete(callback.replace("43210", "43211")) }
            assertFails { it.complete(callback + "&state=${url.queryParameter("state")}") }
            assertEquals(0, calls)
            it.complete(callback)
            assertFails { it.complete(callback) }
            assertEquals(1, calls)
            assertEquals(1, saves)
        }
        assertFails { session.begin(manifest, "Account", redirect) }
    }

    @Test fun cancellationExpiryReplacementAndLifecycleChangesCannotSaveTokens(): Unit {
        for (mode in listOf("cancel", "expiry", "changed", "replacement", "duringExchange", "duringCommit", "declined")) {
            var now = 100L
            var current = true
            var calls = 0
            var saves = 0
            lateinit var session: PluginOAuthSession
            session = PluginOAuthSession({ current }, { _, action ->
                if (mode == "duringCommit") session.cancel(manifest.sourceId)
                action()
            }, { _, _, _ -> saves++ }, broker = { m, active -> PluginHttpBroker(m, active, Interceptor { chain ->
                calls++
                if (mode == "duringExchange") session.cancel(manifest.sourceId)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body("""{"access_token":"host-token","token_type":"Bearer"}""".toResponseBody()).build()
            }) }, elapsedMillis = { now })
            session.use {
                val url = it.begin(manifest, "Account", redirect).toHttpUrl()
                val callback = "$redirect?state=${url.queryParameter("state")}&" + if (mode == "declined") "error=access_denied" else "code=code"
                when (mode) {
                    "cancel" -> it.cancel(manifest.sourceId)
                    "expiry" -> now += 600_001
                    "changed" -> current = false
                    "replacement" -> it.begin(manifest, "New", redirect)
                }
                assertFails(mode) { it.complete(callback) }
                assertEquals(0, saves, mode)
                assertEquals(if (mode.startsWith("during")) 1 else 0, calls, mode)
                assertFails(mode) { it.complete(callback) }
            }
        }
    }
}
