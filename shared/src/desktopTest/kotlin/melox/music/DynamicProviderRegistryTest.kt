package melox.music

import melox.music.model.*
import melox.music.provider.*
import kotlin.test.*
import kotlinx.coroutines.*

class DynamicProviderRegistryTest {
    @Test fun recommendationsRequireDeclaredCapabilityAndMatchingLiveSource() = runBlocking {
        val registry = MusicProviderRegistry(listOf(builtIn))
        val id = SourceId.parse("plugin:example.catalog")!!
        val own = MusicTrack(MusicResourceId(id, "123"), "Track", listOf(MusicArtistRef(name = "Artist")))
        var foreign = false
        var disable = false
        val provider = object : MusicProvider, HomeFeedCapability {
            override val sourceId = id
            override val displayName = "Example"
            override val capabilities = setOf(MusicCapability.HomeRecommendations)
            override suspend fun homeFeed(playlistLimit: Int, newSongLimit: Int, rankingLimit: Int): MusicHomeFeed {
                if (disable) registry.setDynamicEnabled(id, false)
                return MusicHomeFeed(newSongs = listOf(if (foreign) own.copy(id = MusicResourceId(MusicSource.Netease, "123")) else own, own))
            }
        }
        registry.registerDynamic(provider)
        val service = UnifiedMusicService(registry)
        assertEquals(listOf(own), service.collectRecommendationCandidatesSourceIds(setOf(id)))
        foreign = true
        assertTrue(service.collectRecommendationCandidatesSourceIds(setOf(id)).isEmpty())
        foreign = false
        disable = true
        assertTrue(service.collectRecommendationCandidatesSourceIds(setOf(id)).isEmpty())
    }

    @Test fun searchRejectsForeignIdentitiesAndResultsFromDisabledProvider() = runBlocking {
        val registry = MusicProviderRegistry(listOf(builtIn))
        val id = SourceId.parse("plugin:example.catalog")!!
        var disableDuringSearch = false
        val own = MusicTrack(MusicResourceId(id, "123"), "Track", listOf(MusicArtistRef(name = "Artist")))
        val provider = object : MusicProvider, SearchCapability {
            override val sourceId = id
            override val displayName = "Example"
            override val capabilities = setOf(MusicCapability.Search)
            override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> {
                if (disableDuringSearch) registry.setDynamicEnabled(id, false)
                return MusicPage(listOf(own, own, own.copy(id = MusicResourceId(MusicSource.Netease, "123"))), page, pageSize)
            }
        }
        registry.registerDynamic(provider)
        val service = UnifiedMusicService(registry)
        val result = service.searchSourceIds("query", setOf(id))
        assertEquals(listOf(own), result.tracks)
        assertEquals(id, result.failures.single().sourceId)
        disableDuringSearch = true
        assertTrue(service.searchSourceIds("query", setOf(id)).tracks.isEmpty())
        assertTrue(service.searchSourceIds("query", setOf(id)).tracks.isEmpty())
    }

    private fun plugin(capabilities: Set<MusicCapability> = emptySet()) = object : MusicProvider {
        override val sourceId = SourceId.parse("plugin:example.catalog")!!
        override val displayName = "Example"
        override val capabilities = capabilities
    }
    private val builtIn = object : MusicProvider {
        override val source = MusicSource.Netease
        override val displayName = "NetEase"
        override val capabilities = emptySet<MusicCapability>()
    }

    @Test fun sharedRegistriesObserveLifecycleAndPreserveBuiltInLists() {
        val dynamic = DynamicMusicProviders()
        val first = MusicProviderRegistry(listOf(builtIn), dynamic)
        val second = MusicProviderRegistry(listOf(builtIn), dynamic)
        val provider = plugin()
        first.registerDynamic(provider)
        assertSame(provider, second[provider.sourceId])
        assertEquals(listOf(builtIn), first.providers)
        assertSame(builtIn, first[SourceId.fromBuiltIn(MusicSource.Netease)])
        assertEquals(1L, second.changes.value)
        assertTrue(second.setDynamicEnabled(provider.sourceId, false))
        assertNull(first[provider.sourceId])
        val replacement = plugin()
        first.replaceDynamic(replacement)
        assertNull(second[provider.sourceId])
        assertTrue(first.setDynamicEnabled(provider.sourceId, true))
        assertSame(replacement, second[provider.sourceId])
        val generation = first.generation
        assertTrue(first.setDynamicEnabled(provider.sourceId, true))
        assertEquals(generation, first.generation)
        assertTrue(second.unregisterDynamic(provider.sourceId))
        assertNull(first[provider.sourceId])
        assertFalse(first.unregisterDynamic(provider.sourceId))
        assertNull(first[SourceId.parse("future_provider")!!])
    }

    @Test fun invalidCapabilitiesAndDuplicateSourcesCannotMutateRegistry() {
        val registry = MusicProviderRegistry(listOf(builtIn))
        listOf(MusicCapability.Search, MusicCapability.Playback, MusicCapability.Lyrics, MusicCapability.Resolver).forEach {
            assertFailsWith<IllegalArgumentException> { registry.registerDynamic(plugin(setOf(it))) }
        }
        assertFailsWith<IllegalArgumentException> { registry.registerDynamic(builtIn) }
        assertEquals(0L, registry.generation)
        val provider = plugin()
        registry.registerDynamic(provider)
        assertFailsWith<IllegalArgumentException> { registry.registerDynamic(plugin()) }
        assertEquals(1L, registry.generation)
        assertSame(provider, registry[provider.sourceId])
    }
}
