package melox.plugin

import melox.music.model.SourceId
import kotlin.test.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

class PluginHttpBrokerTest {
    private val manifest = PluginManifest(SourceId.parse("plugin:example.http")!!, "HTTP", "1.0.0", 1,
        "index.js", emptySet(), setOf("api.example.com"))

    @Test fun rejectsCoercedModelsAndUnauthorizedOriginsBeforeNetwork() {
        PluginHttpBroker(manifest).use { broker ->
            for (raw in listOf("""{"url":123}""", """{"url":"https://api.example.com","method":123}""",
                """{"url":"https://api.example.com","headers":[]}""", """{"url":"https://api.example.com","body":{}}""",
                """{"url":"http://127.0.0.1"}""", """{"url":"https://foreign.example.com"}""",
                """{"url":"https://api.example.com:444"}""", """{"url":"https://user:password@api.example.com"}""",
                """{"url":"https://api.example.com","headers":{"Host":"private"}}""",
                """{"url":"https://api.example.com","credential":"other"}""")) {
                assertFailsWith<IllegalArgumentException>(raw) { broker.request(raw) }
            }
            broker.authorizeMediaUrl("https://api.example.com/audio.mp3")
            assertFails { broker.imageBytes("https://foreign.example.com/image.png") }
        }
    }

    @Test fun closedOrRevokedInvocationCannotStartRequests() {
        val closed = PluginHttpBroker(manifest)
        closed.close()
        assertFailsWith<IllegalStateException> { closed.request("""{"url":"https://api.example.com"}""") }
        assertFailsWith<IllegalStateException> { closed.imageBytes("https://api.example.com/image.png") }
        PluginHttpBroker(manifest, accountCurrent = { false }).use { revoked ->
            assertFailsWith<IllegalStateException> { revoked.request("""{"url":"https://api.example.com"}""") }
            assertFailsWith<IllegalStateException> { revoked.imageBytes("https://api.example.com/image.png") }
        }
    }

    private fun response(request: Request, code: Int = 200, body: String = "ok", vararg headers: Pair<String, String>) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody()).apply { headers.forEach { (name, value) -> header(name, value) } }.build()

    @Test fun redirectsAuthorizeEachOriginDropHeadersAndConvertPostToGet() {
        val requests = mutableListOf<Request>()
        val transport = Interceptor { chain ->
            val request = chain.request().also(requests::add)
            if (requests.size == 1) response(request, 302, "", "Location" to "https://cdn.example.com/next")
            else response(request, 200, "result", "Set-Cookie" to "session=private", "X-Result" to "value")
        }
        PluginHttpBroker(manifest.copy(networkDomains = manifest.networkDomains + "cdn.example.com"), internalTransport = transport).use {
            val result = JSONObject(it.request("""{"url":"https://api.example.com/start","method":"POST","body":"payload","headers":{"Authorization":"script-value","X-Custom":"test"}}"""))
            assertEquals("POST", requests[0].method)
            assertEquals("GET", requests[1].method)
            assertNull(requests[1].header("Authorization"))
            assertNull(requests[1].header("X-Custom"))
            assertEquals("result", result.getString("body"))
            assertEquals(200, result.getInt("status"))
            assertFalse(result.getJSONObject("headers").has("Set-Cookie"))
        }
        var calls = 0
        PluginHttpBroker(manifest, internalTransport = Interceptor { chain ->
            calls++; response(chain.request(), 307, "", "Location" to "https://foreign.example.com/")
        }).use {
            assertFails { it.request("""{"url":"https://api.example.com"}""") }
            assertEquals(1, calls)
        }
    }

    @Test fun quotasOversizedBodiesAndRevocationDiscardResponses(): Unit {
        var calls = 0
        PluginHttpBroker(manifest, internalTransport = Interceptor { chain -> calls++; response(chain.request()) }).use { broker ->
            repeat(8) { broker.request("""{"url":"https://api.example.com"}""") }
            assertFails { broker.request("""{"url":"https://api.example.com"}""") }
            assertEquals(8, calls)
        }
        PluginHttpBroker(manifest, internalTransport = Interceptor { response(it.request(), body = "x".repeat(65537)) }).use { broker ->
            assertFails { broker.request("""{"url":"https://api.example.com"}""") }
        }
        var current = true
        PluginHttpBroker(manifest, accountCurrent = { current }, internalTransport = Interceptor {
            current = false; response(it.request())
        }).use { broker -> assertFailsWith<IllegalStateException> { broker.request("""{"url":"https://api.example.com"}""") } }
    }

    @Test fun artworkRejectsRedirectsFormatsAndOversizedResponses(): Unit {
        for ((status, mime, body) in listOf(Triple(302, "image/png", ""), Triple(200, "text/html", "html"),
            Triple(200, "image/png", "x".repeat(2 * 1024 * 1024 + 1)))) {
            PluginHttpBroker(manifest, internalTransport = Interceptor {
                response(it.request(), status, body, "Content-Type" to mime, "Location" to "https://api.example.com/next")
            }).use { broker -> assertFails { broker.imageBytes("https://api.example.com/image") } }
        }
        PluginHttpBroker(manifest, internalTransport = Interceptor {
            response(it.request(), 200, "encoded-image", "Content-Type" to "image/png; charset=binary")
        }).use { broker -> assertEquals("encoded-image", broker.imageBytes("https://api.example.com/image").toString(Charsets.UTF_8)) }
    }

    @Test fun credentialInjectionRequiresMatchingSourcePolicySessionAndDestination(): Unit {
        val auth = PluginAuthentication("apiToken", domains = setOf("api.example.com"))
        val secured = manifest.copy(authentication = auth, networkDomains = manifest.networkDomains + "public.example.com")
        val root = java.nio.file.Files.createTempDirectory("broker-vault").toFile()
        try {
            val protection = object : melox.platform.SecretProtection {
                override fun protect(plain: ByteArray) = plain.reversedArray()
                override fun unprotect(encrypted: ByteArray) = encrypted.reversedArray()
            }
            val vault = PluginAccountVault(manifest.sourceId, root, protection)
            vault.importTokenAndActivate("Fixture", "host-only-token", auth)
            val lease = assertNotNull(vault.lease())
            var calls = 0
            val transport = Interceptor {
                calls++
                assertEquals("Bearer host-only-token", it.request().header("Authorization"))
                response(it.request())
            }
            PluginHttpBroker(secured, internalTransport = transport, credential = lease).use { broker ->
                broker.request("""{"url":"https://api.example.com","credential":"active","headers":{"Authorization":"script-value"}}""")
                assertFails { broker.request("""{"url":"https://public.example.com","credential":"active"}""") }
                assertEquals(1, calls)
                vault.activate(null)
                assertFails { broker.request("""{"url":"https://api.example.com","credential":"active"}""") }
                assertEquals(1, calls)
            }
            vault.activate(vault.accounts().single().id)
            val selected = assertNotNull(vault.lease())
            assertFails { PluginHttpBroker(secured.copy(sourceId = SourceId.parse("plugin:example.foreign")!!), credential = selected) }
            PluginHttpBroker(secured.copy(authentication = auth.copy(prefix = "Changed ")), internalTransport = transport, credential = selected).use {
                assertFails { it.request("""{"url":"https://api.example.com","credential":"active"}""") }
            }
            PluginHttpBroker(secured, internalTransport = transport).use {
                assertFailsWith<IllegalStateException> { it.request("""{"url":"https://api.example.com","credential":"active"}""") }
            }
            assertEquals(1, calls)
        } finally { root.deleteRecursively() }
    }
}
