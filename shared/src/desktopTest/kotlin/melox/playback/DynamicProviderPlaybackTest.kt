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
        val provider = object : MusicProvider, PlaybackCapability {
            override val sourceId = this@DynamicProviderPlaybackTest.source
            override val displayName = "Example"
            override val capabilities = setOf(MusicCapability.Playback)
            override suspend fun resolvePlayback(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution {
                assertEquals(this@DynamicProviderPlaybackTest.track, track)
                calls++
                if (disable) registry.setDynamicEnabled(sourceId, false)
                return PlaybackResolution.Playable("https://example.test/audio/$calls", requestedQuality = quality, requestHeaders = mapOf("X-Media" to "value"))
            }
        }
        registry.registerDynamic(provider)
        val resolver = resolver(registry)
        val uri = ProviderPlaybackResolver.uriForTrack(track, AudioQualityTier.Standard)
        assertEquals("value", resolver.resolveProviderRequest(uri).headers["X-Media"])
        assertTrue(resolver.resolveProviderRequest(uri).uri.endsWith("/2"))
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
}
