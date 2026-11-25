package melox.download

import melox.platform.meloXDataDir
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import melox.music.model.AudioQualityTier
import melox.music.model.MusicAlbumRef
import melox.music.model.MusicArtistRef
import melox.music.model.MusicResourceId
import melox.music.model.MusicSource
import melox.music.model.MusicTrack
import melox.music.model.PlaybackResolution
import melox.music.model.ProviderTrackMetadata
import melox.music.provider.DownloadCapability
import melox.music.provider.MeloXMusicProviders
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class MeloXProviderDownloadedSong(
    val track: MusicTrack,
    val quality: AudioQualityTier,
    val fileName: String,
    val byteCount: Long,
    val format: String? = null,
    val bitrate: Int? = null,
    val downloadedAt: Long,
)

/** Persistent download store for provider-native IDs. Legacy NetEase downloads stay untouched. */
class MeloXProviderDownloadStore internal constructor(
    private val directory: File = File(meloXDataDir(), "melox_provider_downloads"),
    private val registryFactory: () -> melox.music.provider.MusicProviderRegistry = MeloXMusicProviders::create,
) {
    init { directory.mkdirs() }
    private val indexFile = File(directory, "index.json")
    private val taskStore = ProviderDownloadTaskStore(directory)
    private val restoreReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val recordsByKey = ConcurrentHashMap<String, MeloXProviderDownloadedSong>()
    private val indexLock = Any()
    private val removedIdentities = ConcurrentHashMap.newKeySet<String>()
    private val removing = ConcurrentHashMap.newKeySet<String>()

    val downloads = mutableStateListOf<MeloXProviderDownloadedSong>()
    val activeDownloads = mutableStateMapOf<String, Boolean>()
    val attempts = mutableStateMapOf<String, ProviderDownloadAttempt>()
    val downloadErrors = mutableStateMapOf<String, String>()
    var taskRecoveryError by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    init {
        scope.launch {
            try {
            val restored = withContext(Dispatchers.IO) { readIndex() }
            restored.forEach { record ->
                val identity = key(record.track.id)
                if (identity !in removedIdentities && recordsByKey.putIfAbsent(identity, record) == null) downloads += record
            }
            val tasks = withContext(Dispatchers.IO) { taskStore.load() }
            for (task in tasks) {
                if (isDownloaded(task.track.id)) {
                    withContext(Dispatchers.IO) { taskStore.remove(task.track.id, task.revision) }
                } else if (key(task.track.id) !in removedIdentities && key(task.track.id) !in attempts) {
                    attempts[key(task.track.id)] = ProviderDownloadAttempt(task.track, task.quality, task.revision, task.accountScope)
                    downloadErrors[key(task.track.id)] = melox.plugin.PluginErrors.message(task.errorCode ?: "DOWNLOAD_INTERRUPTED")
                }
            }
            } catch (_: Exception) {
                taskRecoveryError = melox.plugin.PluginErrors.message("PERSISTENCE_FAILED")
            } finally { restoreReady.complete(Unit) }
        }
    }

    fun isDownloaded(id: MusicResourceId): Boolean = recordsByKey[key(id)]?.let { record ->
        File(directory, record.fileName).isFile
    } == true

    fun isDownloading(id: MusicResourceId): Boolean = activeDownloads[key(id)] == true

    fun recordFor(id: MusicResourceId): MeloXProviderDownloadedSong? = recordsByKey[key(id)]

    fun errorFor(id: MusicResourceId): String? = downloadErrors[key(id)]

    fun retry(id: MusicResourceId) {
        val attempt = attempts[key(id)] ?: return
        if (attempt.accountScope != null) {
            downloadErrors[key(id)] = melox.plugin.PluginErrors.message("DOWNLOAD_ACCOUNT_CHANGED")
            return
        }
        start(attempt.track, attempt.quality)
    }

    fun dismiss(id: MusicResourceId) {
        val identity = key(id)
        if (jobs.containsKey(identity)) return
        val attempt = attempts[identity] ?: return
        lateinit var cleanup: Job
        cleanup = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                restoreReady.await()
                taskStore.remove(id, attempt.revision)
                withContext(Dispatchers.Main) {
                    if (attempts[identity]?.revision == attempt.revision) attempts.remove(identity)
                    downloadErrors.remove(identity)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { downloadErrors[identity] = melox.plugin.PluginErrors.message("PERSISTENCE_FAILED") }
            }
        }
        jobs[identity] = cleanup
        cleanup.invokeOnCompletion { jobs.remove(identity, cleanup) }
        cleanup.start()
    }

    fun remove(id: MusicResourceId) {
        val idKey = key(id)
        if (!removing.add(idKey)) return
        removedIdentities.add(idKey)
        val owner = jobs[idKey]
        owner?.cancel()
        scope.launch {
            try {
                owner?.join()
                restoreReady.await()
                attempts[idKey]?.let { attempt ->
                    withContext(Dispatchers.IO) { taskStore.remove(id, attempt.revision) }
                }
                recordsByKey.remove(idKey)?.let { record ->
                    withContext(Dispatchers.IO) { File(directory, record.fileName).delete() }
                    downloads.removeAll { key(it.track.id) == idKey }
                }
                attempts.remove(idKey)
                activeDownloads.remove(idKey)
                downloadErrors.remove(idKey)
                withContext(Dispatchers.IO) { writeIndex() }
            } catch (_: Exception) {
                downloadErrors[idKey] = melox.plugin.PluginErrors.message("PERSISTENCE_FAILED")
            } finally { removing.remove(idKey) }
        }
    }

    fun localPlaybackUri(id: MusicResourceId): String? = recordsByKey[key(id)]
        ?.let { File(directory, it.fileName) }
        ?.takeIf(File::isFile)
        ?.let { it.toURI().toString() }

    fun start(track: MusicTrack, quality: AudioQualityTier = AudioQualityTier.Standard) {
        val idKey = key(track.id)
        if (isDownloaded(track.id) || jobs.containsKey(idKey) || idKey in removing) return
        val attempt = ProviderDownloadAttempt(track, quality)
        attempts[idKey] = attempt
        downloadErrors.remove(idKey)
        activeDownloads[idKey] = true
        lateinit var job: Job
        job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val staging = File(directory, "${digest(idKey)}-${attempt.revision}.part")
            var destination: File? = null
            var committed = false
            try {
                restoreReady.await()
                if (isDownloaded(track.id)) {
                    committed = true
                    withContext(Dispatchers.Main) { attempts.remove(idKey) }
                    return@launch
                }
                taskStore.save(ProviderDownloadTaskStore.Task(track, quality, attempt.revision))
                val registry = registryFactory()
                val generation = registry.generation
                val provider = registry[track.id.sourceId] ?: error("SOURCE_UNAVAILABLE")
                val downloader = provider as? DownloadCapability ?: error("UNSUPPORTED_OPERATION")
                val resolution = try { kotlinx.coroutines.withTimeout(12_000L) { downloader.resolveDownload(track, quality) } }
                    catch (timeout: kotlinx.coroutines.TimeoutCancellationException) { throw java.io.IOException("Download resolution timed out", timeout) }
                val playable = resolution as? PlaybackResolution.Playable
                    ?: error("${track.id.sourceId.displayName} 当前没有可下载音频")
                val extension = extensionFor(playable.format)
                val fileName = "${digest(idKey)}.$extension"
                val target = File(directory, fileName)
                destination = target
                check(registry.generation == generation && registry[track.id.sourceId] === provider) { "SOURCE_UNAVAILABLE" }
                if (track.id.sourceId.builtIn == null) {
                    val plugin = provider as? melox.plugin.PluginMusicProvider ?: error("SOURCE_UNAVAILABLE")
                    val current = { registry.generation == generation && registry[track.id.sourceId] === provider }
                    try {
                        downloadPlugin(plugin, playable, staging, registry.changes, current)
                    } catch (failure: melox.plugin.PluginMediaHttpException) {
                        if (failure.status !in setOf(401, 403, 410)) throw failure
                        currentCoroutineContext().ensureActive()
                        check(current()) { "SOURCE_UNAVAILABLE" }
                        val refreshed = kotlinx.coroutines.withTimeout(12_000L) {
                            downloader.resolveDownload(track, quality)
                        } as? PlaybackResolution.Playable ?: error("DOWNLOAD_FAILED")
                        check(current()) { "SOURCE_UNAVAILABLE" }
                        require(refreshed.format == playable.format) { "UNSUPPORTED_MEDIA_FORMAT" }
                        downloadPlugin(plugin, refreshed, staging, registry.changes, current)
                    }
                } else download(playable, staging)
                currentCoroutineContext().ensureActive()
                check(registry.generation == generation && registry[track.id.sourceId] === provider) { "SOURCE_UNAVAILABLE" }
                java.nio.file.Files.move(staging.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                val record = MeloXProviderDownloadedSong(
                    track = track,
                    quality = quality,
                    fileName = fileName,
                    byteCount = target.length(),
                    format = playable.format,
                    bitrate = playable.bitrate,
                    downloadedAt = System.currentTimeMillis(),
                )
                withContext(Dispatchers.Main) {
                    currentCoroutineContext().ensureActive()
                    if (jobs[idKey] !== job) return@withContext
                    removedIdentities.remove(idKey)
                    recordsByKey[idKey] = record
                    downloads.removeAll { key(it.track.id) == idKey }
                    downloads += record
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    writeIndex()
                    committed = true
                }
                taskStore.remove(track.id, attempt.revision)
                withContext(Dispatchers.Main) { attempts.remove(idKey); downloadErrors.remove(idKey) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) {
                    runCatching { taskStore.remove(track.id, attempt.revision) }.onFailure {
                        withContext(Dispatchers.Main) { downloadErrors[idKey] = melox.plugin.PluginErrors.message("PERSISTENCE_FAILED") }
                    }.onSuccess { withContext(Dispatchers.Main) { attempts.remove(idKey); downloadErrors.remove(idKey) } }
                }
                throw cancelled
            } catch (failure: Exception) {
                val code = melox.plugin.PluginErrors.code(failure)
                val saved = runCatching { taskStore.save(ProviderDownloadTaskStore.Task(track, quality, attempt.revision, code)) }.isSuccess
                withContext(Dispatchers.Main) { downloadErrors[idKey] = melox.plugin.PluginErrors.message(if (saved) code else "PERSISTENCE_FAILED") }
            } finally {
                staging.delete()
                if (!committed) {
                    destination?.delete()
                    withContext(NonCancellable + Dispatchers.Main) {
                        recordsByKey.remove(idKey)
                        downloads.removeAll { key(it.track.id) == idKey }
                    }
                }
                withContext(NonCancellable + Dispatchers.Main) { activeDownloads.remove(idKey) }
            }
        }
        jobs[idKey] = job
        job.invokeOnCompletion {
            jobs.remove(idKey, job)
            scope.launch {
                if (!jobs.containsKey(idKey)) activeDownloads.remove(idKey)
            }
        }
        job.start()
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun downloadPlugin(provider: melox.plugin.PluginMusicProvider,
        resolution: PlaybackResolution.Playable, destination: File,
        changes: kotlinx.coroutines.flow.StateFlow<Long>, current: () -> Boolean) = kotlinx.coroutines.coroutineScope {
        val broker = resolution.pluginMediaAccess?.broker(current)
            ?: melox.plugin.PluginHttpBroker(provider.manifest, accountCurrent = current)
        val observer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            changes.collect { if (!current()) broker.close() }
        }
        val cancellation = currentCoroutineContext()[Job]!!.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) broker.close()
        }
        try {
            broker.use { transport ->
                PluginDownloadStagingBudget.reserve(destination).use {
                    transport.openMedia(resolution.url, resolution.requestHeaders, resolution.pluginMediaAccess != null).use { input ->
                        destination.outputStream().use { output -> copyAudio(input, output) }
                    }
                }
            }
        } finally { cancellation.dispose(); observer.cancel() }
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun download(resolution: PlaybackResolution.Playable, destination: File) {
        destination.parentFile?.mkdirs()
        if (resolution.url.startsWith("file:")) {
            val uri = java.net.URI(resolution.url)
            val source = File(uri)
            AudioDownloadLimit.check(source.length())
            source.inputStream().use { input ->
                    destination.outputStream().use { output -> copyAudio(input, output) }
            }
            return
        }
        val request = Request.Builder().url(resolution.url).apply {
            resolution.requestHeaders.forEach(::header)
        }.build()
        val call = melox.network.MeloXHttpClient.shared.newCall(request)
        val cancellation = currentCoroutineContext()[Job]!!.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) call.cancel()
        }
        try { call.execute().use { response ->
            check(response.isSuccessful) { "下载失败: HTTP ${response.code}" }
            val body = requireNotNull(response.body) { "下载响应为空" }
            AudioDownloadLimit.check(body.contentLength())
            body.byteStream().use { input -> destination.outputStream().use { output -> copyAudio(input, output) } }
        } } finally { cancellation.dispose() }
    }

    private suspend fun copyAudio(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(32 * 1024)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) return
            count += read
            AudioDownloadLimit.check(count)
            output.write(buffer, 0, read)
        }
    }

    private fun readIndex(): List<MeloXProviderDownloadedSong> = runCatching {
        if (!indexFile.isFile) return emptyList()
        val array = JSONArray(indexFile.readText())
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val source = melox.music.model.SourceId.parse(json.optString("source")) ?: continue
                val id = json.optString("id").takeIf(String::isNotBlank) ?: continue
                val fileName = json.optString("fileName").takeIf(String::isNotBlank) ?: continue
                val track = if (json.has("track")) {
                    ProviderDownloadIndexCodec.decodeTrack(json) ?: continue
                } else MusicTrack(
                    id = MusicResourceId(source, id),
                    title = json.optString("title", "未知歌曲"),
                    artists = json.optString("artists").split(" / ").filter(String::isNotBlank)
                        .map { name -> MusicArtistRef(name = name) },
                    album = json.optString("album").takeIf(String::isNotBlank)
                        ?.let { name -> MusicAlbumRef(name = name) },
                    artworkUrl = json.optString("artworkUrl").takeIf(String::isNotBlank),
                    durationMs = json.optLong("durationMs").takeIf { it > 0L },
                    providerMetadata = if (source.builtIn == MusicSource.Spotify) {
                        ProviderTrackMetadata.Spotify(id)
                    } else ProviderTrackMetadata.Empty,
                )
                val file = File(directory, fileName)
                if (file.isFile) add(
                    MeloXProviderDownloadedSong(
                        track = track,
                        quality = AudioQualityTier.entries.firstOrNull { it.name == json.optString("quality") }
                            ?: AudioQualityTier.Standard,
                        fileName = fileName,
                        byteCount = file.length(),
                        format = json.optString("format").takeIf(String::isNotBlank),
                        bitrate = json.optInt("bitrate").takeIf { it > 0 },
                        downloadedAt = json.optLong("downloadedAt"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun writeIndex() = synchronized(indexLock) {
        val records = recordsByKey.values.toList()
        val array = JSONArray()
        records.forEach { record ->
            val track = record.track
            array.put(JSONObject().apply {
                put("source", track.id.sourceId.value)
                put("track", melox.music.model.MeloXTrackJson.encode(track))
                put("id", track.id.value)
                put("title", track.title)
                put("artists", track.artistText)
                put("album", track.album?.name.orEmpty())
                put("artworkUrl", track.artworkUrl.orEmpty())
                put("durationMs", track.durationMs ?: 0L)
                put("quality", record.quality.name)
                put("fileName", record.fileName)
                put("byteCount", record.byteCount)
                put("format", record.format.orEmpty())
                put("bitrate", record.bitrate ?: 0)
                put("downloadedAt", record.downloadedAt)
            })
        }
            // Reading failure must leave the previous index intact, rather than
            // replacing unknown or corrupt data with a partial in-memory view.
            val previous = if (indexFile.isFile) JSONArray(indexFile.readText()) else JSONArray()
            val merged = ProviderDownloadIndexCodec.merge(previous, array, removedIdentities.toSet())
            val temporary = java.nio.file.Files.createTempFile(directory.toPath(), "index-", ".tmp")
            try {
                java.nio.file.Files.writeString(temporary, merged.toString())
                java.nio.file.Files.move(temporary, indexFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { java.nio.file.Files.deleteIfExists(temporary) }
    }

    private fun extensionFor(format: String?): String = when (format?.lowercase()) {
        "mp3" -> "mp3"
        "m4a", "mp4" -> "m4a"
        "opus", "ogg" -> "ogg"
        else -> "audio"
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun key(id: MusicResourceId): String = "${id.sourceId.value}:${id.value}"

    companion object {
        @Volatile private var instance: MeloXProviderDownloadStore? = null

        fun get(): MeloXProviderDownloadStore =
            instance ?: synchronized(this) {
                instance ?: MeloXProviderDownloadStore().also { instance = it }
            }
    }
}

data class ProviderDownloadAttempt(val track: MusicTrack, val quality: AudioQualityTier,
    val revision: String = java.util.UUID.randomUUID().toString(), val accountScope: String? = null)
