package melox.library

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class NeteaseLibraryCacheRetentionTest {
    @Test fun freshDiskCacheSkipsAutomaticRefreshAndHashCollisionsStaySeparate() = runBlocking {
        val root = Files.createTempDirectory("library-cache-freshness").toFile()
        try {
            val cache = NeteaseLibraryCache(root)
            val id = System.nanoTime()
            cache.saveSnapshot(id, NeteaseLibrarySnapshot(emptyList(), emptyList(), emptyList()))
            assertFalse(NeteaseLibraryCache.beginLibraryColdStartRefresh(id, root))
            val staleId = id + 1
            cache.saveSnapshot(staleId, NeteaseLibrarySnapshot(emptyList(), emptyList(), emptyList()))
            root.resolve("library_$staleId.json").setLastModified(System.currentTimeMillis() - 31L * 60 * 1000)
            assertTrue(NeteaseLibraryCache.beginLibraryColdStartRefresh(staleId, root))
            assertFalse(NeteaseLibraryCache.beginLibraryColdStartRefresh(staleId, root))
            assertEquals("Aa".hashCode(), "BB".hashCode())
            val first = listOf(NeteasePlaylistSummary(1, "First", null, 0, ""))
            val second = listOf(NeteasePlaylistSummary(2, "Second", null, 0, ""))
            cache.saveExplore("Aa", first)
            cache.saveExplore("BB", second)
            assertEquals(first, cache.loadExplore("Aa"))
            assertEquals(second, cache.loadExplore("BB"))
        } finally { root.deleteRecursively() }
    }

    @Test fun browsingCannotEvictFavoritesAndPlaylistsHaveIndependentQuota() = runBlocking {
        val root = Files.createTempDirectory("library-cache-retention").toFile()
        try {
            val cache = NeteaseLibraryCache(root)
            val snapshot = NeteaseLibrarySnapshot(listOf(NeteasePlaylistSummary(42, "Liked", null, 0, "", specialType = 5)),
                emptyList(), emptyList(), 42)
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
