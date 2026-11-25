package melox.music.provider

import melox.music.model.MusicSource
import melox.music.model.SourceId
import melox.music.model.MusicTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext

/**
 * Explicit opt-in cross-provider search.
 *
 * This class deliberately does not decide which providers participate. The caller
 * must pass the already-approved source whitelist from [MusicProviderSelectionStore].
 * It also does not perform automatic playback fallback or try to bypass provider
 * availability/rights: results keep their original provider identity.
 */
class UnifiedMusicService(
    private val registry: MusicProviderRegistry,
    private val providerTimeoutMs: Long = 12_000L,
) {
    data class SearchFailure(
        val sourceId: SourceId,
        val message: String,
    ) {
        constructor(source: MusicSource, message: String) : this(SourceId.fromBuiltIn(source), message)
        val source: MusicSource get() = sourceId.builtIn ?: error("Dynamic search failure: ${sourceId.value}")
    }

    data class SearchResult(
        val tracks: List<MusicTrack>,
        val failures: List<SearchFailure> = emptyList(),
        val aggregated: List<AggregatedTrack> = emptyList(),
    )

    suspend fun searchSongs(
        query: String,
        sources: Set<MusicSource>,
        page: Int = 1,
        pageSizePerProvider: Int = 20,
    ): SearchResult = searchSourceIds(query, sources.mapTo(linkedSetOf(), SourceId::fromBuiltIn), page, pageSizePerProvider)

    suspend fun searchSourceIds(
        query: String,
        sources: Set<SourceId>,
        page: Int = 1,
        pageSizePerProvider: Int = 20,
    ): SearchResult = coroutineScope {
        val normalized = query.trim()
        if (normalized.isEmpty() || sources.isEmpty()) return@coroutineScope SearchResult(emptyList())

        require(page > 0 && pageSizePerProvider in 1..100 && sources.size <= 64)
        val generation = registry.generation
        val orderedSources = sources.sortedBy { it.value }
        val requests = orderedSources.map { source ->
            async {
                val provider = registry[source]
                val search = (provider as? SearchCapability)?.takeIf { provider.supports(MusicCapability.Search) }
                if (search == null) {
                    ProviderResult(
                        source = source,
                        tracks = emptyList(),
                        failure = SearchFailure(source, "${source.displayName} 当前没有搜索能力"),
                    )
                } else {
                    runCatching {
                        withTimeout(providerTimeoutMs) { search.searchSongs(
                            query = normalized,
                            page = page.coerceAtLeast(1),
                            pageSize = pageSizePerProvider.coerceIn(1, 50),
                        ).items }
                    }.fold(
                        onSuccess = { tracks ->
                            if (registry.generation != generation || registry[source] !== provider) {
                                return@fold ProviderResult(source, emptyList(), SearchFailure(source, "音源状态已变化"))
                            }
                            val valid = tracks.filter { it.id.sourceId == source }.distinctBy { it.id }
                            ProviderResult(source, valid, if (valid.size < tracks.distinctBy { it.id }.size)
                                SearchFailure(source, "来源返回的歌曲身份不匹配") else null)
                        },
                        onFailure = { error ->
                            if (error is CancellationException && error !is TimeoutCancellationException) throw error
                            currentCoroutineContext().ensureActive()
                            ProviderResult(
                                source = source,
                                tracks = emptyList(),
                                failure = SearchFailure(
                                    source,
                                    if (error is TimeoutCancellationException) "${source.displayName} 搜索超时" else error.message ?: "${source.displayName} 搜索失败",
                                ),
                            )
                        },
                    )
                }
            }
        }.awaitAll()

        // Keep provider results distinct. Same-title tracks are not silently
        // merged here because their rights/quality/account availability differ.
        val tracks = requests.flatMap(ProviderResult::tracks)
        SearchResult(
            tracks = tracks,
            failures = requests.mapNotNull(ProviderResult::failure),
            aggregated = TrackAggregation.aggregate(tracks),
        )
    }

    data class AggregationResult(
        val tracks: List<MusicTrack>,
        val failures: List<SearchFailure> = emptyList(),
    )

    suspend fun collectAggregationTracks(
        sources: Set<MusicSource>,
        pageSizePerProvider: Int = 100,
    ): AggregationResult = coroutineScope {
        val results = MusicSource.entries.filter(sources::contains).map { source ->
            async {
                val capability = registry[source] as? LocalAggregationCapability
                if (capability == null) {
                    ProviderResult(source, emptyList(), null)
                } else {
                    runCatching { capability.aggregationTracks(page = 1, pageSize = pageSizePerProvider.coerceIn(1, 200)).items }
                        .fold({ ProviderResult(source, it, null) }, {
                            if (it is CancellationException) throw it
                            ProviderResult(source, emptyList(), SearchFailure(source, it.message ?: "${source.displayName} 聚合数据读取失败"))
                        })
                }
            }
        }.awaitAll()
        AggregationResult(results.flatMap(ProviderResult::tracks), results.mapNotNull(ProviderResult::failure))
    }

    suspend fun collectRecommendationCandidates(
        sources: Set<MusicSource>,
        limitPerProvider: Int = 30,
    ): List<MusicTrack> = collectRecommendationCandidatesSourceIds(sources.mapTo(linkedSetOf(), SourceId::fromBuiltIn), limitPerProvider)

    suspend fun collectRecommendationCandidatesSourceIds(
        sources: Set<SourceId>,
        limitPerProvider: Int = 30,
    ): List<MusicTrack> = coroutineScope {
        require(sources.size <= 64)
        val generation = registry.generation
        sources.sortedBy { it.value }.map { source ->
            async {
                val provider = registry[source] ?: return@async emptyList()
                val feed = (provider as? HomeFeedCapability)?.takeIf { provider.supports(melox.music.provider.MusicCapability.HomeRecommendations) }
                    ?: return@async emptyList()
                runCatching {
                    val home = feed.homeFeed(
                        playlistLimit = 6,
                        newSongLimit = limitPerProvider.coerceIn(1, 50),
                        rankingLimit = 4,
                    )
                    check(registry.generation == generation && registry[source] === provider) { "source changed" }
                    (home.newSongs + home.rankings.flatMap { it.previewTracks }).also { tracks ->
                        require(tracks.all { it.id.sourceId == source }) { "recommendation source identity mismatch" }
                    }
                }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    private data class ProviderResult(
        val source: SourceId,
        val tracks: List<MusicTrack>,
        val failure: SearchFailure?,
    ) {
        constructor(source: MusicSource, tracks: List<MusicTrack>, failure: SearchFailure?) :
            this(SourceId.fromBuiltIn(source), tracks, failure)
    }
}
