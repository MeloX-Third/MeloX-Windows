package melox.download

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import melox.music.model.*
import melox.music.provider.*
import kotlin.test.*
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class ProviderDownloadLifecycleTest {
    @Test fun rejectedPluginDownloadResolvesOnceAndStopsAfterSecondRejection() = runBlocking {
        val main = newSingleThreadContext("download-refresh-main")
        Dispatchers.setMain(main)
        val directory = Files.createTempDirectory("download-refresh").toFile()
        val source = SourceId.parse("plugin:test.download.refresh")!!
        val policy = melox.plugin.PluginAuthentication("apiToken", domains = setOf("api.example.com"))
        val manifest = melox.plugin.PluginManifest(source, "Refresh", "1.0.0", 1, "index.js",
            setOf(melox.plugin.PluginCapability.Download), policy.domains, authentication = policy)
        val registry = MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()))
        var resolutions = 0
        var requests = 0
        var alwaysReject = false
        val provider = melox.plugin.PluginMusicProvider(manifest) { _, _ ->
            resolutions++
            """{"type":"playable","url":"https://api.example.com/audio/$resolutions","credential":"active","format":"mp3"}"""
        }
        val lease = object : melox.plugin.PluginCredentialLease {
            override val source = provider.sourceId
            override val token = "host-secret"
            override val policy = manifest.authentication!!
            override val expiresAt: Long? = null
            override fun isCurrent() = true
        }
        provider.mediaAccess = {
            melox.plugin.PluginMediaAccess({ true }) { active ->
                melox.plugin.PluginHttpBroker(manifest, accountCurrent = active, credential = lease,
                    internalTransport = okhttp3.Interceptor { chain ->
                        requests++
                        assertEquals("Bearer host-secret", chain.request().header("Authorization"))
                        okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(if (alwaysReject || requests % 2 == 1) 403 else 200).message("fixture")
                            .body("audio-fixture".toResponseBody()).build()
                    })
            }
        }
        registry.registerDynamic(provider)
        try {
            val store = MeloXProviderDownloadStore(directory) { registry }
            val first = MusicTrack(MusicResourceId(source, "first"), "First", emptyList())
            withContext(main) { store.start(first) }
            awaitIdle(store)
            assertTrue(store.isDownloaded(first.id))
            assertEquals(2, resolutions)
            assertEquals(2, requests)
            assertEquals("audio-fixture", java.io.File(java.net.URI(store.localPlaybackUri(first.id)!!)).readText())
            alwaysReject = true
            val second = MusicTrack(MusicResourceId(source, "second"), "Second", emptyList())
            withContext(main) { store.start(second) }
            awaitIdle(store)
            assertFalse(store.isDownloaded(second.id))
            assertEquals(4, resolutions)
            assertEquals(4, requests)
            assertFalse(directory.listFiles()!!.any { it.extension == "part" })
        } finally { Dispatchers.resetMain(); main.close(); directory.deleteRecursively() }
    }

    @Test fun missingSourceRestoresAsManualRetryAndDynamicFileCannotBypassBroker() = runBlocking {
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
            assertFalse(restored.isDownloaded(track.id))
            assertEquals("SOURCE_UNAVAILABLE", ProviderDownloadTaskStore(directory).load().single().errorCode)
            assertNull(restored.localPlaybackUri(track.id))
            assertFalse(directory.listFiles()!!.any { it.extension == "part" })
            registry.replaceDynamic(melox.plugin.PluginMusicProvider(
                melox.plugin.PluginManifest(source, "Example", "1.0.0", 1, "index.js",
                    setOf(melox.plugin.PluginCapability.Download), emptySet()),
                melox.plugin.PluginInvoker { _, _ -> """{"type":"playable","url":"${audio.toURI()}","format":"flac"}""" }))
            withContext(main) { restored.retry(track.id) }
            awaitIdle(restored)
            assertFalse(restored.isDownloaded(track.id))
            assertEquals("INVALID_ARGUMENT", ProviderDownloadTaskStore(directory).load().single().errorCode)
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
