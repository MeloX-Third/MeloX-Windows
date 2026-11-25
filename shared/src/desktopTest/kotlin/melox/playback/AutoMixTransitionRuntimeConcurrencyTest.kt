package melox.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import melox.music.model.MusicResourceId
import melox.music.model.MusicSource
import melox.music.model.MusicTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoMixTransitionRuntimeConcurrencyTest {
    @AfterTest
    fun reset() = MeloXAutoMixTransitionRuntime.cancel()

    private fun track(id: String) = MusicTrack(
        id = MusicResourceId(MusicSource.Local, id),
        title = id,
        artists = emptyList(),
    )

    @Test
    fun concurrentStateWritersPublishCompleteSnapshots() = runBlocking {
        MeloXAutoMixTransitionRuntime.cancel()
        val outgoing = track("outgoing")
        val incoming = track("incoming")

        (0 until 200).map { index ->
            async(Dispatchers.Default) {
                MeloXAutoMixTransitionRuntime.update(outgoing, incoming, index / 200f, index.toLong())
            }
        }.awaitAll()

        val snapshot = MeloXAutoMixTransitionRuntime.state.value
        assertEquals("mixing", snapshot.status)
        assertTrue(snapshot.active)
        assertEquals(outgoing, snapshot.outgoing)
        assertEquals(incoming, snapshot.incoming)
        assertTrue(snapshot.progress in 0f..1f)
        assertTrue(snapshot.incomingPositionMs >= 0L)
    }

    @Test
    fun statusUpdatesCannotUndoAConcurrentHandoff() {
        val outgoing = track("outgoing")
        val incoming = track("incoming")
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val statusWriter = executor.submit {
                repeat(10_000) {
                    barrier.await(10, TimeUnit.SECONDS)
                    MeloXAutoMixTransitionRuntime.status("ready")
                    barrier.await(10, TimeUnit.SECONDS)
                }
            }
            repeat(10_000) {
                MeloXAutoMixTransitionRuntime.update(outgoing, incoming, .5f, 123L)
                barrier.await(10, TimeUnit.SECONDS)
                MeloXAutoMixTransitionRuntime.handoff()
                barrier.await(10, TimeUnit.SECONDS)
                val snapshot = MeloXAutoMixTransitionRuntime.state.value
                assertTrue(snapshot.handedOff, "A status update must preserve the handoff")
                assertEquals(false, snapshot.active)
                assertEquals(1f, snapshot.progress)
                assertEquals(incoming, snapshot.incoming)
                assertEquals(123L, snapshot.incomingPositionMs)
            }
            statusWriter.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
