package melox.recommendation

import melox.music.model.*
import org.json.JSONObject
import kotlin.test.*

class RecommendationCandidateIdentityTest {
    @Test fun completeCandidateRetainsProviderPlaybackIdentityAndQuality() {
        val track = MusicTrack(MusicResourceId(MusicSource.QQMusic, "song-mid"), "Song",
            listOf(MusicArtistRef(name = "Artist")),
            providerMetadata = ProviderTrackMetadata.QQMusic("song-mid", "media-mid", 123),
            bestQuality = AudioQualityTier.Lossless, bestBitrate = 900000)
        val item = JSONObject().put("source", "qq_music").put("id", "song-mid")
            .put("track", MeloXTrackJson.encode(track))
        assertEquals(track, LocalRecommendationStore.decodeCandidate(item))
        item.put("id", "different-song")
        assertNull(LocalRecommendationStore.decodeCandidate(item))
    }

    @Test fun legacyCandidatesRemainReadableAndInvalidSourcesAreRejected() {
        val item = JSONObject().put("source", "netease").put("id", "123")
            .put("title", "Legacy").put("artist", "Artist")
        assertEquals(MusicResourceId(MusicSource.Netease, "123"), LocalRecommendationStore.decodeCandidate(item)?.id)
        item.put("source", "invalid/source")
        assertNull(LocalRecommendationStore.decodeCandidate(item))
        item.put("source", "netease").put("track", "corrupt")
        assertNull(LocalRecommendationStore.decodeCandidate(item))
    }

    @Test fun dynamicCandidatesRetainTheirIdentityAndRejectConflictingNestedSource() {
        val source = SourceId.parse("plugin:example.catalog")!!
        val track = MusicTrack(MusicResourceId(source, "123"), "Song",
            listOf(MusicArtistRef(MusicResourceId(source, "artist"), "Artist")),
            bestQuality = AudioQualityTier.Lossless)
        val item = JSONObject().put("source", source.value).put("id", "123")
            .put("track", MeloXTrackJson.encode(track))
        assertEquals(track, LocalRecommendationStore.decodeCandidate(item))
        item.put("source", "plugin:other.catalog")
        assertNull(LocalRecommendationStore.decodeCandidate(item))
        item.remove("track")
        val legacy = assertNotNull(LocalRecommendationStore.decodeCandidate(item))
        assertEquals("plugin:other.catalog", legacy.id.sourceId.value)
        assertNull(legacy.id.sourceId.builtIn)
    }
}
