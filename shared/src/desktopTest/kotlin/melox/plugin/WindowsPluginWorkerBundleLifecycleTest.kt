package melox.plugin

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsPluginWorkerBundleLifecycleTest {
    @Test fun staleBundlesAreRemovedButCurrentRecentAndFilesAreKept() {
        val root = Files.createTempDirectory("plugin-bundle-cleanup-").toFile()
        try {
            val now = System.currentTimeMillis()
            val current = java.io.File(root, "current").apply { mkdirs() }
            val recent = java.io.File(root, "recent").apply { mkdirs() }
            val stale = java.io.File(root, "stale").apply {
                mkdirs(); java.io.File(this, "runtime").mkdirs()
            }
            val stray = java.io.File(root, "keep.txt").apply { writeText("keep") }
            recent.setLastModified(now - 29L * 24 * 60 * 60 * 1000)
            stale.setLastModified(now - 31L * 24 * 60 * 60 * 1000)

            WindowsPluginWorkerBundle.cleanupStale(root, keep = current, now = now)
            assertTrue(current.isDirectory)
            assertTrue(recent.isDirectory)
            assertFalse(stale.exists())
            assertTrue(stray.isFile)

            WindowsPluginWorkerBundle.remove(current)
            assertFalse(current.exists())
            assertTrue(recent.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
