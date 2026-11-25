package melox.recommendation

import melox.music.model.*
import kotlin.test.*

class HomeRecommendationPoolTest {
    private fun track(source: String, id: String) = MusicTrack(MusicResourceId(SourceId.parse(source)!!, id), id, emptyList())

    @Test fun sameResourceAcrossSourcesSurvivesAndExistingCardsStayStable() {
        val pool = HomeRecommendationPool { it }
        val first = track("plugin:example.one", "shared")
        val second = track("plugin:example.two", "shared")
        assertEquals(listOf(first), pool.ensureSource("one", listOf(first, first)))
        assertEquals(listOf(second, first), pool.appendTracks(listOf(second, first, second)))
        assertEquals(listOf(second, first), pool.ensureSource("one", listOf(first)))
        assertEquals(listOf(first, second), pool.ensureSource("two", emptyList()))
    }

    @Test fun entireNewQueueContributesAndDisplayIsLimitedToFive() {
        val pool = HomeRecommendationPool { it }
        val queue = (1..8).map { track("plugin:example.queue", it.toString()) }
        assertEquals(queue.take(5), pool.appendTracks(queue))
        assertEquals(queue.take(5), pool.appendTracks(queue))
        val fresh = track("plugin:example.queue", "fresh")
        assertEquals(listOf(fresh) + queue.take(4), pool.appendTracks(listOf(fresh)))
    }
}
