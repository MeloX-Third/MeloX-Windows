package melox.lyrics

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import melox.music.model.*
import melox.music.provider.*
import melox.settings.MeloXSettingsRuntime
import kotlin.test.*

class DynamicProviderLyricsTest {
    private val source = SourceId.parse("plugin:example.lyrics")!!
    private val track = MusicTrack(MusicResourceId(source, "lyric-123"), "Title", emptyList())

    @Test fun dynamicLyricsUseOnlyCurrentProviderAndInvalidateCacheAfterDisable() = runBlocking {
        val registry = MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()))
        var calls = 0
        registry.registerDynamic(object : MusicProvider, LyricsCapability {
            override val sourceId = this@DynamicProviderLyricsTest.source
            override val displayName = "Lyrics"
            override val capabilities = setOf(MusicCapability.Lyrics)
            override suspend fun lyrics(track: MusicTrack): LyricsDocument {
                calls++
                return LyricsDocument(listOf(LyricLine(1000, text = "hello")))
            }
        })
        val old = MeloXSettingsRuntime.automaticLyricSelectionEnabled
        MeloXSettingsRuntime.automaticLyricSelectionEnabled = true
        try {
            val loader = MeloXLyricsLoad(object : LyricDocumentSources() {
                override suspend fun amll(track: MusicTrack, registry: MusicProviderRegistry?, preference: MeloXLyricPreference): LyricsDocument =
                    error("Dynamic provider must not start cross-source lyrics")
            })
            assertEquals("hello", loader.observe(track, registry).toList().single().lines.single().text)
            assertEquals(1, calls)
            registry.setDynamicEnabled(source, false)
            assertTrue(loader.load(track, registry).lines.isEmpty())
        } finally { MeloXSettingsRuntime.automaticLyricSelectionEnabled = old }
    }

    @Test fun bindingRetainsDynamicSourceAndSeparatesSameResourceAcrossSources() {
        val binding = LyricBinding(BoundLyricSource.Provider, resourceValue = "123", title = "Title", artist = "Artist",
            durationMs = 1234, providerSourceId = source)
        LyricBindingStore.write(track.id, binding)
        assertEquals(binding, LyricBindingStore.read(track.id))
        assertNotEquals(binding.stableKey(), binding.copy(providerSourceId = SourceId.fromBuiltIn(MusicSource.Netease)).stableKey())
        assertNull(LyricBindingStore.read(MusicResourceId(SourceId.parse("plugin:other.lyrics")!!, track.id.value)))
    }
}
