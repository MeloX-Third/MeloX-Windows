package melox.autosync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import melox.lyrics.LrcLyricsParser

class AutoSyncTimelineLanguageTest {
    @Test fun removingALanguageModelDeletesOnlyItsTimelines() {
        val root = Files.createTempDirectory("melox-autosync-language-test-").toFile()
        try {
            val store = AutoSyncTimelineStore(root)
            val document = LrcLyricsParser.parse("[00:01.00]Hello world")
            val source = AutoSyncTimelineStore.sourceHash(document)
            val units = mapOf(0 to listOf(AutoSyncUnit("Hello", 1_100, 1_400)))
            store.save("japanese", source, "audio", "models-ja", units, listOf("ja"))
            store.save("chinese", source, "audio", "models-zh", units, listOf("zh"))
            store.save("mixed", source, "audio", "models-mixed", units, listOf("en", "ja"))

            store.clearLanguage("en")
            assertNotNull(store.load("japanese", source, "audio", "models-ja"))
            assertNotNull(store.load("chinese", source, "audio", "models-zh"))
            assertNull(store.load("mixed", source, "audio", "models-mixed"))

            store.save("mixed", source, "audio", "models-mixed", units, listOf("en", "ja"))
            store.clearLanguage("ja")
            assertNull(store.load("japanese", source, "audio", "models-ja"))
            assertNull(store.load("mixed", source, "audio", "models-mixed"))
            assertNotNull(store.load("chinese", source, "audio", "models-zh"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun legacyTimelinesWithoutLanguageMetadataAreKept() {
        val root = Files.createTempDirectory("melox-autosync-legacy-test-").toFile()
        try {
            val store = AutoSyncTimelineStore(root)
            val document = LrcLyricsParser.parse("[00:01.00]Hello world")
            val source = AutoSyncTimelineStore.sourceHash(document)
            val units = mapOf(0 to listOf(AutoSyncUnit("Hello", 1_100, 1_400)))
            store.save("legacy", source, "audio", "models", units)
            store.clearLanguage("ja")
            assertNotNull(store.load("legacy", source, "audio", "models"))
        } finally {
            root.deleteRecursively()
        }
    }
}
