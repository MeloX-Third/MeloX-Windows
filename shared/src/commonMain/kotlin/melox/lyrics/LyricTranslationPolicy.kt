package melox.lyrics

internal enum class LyricTranslationDecision { KeepMainOnly, ConvertScriptOnly, NeedTranslation }

/** Language and translation-only timeline policy from Android edc829e. */
internal object LyricTranslationPolicy {
    const val COMBINE_TOLERANCE_MS = 120L
    const val COMBINE_MIN_MATCH_RATIO = 0.6

    fun detectLanguage(texts: List<String>): String? {
        val joined = texts.joinToString(" ")
        val kana = Regex("[\\u3040-\\u30FF]").findAll(joined).count()
        val hangul = Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF]").findAll(joined).count()
        val han = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF]").findAll(joined).count()
        val latin = Regex("[A-Za-z]").findAll(joined).count()
        return when {
            kana >= 2 && kana * 4 >= kana + han -> "ja"
            hangul >= 2 && hangul >= latin -> "ko"
            han >= 2 -> "zh"
            latin >= 3 -> "en"
            else -> null
        }
    }

    fun detectLanguage(document: LyricsDocument): String? = detectLanguage(document.lines.map { it.text })
    fun targetLanguage(preference: MeloXLyricPreference): String = preference.languageTag ?: "zh"
    fun decide(mainLanguage: String?, target: MeloXLyricPreference): LyricTranslationDecision = when {
        mainLanguage == null -> LyricTranslationDecision.NeedTranslation
        mainLanguage == "zh" && targetLanguage(target) == "zh" -> LyricTranslationDecision.ConvertScriptOnly
        mainLanguage == targetLanguage(target) -> LyricTranslationDecision.KeepMainOnly
        else -> LyricTranslationDecision.NeedTranslation
    }

    fun translationMatches(translations: List<String>, target: MeloXLyricPreference): Boolean {
        val texts = translations.filter(String::isNotBlank)
        if (texts.isEmpty()) return false
        val detected = detectLanguage(texts) ?: return true
        val wanted = targetLanguage(target)
        // Latin script alone cannot distinguish English from Turkish or other
        // explicitly requested Latin languages. Preserve the provider-selected track.
        if (detected == "en" && wanted !in setOf("zh", "ja", "ko")) return true
        return detected == wanted
    }

    fun withoutTranslations(document: LyricsDocument): LyricsDocument = document.copy(lines = document.lines.map {
        it.copy(translation = null, accompaniment = it.accompaniment.map { vocal -> vocal.copy(translation = null) })
    })

    fun combineTranslations(main: LyricsDocument, alternative: LyricsDocument,
        toleranceMs: Long = COMBINE_TOLERANCE_MS): LyricsDocument? {
        if (main.lines.isEmpty() || alternative.lines.isEmpty()) return null
        val translated = alternative.lines.filter { !it.translation.isNullOrBlank() }
        if (translated.isEmpty()) return null
        val offset = estimateOffset(main.lines, translated)
        val adjusted = translated.map { it.copy(timeMs = it.timeMs - offset) }
        val partners = adjusted.associateBy { it.timeMs }
        val textPartners = translated.mapNotNull { line ->
            normalized(line.text).takeIf { it.length > 2 }?.let { it to line }
        }.toMap()
        var filled = 0
        val merged = main.lines.map { line ->
            if (!line.translation.isNullOrBlank() || line.text.isBlank()) return@map line
            val partner = partners[line.timeMs]
                ?: adjusted.minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
                    ?.takeIf { kotlin.math.abs(it.timeMs - line.timeMs) <= toleranceMs }
                ?: textPartners[normalized(line.text)]
            if (partner == null) line else {
                filled++
                line.copy(translation = partner.translation)
            }
        }
        val candidates = main.lines.count { it.text.isNotBlank() && it.translation.isNullOrBlank() }
        if (filled == 0 || candidates > 0 && filled.toFloat() / candidates < .3) return null
        return main.copy(lines = merged)
    }

    private fun normalized(text: String) = text.trim().replace(Regex("\\s+"), "")
        .replace(Regex("[，。！？、,.!?…~～]"), "")

    private fun estimateOffset(main: List<LyricLine>, translated: List<LyricLine>): Long {
        if (main.size < 4 || translated.size < 4) return 0
        val deltas = main.mapNotNull { line ->
            val nearest = translated.minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) } ?: return@mapNotNull null
            (nearest.timeMs - line.timeMs).takeIf { kotlin.math.abs(it) <= 1_500 }
        }.sorted()
        if (deltas.size < minOf(main.size, translated.size) / 3) return 0
        return deltas[deltas.size / 2]
    }
}
