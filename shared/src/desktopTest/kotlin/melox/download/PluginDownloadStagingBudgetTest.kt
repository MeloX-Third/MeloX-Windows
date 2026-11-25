package melox.download

import java.nio.file.Files
import kotlin.test.*

class PluginDownloadStagingBudgetTest {
    @Test fun concurrentReservationsAndRetainedBytesShareOneBudget() {
        val root = Files.createTempDirectory("plugin-staging-budget").toFile()
        try {
            val a = root.resolve("a.part")
            val b = root.resolve("b.part")
            val c = root.resolve("c.part")
            PluginDownloadStagingBudget.reserve(a).use {
                assertFailsWith<IllegalArgumentException> { PluginDownloadStagingBudget.reserve(a) }
                PluginDownloadStagingBudget.reserve(b).use {
                    assertFailsWith<IllegalArgumentException> { PluginDownloadStagingBudget.reserve(c, 1) }
                }
                root.resolve("retained.part").writeBytes(byteArrayOf(1))
                assertFailsWith<IllegalArgumentException> { PluginDownloadStagingBudget.reserve(b) }
                PluginDownloadStagingBudget.reserve(b, AudioDownloadLimit.MAX_BYTES - 1).close()
            }
            val released = PluginDownloadStagingBudget.reserve(a)
            released.close()
            PluginDownloadStagingBudget.reserve(a).use {
                released.close()
                assertFailsWith<IllegalArgumentException> { PluginDownloadStagingBudget.reserve(a) }
            }
        } finally { root.deleteRecursively() }
    }
}
