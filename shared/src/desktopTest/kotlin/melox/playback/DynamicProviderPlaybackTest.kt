package melox.playback

import java.io.IOException
import melox.music.model.*
import melox.music.provider.*
import kotlin.test.*

class DynamicProviderPlaybackTest {
    private val source = SourceId.parse("plugin:example.catalog")!!
    private val track = MusicTrack(MusicResourceId(source, "目录/a%2F+b?中文"), "Title", listOf(MusicArtistRef(name = "Artist")),
        bestQuality = AudioQualityTier.Lossless)
    private fun resolver(registry: MusicProviderRegistry, local: (MusicResourceId) -> String? = { null }) =
        ProviderPlaybackResolver(NeteasePlaybackResolver(cookieProvider = { "" }, localSourceProvider = { null }), registry,
            localSourceProvider = local)

    @Test fun fullTrackRoundTripKeepsLiteralResourceAndHeadersWithoutStaleCache() {
        val registry = MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()))
        var calls = 0
        var disable = false
        val manifest = melox.plugin.PluginManifest(source, "Example", "1.0.0", 1, "index.js",
            setOf(melox.plugin.PluginCapability.Playback), setOf("example.test"))
        val provider = melox.plugin.PluginMusicProvider(manifest) { method, args ->
                assertEquals("resolvePlayback", method)
                assertEquals(track.id.value, args.getJSONObject(0).getString("id"))
                calls++
                if (disable) registry.setDynamicEnabled(source, false)
                """{"type":"playable","url":"https://example.test/audio/$calls","headers":{"X-Media":"value"}}"""
        }
        registry.registerDynamic(provider)
        val resolver = resolver(registry)
        val uri = ProviderPlaybackResolver.uriForTrack(track, AudioQualityTier.Standard)
        val first = resolver.resolveProviderRequest(uri)
        assertTrue(first.headers.isEmpty())
        assertTrue(melox.plugin.PluginPlaybackInputs.isPrivate(first.uri))
        assertNotEquals(first.uri, resolver.resolveProviderRequest(uri).uri)
        disable = true
        assertFailsWith<IOException> { resolver.resolveProviderRequest(uri) }
        assertFailsWith<IOException> { resolver.resolveProviderRequest(uri) }
        assertEquals(3, calls)
    }

    @Test fun downloadedDynamicTrackWorksWithoutInstalledProvider() {
        val registry = MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()))
        val uri = ProviderPlaybackResolver.uriForTrack(track, AudioQualityTier.Standard)
        assertEquals("file:///download.flac", resolver(registry) { id ->
            assertEquals(track.id, id)
            "file:///download.flac"
        }.resolveProviderRequest(uri).uri)
        assertFailsWith<IOException> { resolver(registry).resolveProviderRequest(uri) }
    }

    @Test fun forgedDynamicPlaybackProviderIsRejectedBeforeInvocation() {
        var invoked = false
        val registry = MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()))
        registry.registerDynamic(object : MusicProvider, PlaybackCapability {
            override val sourceId = this@DynamicProviderPlaybackTest.source
            override val displayName = "Forged"
            override val capabilities = setOf(MusicCapability.Playback)
            override suspend fun resolvePlayback(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution {
                invoked = true
                error("Must not invoke")
            }
        })
        assertFailsWith<IOException> {
            resolver(registry).resolveProviderRequest(ProviderPlaybackResolver.uriForTrack(track, AudioQualityTier.Standard))
        }
        assertFalse(invoked)
    }
}
