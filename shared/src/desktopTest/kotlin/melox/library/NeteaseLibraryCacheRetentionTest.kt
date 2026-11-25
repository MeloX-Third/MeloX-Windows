package melox.library

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class NeteaseLibraryCacheRetentionTest {
    @Test fun browsingCannotEvictFavoritesAndPlaylistsHaveIndependentQuota() = runBlocking {
        val root = Files.createTempDirectory("library-cache-retention").toFile()
        try {
            val cache = NeteaseLibraryCache(root)
            val snapshot = NeteaseLibrarySnapshot(emptyList(), emptyList(), emptyList(), 42)
            cache.saveSnapshot(7, snapshot)
            root.resolve("library_7.json").setLastModified(1)
            repeat(55) { index -> root.resolve("playlist_$index.json").apply { writeText("{}"); setLastModified(100L + index) } }
            repeat(140) { index -> root.resolve("home_$index.json").apply { writeText("{}"); setLastModified(1000L + index) } }
            cache.pruneCache()
            assertTrue(root.resolve("library_7.json").isFile)
            assertEquals(48, root.listFiles()!!.count { it.name.startsWith("playlist_") })
            assertEquals(128, root.listFiles()!!.count { it.name.startsWith("home_") })
            root.resolve("library_7.json").setLastModified(System.currentTimeMillis())
            assertEquals(snapshot, cache.loadSnapshot(7))
        } finally { root.deleteRecursively() }
    }
}
