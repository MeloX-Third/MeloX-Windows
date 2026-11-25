package melox.download

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import melox.plugin.PluginHttpBroker
import org.json.JSONObject

/** Persist only validators and an opaque host scope; resolved URLs and headers stay in memory. */
internal object PluginDownloadTransfer {
    private data class Resume(val scope: String, val etag: String, val total: Long)

    fun run(destination: File, scope: String?, active: () -> Boolean,
        open: (Long?, String?) -> PluginHttpBroker.MediaResponse) {
        val metadata = File(destination.path + ".resume.json")
        val saved = runCatching {
            require(metadata.length() in 1..4096)
            val json = JSONObject(metadata.readText())
            Resume(json.getString("scope"), json.getString("etag"), json.getLong("total"))
        }.getOrNull()?.takeIf { scope != null && it.scope == scope && strongEtag(it.etag) &&
            it.total in 1..AudioDownloadLimit.MAX_BYTES && destination.isFile && destination.length() in 1 until it.total }
        if (saved == null) clear(destination)
        val offset = if (saved == null) 0L else destination.length()
        var resumable = false
        try {
            check(active()) { "SOURCE_UNAVAILABLE" }
            open(offset.takeIf { saved != null }, saved?.etag).use { response ->
                require(response.headers["Content-Encoding"].let { it == null || it.equals("identity", true) }) { "INVALID_ARGUMENT" }
                val etag = response.headers["ETag"]?.takeIf(::strongEtag)
                val append = response.status == 206
                val total = if (append) {
                    require(saved != null && etag == saved.etag) { "INVALID_ARGUMENT" }
                    val range = requireNotNull(Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
                        .matchEntire(response.headers["Content-Range"].orEmpty())) { "INVALID_ARGUMENT" }
                    val start = range.groupValues[1].toLong()
                    val end = range.groupValues[2].toLong()
                    val size = range.groupValues[3].toLong()
                    require(start == offset && size == saved.total && end == size - 1 && start <= end &&
                        (response.length == -1L || response.length == size - offset)) { "INVALID_ARGUMENT" }
                    size
                } else {
                    require(response.status == 200) { "INVALID_ARGUMENT" }
                    response.length
                }
                require(total == -1L || total in 1..AudioDownloadLimit.MAX_BYTES) { "INVALID_ARGUMENT" }
                PluginDownloadStagingBudget.reserve(destination, total.takeIf { it > 0 } ?: AudioDownloadLimit.MAX_BYTES).use {
                    FileOutputStream(destination, append).use { output ->
                        if (!append) output.fd.sync()
                        if (scope != null && etag != null && total > 0) {
                            write(metadata, Resume(scope, etag, total))
                            resumable = true
                        } else metadata.delete()
                        var received = if (append) offset else 0L
                        val buffer = ByteArray(8192)
                        while (true) {
                            check(active() && !Thread.currentThread().isInterrupted) { "SOURCE_UNAVAILABLE" }
                            val count = response.stream.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= AudioDownloadLimit.MAX_BYTES && (total < 0 || received <= total)) { "INVALID_ARGUMENT" }
                            output.write(buffer, 0, count)
                        }
                        require(received > 0 && (total < 0 || received == total)) { "INVALID_ARGUMENT" }
                        output.fd.sync()
                    }
                }
                check(active()) { "SOURCE_UNAVAILABLE" }
                metadata.delete()
            }
        } catch (failure: Throwable) {
            if (!resumable || failure !is java.io.IOException || !runCatching(active).getOrDefault(false)) clear(destination)
            throw failure
        }
    }

    fun clear(destination: File) {
        destination.delete()
        File(destination.path + ".resume.json").delete()
    }

    private fun strongEtag(value: String) = value.length in 2..512 && value.first() == '"' && value.last() == '"' &&
        value.substring(1, value.lastIndex).all { it.code in 0x21..0x7e && it != '"' }

    private fun write(file: File, resume: Resume) {
        val temporary = File(file.path + ".tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(JSONObject().put("scope", resume.scope).put("etag", resume.etag).put("total", resume.total)
                    .toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
