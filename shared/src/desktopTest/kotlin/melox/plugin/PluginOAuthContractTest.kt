package melox.plugin

import melox.music.model.SourceId
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import kotlin.test.*

class PluginOAuthContractTest {
    @Test fun pkceAndTokenParsingPreserveHostOnlyRefreshAndExpiry(): Unit {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            PluginOAuthContract.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val raw = """{"access_token":"private-access","refresh_token":"private-refresh","token_type":"Bearer","expires_in":3600}"""
        val parsed = PluginOAuthContract.parseTokens(JSONObject(raw), 1000)
        assertEquals("private-access", parsed.accessToken)
        assertEquals("private-refresh", parsed.refreshToken)
        assertEquals(3_601_000L, parsed.expiresAt)
        assertFalse(parsed.toString().contains("private-access"))
        for (bad in listOf(raw.replace("Bearer", "MAC"), raw.replace("3600", "-1"), raw.replace("3600", "0.5"),
            raw.replace("\"private-access\"", "123"), raw.replace("private-refresh", "bad\\nrefresh"))) {
            assertFails { PluginOAuthContract.parseTokens(JSONObject(bad)) }
        }
        assertFails { PluginOAuthContract.parseTokens(JSONObject(raw), Long.MAX_VALUE) }
    }

    @Test fun tokenExchangeChecksExactEndpointStatusSizeAndRevocation(): Unit {
        val oauth = PluginOAuthConfiguration("https://api.example.com/authorize", "https://api.example.com/token", "public-client", emptyList())
        val manifest = PluginManifest(SourceId.parse("plugin:example.oauth")!!, "OAuth", "1.0.0", 1, "index.js", emptySet(),
            setOf("api.example.com"), PluginAuthentication("oauth2Pkce", domains = setOf("api.example.com"), oauth = oauth))
        var calls = 0
        var status = 200
        var body = """{"access_token":"host-only","token_type":"Bearer"}"""
        var current = true
        var revoke = false
        PluginHttpBroker(manifest, accountCurrent = { current }, internalTransport = Interceptor { chain ->
            calls++
            assertEquals("POST", chain.request().method)
            assertNull(chain.request().header("Authorization"))
            if (revoke) current = false
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).header("Location", "https://api.example.com/token").build()
        }).use { broker ->
            assertFails { broker.tokenRequest("https://api.example.com/other", emptyMap()) }
            assertEquals(0, calls)
            assertEquals("host-only", broker.tokenRequest(oauth.tokenEndpoint, mapOf("code" to "secret-code")).getString("access_token"))
            status = 302
            assertFails { broker.tokenRequest(oauth.tokenEndpoint, emptyMap()) }
            assertEquals(2, calls)
            status = 200; body = "x".repeat(16385)
            assertFails { broker.tokenRequest(oauth.tokenEndpoint, emptyMap()) }
            body = "{broken"
            assertFails { broker.tokenRequest(oauth.tokenEndpoint, emptyMap()) }
            body = "{}"; revoke = true
            assertFails { broker.tokenRequest(oauth.tokenEndpoint, emptyMap()) }
        }
    }
}
