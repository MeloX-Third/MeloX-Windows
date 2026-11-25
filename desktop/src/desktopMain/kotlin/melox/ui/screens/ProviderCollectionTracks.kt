package melox.ui.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import melox.music.model.*
import melox.music.provider.*

internal suspend fun loadProviderAlbumTracks(album: MusicAlbumSummary): List<MusicTrack> =
    loadProviderCollectionTracks(album.id, MusicCapability.Albums) { provider, page ->
        val detail = (provider as? AlbumCapability)?.albumDetail(album, page, 100)
            ?: error("音源不提供专辑详情")
        require(detail.summary.id == album.id) { "专辑身份不匹配" }
        CollectionTrackPage(detail.tracks, detail.hasMore, detail.totalTracks)
    }

internal suspend fun loadProviderArtistTracks(artist: MusicArtistSummary): List<MusicTrack> =
    loadProviderCollectionTracks(artist.id, MusicCapability.Artists) { provider, page ->
        val detail = (provider as? ArtistCapability)?.artistDetail(artist, page, 100)
            ?: error("音源不提供歌手详情")
        require(detail.summary.id == artist.id) { "歌手身份不匹配" }
        CollectionTrackPage(detail.tracks, detail.hasMore, detail.totalTracks)
    }

internal data class CollectionTrackPage(val tracks: List<MusicTrack>, val more: Boolean?, val total: Long?)

internal suspend fun loadProviderCollectionTracks(id: MusicResourceId, capability: MusicCapability,
    load: suspend (MusicProvider, Int) -> CollectionTrackPage): List<MusicTrack> = withContext(Dispatchers.IO) {
    val registry = MeloXMusicProviders.create()
    val generation = registry.generation
    val provider = registry[id.sourceId]?.takeIf { it.supports(capability) }
        ?: error("音源未启用或不提供此详情")
    val tracks = linkedMapOf<MusicResourceId, MusicTrack>()
    var page = 1
    while (true) {
        val result = load(provider, page)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(registry.generation == generation && registry[id.sourceId] === provider) { "音源已变化" }
        val before = tracks.size
        result.tracks.forEach { require(it.id.sourceId == id.sourceId) { "歌曲来源不匹配" }; tracks[it.id] = it }
        val more = result.more ?: result.total?.let { page.toLong() * 100 < it } ?: false
        if (!more || result.tracks.isEmpty() || tracks.size == before) break
        page++
    }
    tracks.values.toList()
}
