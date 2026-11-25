package melox.lyrics

import kotlin.test.*

class LyricTranslationPolicyTest {
    private val chinese = MeloXLyricPreference(null, MeloXLyricScript.Simplified)
    private val english = MeloXLyricPreference("en", MeloXLyricScript.Original)

    @Test fun automaticLoaderFillsTranslationFromCompletedAlternative() = kotlinx.coroutines.test.runTest {
        val previous = melox.settings.MeloXSettingsRuntime.automaticLyricSelectionEnabled
        melox.settings.MeloXSettingsRuntime.automaticLyricSelectionEnabled = true
        try {
            val main = LyricsDocument(listOf(LyricLine(1000, text = "こんにちは世界",
                syllables = listOf(LyricSyllable("こんにちは世界", 1000, 2000)))), LyricSource.Netease)
            val loader = MeloXLyricsLoad(object : LyricDocumentSources() {
                override suspend fun provider(track: melox.music.model.MusicTrack,
                    registry: melox.music.provider.MusicProviderRegistry?, preference: MeloXLyricPreference) = main
                override suspend fun amll(track: melox.music.model.MusicTrack,
                    registry: melox.music.provider.MusicProviderRegistry?, preference: MeloXLyricPreference) = LyricsDocument(emptyList())
                override suspend fun matchedQq(track: melox.music.model.MusicTrack,
                    registry: melox.music.provider.MusicProviderRegistry?) = main.copy(lines = main.lines.map { it.copy(translation = "Hello world") })
            }, backgroundScope)
            val track = melox.music.model.MusicTrack(melox.music.model.MusicResourceId(melox.music.model.MusicSource.Netease, "translation-merge"), "Song", emptyList())
            val result = loader.load(track, preference = english)
            assertEquals("Hello world", result.lines.single().translation)
            assertEquals(main.lines.single().syllables, result.lines.single().syllables)
        } finally { melox.settings.MeloXSettingsRuntime.automaticLyricSelectionEnabled = previous }
    }

    @Test fun translationSelectionUsesLanguageAcrossSources() {
        assertTrue(LyricTranslationPolicy.translationMatches(listOf("Merhaba dünya"),
            MeloXLyricPreference("tr", MeloXLyricScript.Original)))
        val main = LyricsDocument(listOf(LyricLine(1000, text = "Hello world", translation = "你好世界")), source = LyricSource.Netease)
        assertNull(applyLyricTranslationPreference(main, english).lines.single().translation)
        assertEquals("你好世界", applyLyricTranslationPreference(main, chinese).lines.single().translation)
        val japanese = main.copy(lines = listOf(LyricLine(1000, text = "こんにちは世界", translation = "Hello world")))
        assertEquals("Hello world", applyLyricTranslationPreference(japanese, english).lines.single().translation)
        assertNull(applyLyricTranslationPreference(japanese, chinese).lines.single().translation)
        val han = main.copy(lines = listOf(LyricLine(1000, text = "音樂世界", translation = "重复译文")))
        val converted = applyLyricTranslationPreference(han, chinese).lines.single()
        assertEquals("音乐世界", converted.text)
        assertNull(converted.translation)
    }

    @Test fun mergingToleratesMasterOffsetAndPreservesWordTimingAndExistingTranslations() {
        val lines = (0 until 5).map { LyricLine(it * 4000L, text = "Main line $it") }
        val main = LyricsDocument(lines)
        val alternate = LyricsDocument(lines.map { it.copy(timeMs = it.timeMs + 250, translation = "译文 ${it.timeMs}") })
        val merged = assertNotNull(LyricTranslationPolicy.combineTranslations(main, alternate))
        assertEquals(lines.map { it.timeMs }, merged.lines.map { it.timeMs })
        assertEquals(lines.map { it.text }, merged.lines.map { it.text })
        assertTrue(merged.lines.all { it.translation != null })
        val existing = main.copy(lines = lines.map { it.copy(translation = "Keep") })
        assertNull(LyricTranslationPolicy.combineTranslations(existing, alternate))
        val unrelated = alternate.copy(lines = alternate.lines.map { it.copy(timeMs = it.timeMs + 100000, text = "Unrelated") })
        assertNull(LyricTranslationPolicy.combineTranslations(main, unrelated))
    }
}
