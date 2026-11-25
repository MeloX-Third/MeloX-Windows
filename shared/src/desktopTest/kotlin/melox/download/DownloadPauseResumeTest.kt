package melox.download

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import melox.audio.MusicQuality
import melox.model.SearchSong
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadPauseResumeTest {
    @Test fun immediateResumeWaitsForCancelledOwnerAndCancelPreventsRestart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = MeloXDownloadStore.instance()
        val id = 876543210L
        val song = SearchSong(id, "Pause test", "Artist", "Album", null)
        @Suppress("UNCHECKED_CAST")
        val jobs = MeloXDownloadStore::class.java.getDeclaredField("jobs").let {
            it.isAccessible = true
            it.get(store) as ConcurrentHashMap<Long, Job>
        }
        var cleaned = false
        val owner = backgroundScope.launch {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { delay(100); cleaned = true; jobs.remove(id) }
            }
        }
        jobs[id] = owner
        store.activeDownloads[id] = MeloXActiveDownload(song, MusicQuality.Standard)
        try {
            runCurrent()
            store.pause(id)
            assertFalse(store.activeDownloads.containsKey(id))
            assertTrue(store.pausedDownloads.containsKey(id))
            store.resume(id)
            runCurrent()
            assertSame(owner, jobs[id])
            assertFalse(cleaned)
            store.cancel(id)
            advanceTimeBy(101)
            runCurrent()
            assertTrue(cleaned)
            assertFalse(jobs.containsKey(id))
            assertFalse(store.pausedDownloads.containsKey(id))
            assertFalse(store.activeDownloads.containsKey(id))
        } finally {
            store.cancel(id)
            owner.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }
}
