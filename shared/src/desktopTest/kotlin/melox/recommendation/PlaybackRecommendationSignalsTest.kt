package melox.recommendation

import melox.music.model.*
import kotlin.test.*

class PlaybackRecommendationSignalsTest {
    @Test fun audibleStartCompletionAndEarlySkipRespectCompleteIdentity() {
        val events = mutableListOf<Triple<MusicTrack, Boolean, Boolean>>()
        val signals = PlaybackRecommendationSignals { track, completed, skipped -> events += Triple(track, completed, skipped) }
        val first = MusicTrack(MusicResourceId(SourceId.parse("plugin:test.first")!!, "123"), "Title", emptyList())
        val second = first.copy(id = MusicResourceId(SourceId.parse("plugin:test.second")!!, "123"))
        signals.sample(first, false, 0, 100_000)
        signals.skip(first, 0)
        assertTrue(events.isEmpty())
        signals.sample(first, true, 0, 100_000)
        signals.sample(first, false, 10_000, 100_000)
        signals.sample(first, true, 20_000, 100_000)
        assertEquals(1, events.size)
        signals.skip(first, 20_000)
        assertTrue(events.last().third)
        signals.sample(second, true, 84_999, 100_000)
        assertEquals(second, events.last().first)
        signals.sample(second, true, 85_000, 100_000)
        assertTrue(events.last().second)
        val count = events.size
        signals.sample(second, true, 90_000, 100_000)
        signals.skip(second, 0)
        assertEquals(count, events.size)
        signals.sample(null, false, 0, 0)
        signals.sample(second, true, 0, 100_000)
        signals.skip(second, 30_000)
        assertEquals(count + 1, events.size)
    }
}
