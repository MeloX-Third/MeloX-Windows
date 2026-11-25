package melox.download

import java.io.Closeable
import java.io.File

/** Process-wide reservations include retained partial files, as in Android edc829e. */
internal object PluginDownloadStagingBudget {
    const val MAX_BYTES = 1024L * 1024 * 1024
    private val reservations = mutableMapOf<String, Long>()

    @Synchronized
    fun reserve(part: File, expectedBytes: Long = AudioDownloadLimit.MAX_BYTES): Closeable {
        require(expectedBytes in 1..AudioDownloadLimit.MAX_BYTES) { "INVALID_ARGUMENT" }
        val key = part.canonicalPath
        require(key !in reservations) { "RATE_LIMITED" }
        val retained = part.parentFile?.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.name.endsWith(".part") && it.canonicalPath != key && it.canonicalPath !in reservations }
            .sumOf { it.length().coerceAtMost(MAX_BYTES + 1) }
        require(retained <= MAX_BYTES - reservations.values.sum() - expectedBytes) { "DOWNLOAD_STAGING_FULL" }
        reservations[key] = expectedBytes
        return object : Closeable {
            private var closed = false
            override fun close() { synchronized(PluginDownloadStagingBudget) {
                if (!closed) { closed = true; reservations.remove(key) }
            } }
        }
    }
}
