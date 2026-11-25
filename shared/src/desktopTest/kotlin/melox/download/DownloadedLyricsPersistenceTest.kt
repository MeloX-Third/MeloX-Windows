package melox.download

import melox.lyrics.LrcLyricsParser
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadedLyricsPersistenceTest {
    @Test fun synchronizedLyricsRetainExplicitPseudoTimingPolicy() {
        val store = MeloXDownloadStore.instance()
        val timed = LrcLyricsParser.parse("[00:01.00]Timed line")
        for (allowed in listOf(true, false)) {
            val original = timed.copy(pseudoTimingAllowed = allowed)
            val restored = store.decodeLyrics(JSONObject(store.encodeLyrics(original).toString()))
            assertEquals(allowed, restored.pseudoTimingAllowed)
            assertFalse(restored.isUnsynchronized)
            assertEquals(original.lines, restored.lines)
        }
    }
    @Test fun downloadedPlainLyricsRemainUntimedAfterJsonRoundTripAndOldFilesRemainReadable() {
        val store = MeloXDownloadStore.instance()
        val plain = LrcLyricsParser.parse("First line\nSecond line")
        val reopened = store.decodeLyrics(JSONObject(store.encodeLyrics(plain).toString()))
        assertTrue(reopened.isUnsynchronized)
        assertFalse(reopened.pseudoTimingAllowed)
        assertNull(reopened.highlightedIndex(10_000))
        assertEquals(listOf("First line", "Second line"), reopened.lines.map { it.text })

        val old = store.decodeLyrics(JSONObject("""{"lines":[{"timeMs":1000,"text":"timed"}]}"""))
        assertFalse(old.isUnsynchronized)
        assertEquals(0, old.highlightedIndex(1_000))
    }
}
