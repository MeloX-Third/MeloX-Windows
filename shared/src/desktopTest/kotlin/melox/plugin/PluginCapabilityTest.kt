package melox.plugin

import melox.music.model.*
import melox.music.provider.*
import kotlin.test.*

class PluginCapabilityTest {
    @Test fun declaredAccountAndDownloadCapabilitiesRequireTheirInterfaces() {
        for (capability in listOf(MusicCapability.Authentication, MusicCapability.Download)) {
            val registry = DynamicMusicProviders()
            val provider = object : MusicProvider {
                override val sourceId = SourceId.parse("plugin:example.missing")!!
                override val displayName = "Missing interface"
                override val capabilities = setOf(capability)
            }
            assertFailsWith<IllegalArgumentException> { registry.register(provider) }
            assertNull(registry[provider.sourceId])
            assertEquals(0L, registry.generation)
        }
    }

    @Test fun progressiveMediaRejectsAdaptiveAndDrmDeclarations() {
        PluginMediaFormats.check("https://example.com/audio.mp3")
        for (url in listOf("https://example.com/STREAM.M3U8?token=x", "https://example.com/audio.mpd")) {
            assertFails { PluginMediaFormats.check(url) }
        }
        for (format in listOf("HLS", "dash", "drm", "application/dash+xml; charset=utf-8")) {
            assertFails { PluginMediaFormats.check("https://example.com/audio", format) }
        }
        assertFails { PluginMediaFormats.check("https://example.com/audio.mp3", drm = true) }
        assertTrue(PluginMediaFormats.unsupportedMime("Audio/X-MpegURL; charset=utf-8"))
        assertFalse(PluginMediaFormats.unsupportedMime("audio/mpeg"))
    }
}
