package melox.ui.screens

import kotlinx.coroutines.runBlocking
import melox.music.model.*
import melox.music.provider.*
import kotlin.test.*

class ProviderCollectionPagingTest {
    @Test fun completePagesDeduplicateAndRejectRevocation(): Unit = runBlocking {
        val registry = MeloXMusicProviders.create()
        val source = SourceId.parse("plugin:test.collection.paging")!!
        val id = MusicResourceId(source, "album")
        val first = MusicTrack(MusicResourceId(source, "first"), "First", emptyList())
        val second = first.copy(id = MusicResourceId(source, "second"))
        val provider = object : MusicProvider {
            override val sourceId = source
            override val displayName = "Paging fixture"
            override val capabilities = setOf(MusicCapability.Albums)
        }
        registry.registerDynamic(provider)
        try {
            var calls = 0
            val tracks = loadProviderCollectionTracks(id, MusicCapability.Albums) { actual, page ->
                assertSame(provider, actual)
                calls++
                when (page) {
                    1 -> CollectionTrackPage(listOf(first), true, null)
                    2 -> CollectionTrackPage(listOf(first, second), true, null)
                    else -> CollectionTrackPage(listOf(second), true, null)
                }
            }
            assertEquals(listOf(first, second), tracks)
            assertEquals(3, calls)
            assertFailsWith<IllegalArgumentException> {
                loadProviderCollectionTracks(id, MusicCapability.Albums) { _, _ ->
                    CollectionTrackPage(listOf(first.copy(id = MusicResourceId(MusicSource.Netease, "bad"))), false, null)
                }
            }
            assertFailsWith<IllegalStateException> {
                loadProviderCollectionTracks(id, MusicCapability.Albums) { _, _ ->
                    registry.setDynamicEnabled(source, false)
                    CollectionTrackPage(listOf(first), false, null)
                }
            }
        } finally { registry.unregisterDynamic(source) }
    }
}
