package melox.plugin

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bound reads and require atomic replacement; never overwrite the committed file in place. */
internal object PluginPrivateFiles {
    fun read(file: File, limit: Int): ByteArray = file.inputStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "plugin private file too large" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    fun write(file: File, bytes: ByteArray) {
        check(file.parentFile.isDirectory || file.parentFile.mkdirs())
        val temporary = Files.createTempFile(file.parentFile.toPath(), ".plugin-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
