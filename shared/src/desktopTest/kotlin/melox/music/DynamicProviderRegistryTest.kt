package melox.music

import melox.music.model.*
import melox.music.provider.*
import kotlin.test.*

class DynamicProviderRegistryTest {
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
