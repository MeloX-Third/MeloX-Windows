package melox.download

import java.io.IOException

/** Same per-track audio limit as Android edc829e, including unknown-length responses. */
internal object AudioDownloadLimit {
    const val MAX_BYTES = 512L * 1024L * 1024L

    fun check(byteCount: Long) {
        if (byteCount > MAX_BYTES) throw IOException("下载文件超过大小限制")
    }

    fun copy(input: java.io.InputStream, output: java.io.OutputStream, limit: Long = MAX_BYTES): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
        var received = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return received
            received += count
            if (received > limit) throw IOException("下载文件超过大小限制")
            output.write(buffer, 0, count)
        }
    }
}
