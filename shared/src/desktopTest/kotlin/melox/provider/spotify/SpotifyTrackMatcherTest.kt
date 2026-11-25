package melox.provider.spotify

import melox.music.model.*
import kotlin.test.*

class SpotifyTrackMatcherTest {
    private val source = MusicTrack(MusicResourceId(MusicSource.Spotify, "source"), "Song",
        listOf(MusicArtistRef(name = "Artist")), availability = TrackAvailability.Playable)

    @Test fun missingDurationsAcceptCompleteArtistMatchWithDynamicIdentity() {
        val plugin = requireNotNull(SourceId.parse("plugin:example.match"))
        val candidate = source.copy(id = MusicResourceId(plugin, "candidate"))
        assertEquals(listOf(candidate), SpotifyTrackMatcher.rank(source, listOf(source, candidate)).map { it.candidate })
        assertTrue(SpotifyTrackMatcher.rank(source, listOf(candidate.copy(artists = candidate.artists + MusicArtistRef(name = "Guest")))).isEmpty())
    }

    @Test fun knownDurationRequiresCandidateDurationAndRejectsMismatch() {
        val timed = source.copy(durationMs = 180_000)
        val candidate = source.copy(id = MusicResourceId(MusicSource.Netease, "candidate"))
        assertNull(SpotifyTrackMatcher.score(timed, candidate))
        assertNull(SpotifyTrackMatcher.score(timed, candidate.copy(durationMs = 184_000)))
        assertEquals(1, SpotifyTrackMatcher.rank(timed, listOf(candidate.copy(durationMs = 180_500))).size)
    }
}
