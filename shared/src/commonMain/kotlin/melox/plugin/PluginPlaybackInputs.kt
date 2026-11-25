package melox.plugin

import java.io.Closeable
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import melox.music.provider.MusicProviderRegistry

/** Opaque process-local decoder inputs. Native decoders never receive provider URLs or headers. */
internal object PluginPlaybackInputs {
    internal data class MediaRequest(val url: String, val headers: Map<String, String>, val expiresAt: Long?, val access: PluginMediaAccess? = null)
    private const val Prefix = "melox-plugin-audio:"
    private data class Source(val expires: Long, val current: () -> Boolean, val open: () -> InputStream)
    private val sources = ConcurrentHashMap<String, Source>()

    fun register(provider: PluginMusicProvider, registry: MusicProviderRegistry, generation: Long,
        url: String, headers: Map<String, String>, expiresAt: Long?,
        refresh: (() -> MediaRequest)? = null, access: PluginMediaAccess? = null): String {
        val current = { registry.generation == generation && registry[provider.sourceId] === provider && (access?.current?.invoke() ?: true) }
        val requestHeaders = headers.toMap()
        PluginMediaFormats.check(url)
        PluginHttpBroker(provider.manifest).use { it.authorizeMediaUrl(url) }
        fun open(request: MediaRequest): InputStream {
            check(current()) { "CANCELLED" }
            check(request.expiresAt?.let { it > System.currentTimeMillis() } != false) { "AUTH_EXPIRED" }
            val broker = request.access?.broker(current) ?: PluginHttpBroker(provider.manifest, accountCurrent = current)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch { registry.changes.collect { if (!current()) broker.close() } }
            try {
                val input = broker.openMedia(request.url, request.headers, request.access != null)
                return object : java.io.FilterInputStream(input) {
                    override fun close() { try { super.close() } finally { scope.cancel(); broker.close() } }
                }
            } catch (failure: Throwable) {
                scope.cancel(); broker.close(); throw failure
            }
        }
        val initial = MediaRequest(url, requestHeaders, expiresAt, access)
        return register(expiresAt, current) {
            openWithRefresh(current, { open(initial) }, refresh?.let { resolve -> { open(resolve()) } })
        }
    }

    /** Retry only an unopened rejected response; never replay bytes after decoding starts. */
    internal fun openWithRefresh(current: () -> Boolean, open: () -> InputStream,
        refresh: (() -> InputStream)?): InputStream {
        check(current()) { "CANCELLED" }
        return try { open() } catch (failure: PluginMediaHttpException) {
            if (failure.status !in setOf(401, 403, 410) || refresh == null) throw failure
            check(current()) { "CANCELLED" }
            val input = refresh()
            if (!current()) {
                input.close()
                error("CANCELLED")
            }
            input
        }
    }

    @Synchronized internal fun register(expiresAt: Long?, current: () -> Boolean, open: () -> InputStream): String {
        val now = System.currentTimeMillis()
        sources.entries.removeIf { it.value.expires <= now || !it.value.current() }
        check(sources.size < 512) { "Too many plugin playback inputs" }
        val key = Prefix + UUID.randomUUID()
        sources[key] = Source(minOf(expiresAt ?: Long.MAX_VALUE, now + 30 * 60_000L), current, open)
        return key
    }

    fun isPrivate(uri: String) = uri.startsWith(Prefix)

    fun open(uri: String): InputStream {
        val source = sources[uri] ?: error("Plugin playback input expired")
        check(source.expires > System.currentTimeMillis() && source.current()) { "Plugin playback input revoked" }
        val input = source.open()
        return object : java.io.FilterInputStream(input) {
            override fun read(): Int = checked { super.read() }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = checked { super.read(bytes, offset, length) }
            private fun checked(read: () -> Int): Int = try {
                check(source.expires > System.currentTimeMillis() && source.current()) { "Plugin playback input revoked" }
                val count = read()
                check(source.expires > System.currentTimeMillis() && source.current()) { "Plugin playback input revoked" }
                count
            } catch (failure: Throwable) {
                try { close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                throw failure
            }
        }
    }

    /** Process exit and decoder cancellation both release the stream and its private broker. */
    fun attach(process: Process, input: InputStream): Closeable {
        val closed = AtomicBoolean()
        val owner = Closeable {
            if (closed.compareAndSet(false, true)) {
                runCatching { input.close() }
                runCatching { process.outputStream.close() }
            }
        }
        thread(name = "melox-plugin-audio-input", isDaemon = true) {
            try {
                val buffer = ByteArray(8192)
                while (!closed.get()) {
                    val count = try { input.read(buffer) } catch (failure: Exception) {
                        if (!closed.get()) process.destroyForcibly()
                        throw failure
                    }
                    if (count < 0) break
                    // A duration-limited analysis may finish while input is still arriving.
                    try { process.outputStream.write(buffer, 0, count) } catch (_: java.io.IOException) { break }
                }
            }
            catch (_: Exception) { }
            finally { owner.close() }
        }
        process.onExit().thenRun { owner.close() }
        return owner
    }
}
