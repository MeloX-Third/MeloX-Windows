package melox.plugin

import melox.music.model.*
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class PluginMediaAccessTest {
    private val source = requireNotNull(SourceId.parse("plugin:test.media.access"))
    private val policy = PluginAuthentication("apiToken", domains = setOf("api.example.com"))
    private val manifest = PluginManifest(source, "Media", "1.0.0", 1, "index.js",
        setOf(PluginCapability.Playback), setOf("api.example.com", "cdn.example.com"), authentication = policy)

    @Test fun activeMediaCredentialStaysInHostAndRevocationClosesReads() {
        var current = true
        var requests = 0
        val lease = object : PluginCredentialLease {
            override val source = this@PluginMediaAccessTest.source
            override val policy = this@PluginMediaAccessTest.policy
            override val token = "private-token"
            override val expiresAt: Long? = null
            override fun isCurrent() = current
        }
        val access = PluginMediaAccess({ current }) { active ->
            PluginHttpBroker(manifest, accountCurrent = active, credential = lease, internalTransport = okhttp3.Interceptor { chain ->
                requests++
                assertEquals("Bearer private-token", chain.request().header("Authorization"))
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body("audio".toResponseBody()).build()
            })
        }
        access.broker { true }.use { broker ->
            assertFails { broker.openMedia("https://cdn.example.com/audio", activeCredential = true) }
            assertEquals(0, requests)
            assertFails { broker.openMedia("https://api.example.com/audio", mapOf("authorization" to "script"), true) }
            assertEquals(0, requests)
            broker.openMedia("https://api.example.com/audio", activeCredential = true).use { stream ->
                assertEquals('a'.code, stream.read())
                current = false
                assertFails { stream.read() }
            }
        }
        assertEquals(1, requests)
        assertFails { access.broker { true } }
    }

    @Test fun providerBindsMediaReferenceAndRejectsAccountSwitchDuringResolution() = kotlinx.coroutines.runBlocking {
        var current = true
        var switch = false
        val provider = PluginMusicProvider(manifest) { _, _ ->
            if (switch) current = false
            """{"type":"playable","url":"https://api.example.com/audio","credential":"active"}"""
        }
        val access = PluginMediaAccess({ current }) { error("must not create broker during resolution") }
        provider.mediaAccess = { access }
        val track = MusicTrack(MusicResourceId(source, "track"), "Title", emptyList())
        val playable = provider.resolvePlayback(track, AudioQualityTier.Standard) as PlaybackResolution.Playable
        assertSame(access, playable.pluginMediaAccess)
        assertTrue(playable.requestHeaders.isEmpty())
        assertFalse(playable.toString().contains("private-token"))
        switch = true
        assertFails { provider.resolvePlayback(track, AudioQualityTier.Standard) }
        Unit
    }
}
