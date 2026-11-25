package melox.recommendation

import melox.music.model.*
import melox.music.provider.TrackAggregation
import kotlin.test.*

class RecommendationTrainingFeaturesTest {
    @Test fun sourceBehaviorMergesOnceAndUnrelatedSameResourceSurvives() {
        val first = MusicTrack(MusicResourceId(SourceId.parse("plugin:test.first")!!, "123"), "Song", listOf(MusicArtistRef(name = "Artist")))
        val second = first.copy(id = MusicResourceId(SourceId.parse("plugin:test.second")!!, "123"))
        fun key(track: MusicTrack) = SourceResourceUri.encode(track.id.sourceId, track.id.value)
        val groups = TrackAggregation.aggregate(listOf(first))
        val existing = listOf(LocalTrackFeatures(key(first), "Song", "Artist", playCount = 2, completedCount = 1),
            LocalTrackFeatures(key(second), "Other", "Artist", playCount = 3, skipCount = 1))
        val merged = recommendationTrainingFeatures(groups, existing)
        assertEquals(2, merged.size)
        assertEquals(existing[1], merged.first { it.trackKey == key(second) })
        val imported = merged.first { it.trackKey != key(second) }
        assertEquals(2, imported.playCount)
        assertEquals(1, imported.completedCount)
        assertEquals(0, imported.skipCount)
        assertTrue(imported.liked)
        assertEquals(merged, recommendationTrainingFeatures(groups, merged))
        val newBehavior = existing.first().copy(playCount = 1, completedCount = 0)
        val next = recommendationTrainingFeatures(groups, merged + newBehavior)
        assertEquals(3, next.first { it.trackKey == imported.trackKey }.playCount)
        assertEquals(merged, recommendationTrainingFeatures(emptyList(), merged))
    }
}
