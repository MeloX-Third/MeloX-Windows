package melox.download

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import melox.audio.MusicQuality
import melox.model.SearchSong
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadPauseResumeTest {
    @Test fun pausingQueuedTransferRetainsItsDescriptionAndReleasesOwnerBeforeCancel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = MeloXDownloadStore.instance()
        val song = SearchSong(876543211L, "Queued pause test", "Artist", "Album", null)
        val slots = MeloXDownloadStore::class.java.getDeclaredField("transferSlots").let {
            it.isAccessible = true
            it.get(store) as kotlinx.coroutines.sync.Semaphore
        }
        repeat(3) { slots.acquire() }
        try {
            store.start(song, MusicQuality.High)
            runCurrent()
            assertTrue(store.isDownloading(song.id))
            store.pause(song.id)
            assertTrue(store.isPaused(song.id))
            assertEquals(MusicQuality.High, store.pausedDownloads[song.id]?.quality)
            assertFalse(store.isDownloading(song.id))
            store.resume(song.id)
            store.cancel(song.id)
            runCurrent()
            // Cleanup performs real IO before releasing the per-song owner.
            @Suppress("UNCHECKED_CAST")
            val jobs = MeloXDownloadStore::class.java.getDeclaredField("jobs").let {
                it.isAccessible = true
                it.get(store) as ConcurrentHashMap<Long, Job>
            }
            jobs[song.id]?.join()
            assertFalse(jobs.containsKey(song.id))
            assertFalse(store.isPaused(song.id))
            assertFalse(store.isDownloading(song.id))
            assertEquals(0, slots.availablePermits)
        } finally {
            store.cancel(song.id)
            repeat(3) { slots.release() }
            Dispatchers.resetMain()
        }
    }

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
