package melox.dailyhistory

import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import melox.model.SearchSong
import kotlin.test.*

class DailyHistoryStoreTest {
    @Test fun archiveSurvivesReloadOverwritesAndKeepsAccountsSeparate() = runBlocking {
        val root = Files.createTempDirectory("melox-daily-history").toFile()
        try {
            val store = DailyHistoryStore(root)
            val account = DailyHistoryStore.accountKey("account-a")
            val other = DailyHistoryStore.accountKey("account-b")
            val song = SearchSong(12, "title", "artist", "album", "https://example.com/art", 42000,
                albumId = 34, artistId = 56)
            assertTrue(store.archive(account, "2026-11-25", listOf(song)))
            assertEquals(listOf(song), DailyHistoryStore(root).read(account, "2026-11-25")?.songs)
            assertNull(store.read(other, "2026-11-25"))
            assertTrue(store.archive(account, "2026-11-25", listOf(song.copy(name = "updated"))))
            assertFalse(store.archive(account, "2026-11-25", emptyList()))
            assertEquals("updated", store.read(account, "2026-11-25")?.songs?.single()?.name)
            assertEquals(listOf("2026-11-25"), store.listDates(account))
            assertFalse(root.walk().any { it.extension == "tmp" })
        } finally { root.deleteRecursively() }
    }

    @Test fun retentionKeepsNewestThirtyRecordsAndRejectsInvalidDates() = runBlocking {
        val root = Files.createTempDirectory("melox-daily-retention").toFile()
        try {
            val store = DailyHistoryStore(root)
            val account = DailyHistoryStore.accountKey("cookie")
            val songs = listOf(SearchSong(1, "one", "", "", null))
            val start = LocalDate.of(2026, 10, 1)
            repeat(35) { assertTrue(store.archive(account, start.plusDays(it.toLong()).toString(), songs)) }
            assertEquals(30, store.listDates(account).size)
            assertEquals(start.plusDays(34).toString(), store.listDates(account).first())
            assertNull(store.read(account, start.toString()))
            assertFalse(store.archive(account, "2026-02-30", songs))
            assertNull(store.read(account, "../../outside"))
            java.io.File(root, "$account/${start.plusDays(34)}.json").writeText("broken")
            assertNull(store.read(account, start.plusDays(34).toString()))
        } finally { root.deleteRecursively() }
    }
}
