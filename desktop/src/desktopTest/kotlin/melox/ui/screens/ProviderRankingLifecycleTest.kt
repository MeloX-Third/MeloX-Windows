package melox.ui.screens

import kotlinx.coroutines.runBlocking
import melox.music.model.*
import melox.music.provider.*
import kotlin.test.*

class ProviderRankingLifecycleTest {
    @Test fun pluginPagesKeepIdentityAndRejectSourceRevocation(): Unit = runBlocking {
        val registry = MeloXMusicProviders.create()
        val source = SourceId.parse("plugin:test.ranking.lifecycle")!!
        val ranking = MusicRankingSummary(MusicResourceId(source, "chart"), "Chart")
        val track = MusicTrack(MusicResourceId(source, "song%/identity"), "Song", emptyList())
        var revoke = false
        var foreign = false
        var pages = 0
        val provider = object : MusicProvider, RankingCapability {
            override val sourceId = source
            override val displayName = "Test ranking"
            override val capabilities = setOf(MusicCapability.Rankings)
            override suspend fun rankingTracks(ranking: MusicRankingSummary, page: Int, pageSize: Int): MusicPage<MusicTrack> {
                assertEquals(100, pageSize)
                pages++
                if (revoke) registry.setDynamicEnabled(source, false)
                val item = if (foreign) track.copy(id = MusicResourceId(MusicSource.Netease, "foreign")) else track
                return MusicPage(listOf(item), page, pageSize, hasMore = page == 1)
            }
        }
        registry.registerDynamic(provider)
        try {
            assertEquals(listOf(track), loadProviderRankingSongs(source, ranking).map { it.providerTrack })
            assertEquals(2, pages)
            foreign = true
            assertFailsWith<IllegalArgumentException> { loadProviderRankingSongs(source, ranking) }
            foreign = false
            revoke = true
            assertFailsWith<IllegalStateException> { loadProviderRankingSongs(source, ranking) }
        } finally {
            registry.unregisterDynamic(source)
        }
    }
}
