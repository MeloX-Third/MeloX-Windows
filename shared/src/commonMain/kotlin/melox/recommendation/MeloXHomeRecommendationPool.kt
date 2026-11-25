package melox.recommendation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import melox.model.SearchSong
import melox.music.model.*

/** Process-scoped Android edc829e pool; playback contributes the entire submitted queue. */
object MeloXHomeRecommendationPool {
    private val state = HomeRecommendationPool()
    var tracks by mutableStateOf<List<MusicTrack>>(emptyList())
        private set

    @Synchronized fun ensureSource(source: String, seeds: List<MusicTrack>) {
        tracks = state.ensureSource(source, seeds)
    }
    @Synchronized fun appendTracks(tracks: List<MusicTrack>) {
        this.tracks = state.appendTracks(tracks)
    }
    fun ensureSongs(source: String, songs: List<SearchSong>) = ensureSource(source, songs.mapNotNull(::recommendationTrack))
    fun appendSongs(songs: List<SearchSong>) = appendTracks(songs.mapNotNull(::recommendationTrack))
    private fun recommendationTrack(song: SearchSong): MusicTrack? =
        song.providerTrack ?: song.id.takeIf { it > 0 }?.let { id ->
            MusicTrack(MusicResourceId(MusicSource.Netease, id.toString()), song.name,
                listOf(MusicArtistRef(name = song.artists)), artworkUrl = song.artworkUrl,
                durationMs = song.durationMs.takeIf { it > 0 })
        }
}

internal class HomeRecommendationPool(private val shuffle: (List<MusicTrack>) -> List<MusicTrack> = { it.shuffled() }) {
    private var source: String? = null
    private var pool = emptyList<MusicTrack>()
    private var displayed = emptyList<MusicTrack>()

    fun ensureSource(source: String, seeds: List<MusicTrack>): List<MusicTrack> {
        pool = (pool + seeds).distinctBy { it.id }
        if (this.source == source && displayed.isNotEmpty()) return displayed
        this.source = source
        if (pool.isNotEmpty()) displayed = shuffle(pool).take(5)
        return displayed
    }

    fun appendTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        val known = pool.mapTo(hashSetOf()) { it.id }
        val additions = tracks.distinctBy { it.id }.filter { it.id !in known }
        if (additions.isEmpty()) return displayed
        pool = pool + additions
        displayed = (shuffle(additions).take(5) + displayed).distinctBy { it.id }.take(5)
        return displayed
    }
}
