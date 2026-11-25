package melox.download

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import melox.music.model.*
import melox.music.provider.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class ProviderDownloadLifecycleTest {
    @Test fun missingSourceRestoresAsManualRetryAndSuccessfulRetryCommitsLocalFile() = runBlocking {
        val main = newSingleThreadContext("provider-download-main")
        Dispatchers.setMain(main)
        val directory = Files.createTempDirectory("provider-lifecycle").toFile()
        val source = SourceId.parse("plugin:example.catalog")!!
        val track = MusicTrack(MusicResourceId(source, "123"), "Track", emptyList())
        val registry = MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()))
        try {
            val store = MeloXProviderDownloadStore(directory) { registry }
            withContext(main) { store.start(track) }
            awaitIdle(store)
            assertEquals("SOURCE_UNAVAILABLE", ProviderDownloadTaskStore(directory).load().single().errorCode)
            val restored = MeloXProviderDownloadStore(directory) { registry }
            withTimeout(5000) { while (withContext(main) { restored.attempts.isEmpty() }) delay(10) }
            assertFalse(restored.isDownloading(track.id))
            val audio = directory.resolve("input.flac").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            registry.registerDynamic(object : MusicProvider, DownloadCapability {
                override val sourceId = source
                override val displayName = "Example"
                override val capabilities = emptySet<MusicCapability>()
                override suspend fun resolveDownload(track: MusicTrack, quality: AudioQualityTier) =
                    PlaybackResolution.Playable(audio.toURI().toString(), requestedQuality = quality, format = "flac")
            })
            withContext(main) { restored.retry(track.id) }
            awaitIdle(restored)
            assertTrue(restored.isDownloaded(track.id))
            assertTrue(ProviderDownloadTaskStore(directory).load().isEmpty())
            assertEquals(listOf<Byte>(1, 2, 3, 4), java.io.File(java.net.URI(restored.localPlaybackUri(track.id)!!)).readBytes().toList())
            assertFalse(directory.listFiles()!!.any { it.extension == "part" })
        } finally { Dispatchers.resetMain(); main.close(); directory.deleteRecursively() }
    }

    private suspend fun awaitIdle(store: MeloXProviderDownloadStore) {
        @Suppress("UNCHECKED_CAST")
        val jobs = MeloXProviderDownloadStore::class.java.getDeclaredField("jobs").let {
            it.isAccessible = true
            it.get(store) as java.util.concurrent.ConcurrentHashMap<String, Job>
        }
        withTimeout(5000) { jobs.values.toList().forEach { it.join() } }
    }
}
