package melox.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricFileEncodingTest {
    @Test fun chineseSidecarsDecodeUtf8AndLegacyChineseWithoutLosingCharacters() {
        val lyric = "[00:01.00]你好，音乐\n[00:02.00]𠀀和世界"
        val expected = LyricFileParser.parse(lyric)
        for (encoding in listOf(Charsets.UTF_8, charset("GB18030"))) {
            assertEquals(expected, LyricFileParser.parse(lyric.toByteArray(encoding)), encoding.name())
        }
        assertEquals(expected, LyricFileParser.parse(("\uFEFF" + lyric).toByteArray(Charsets.UTF_8)))
        val gbkLyric = "[00:01.00]你好，音乐"
        assertEquals(LyricFileParser.parse(gbkLyric), LyricFileParser.parse(gbkLyric.toByteArray(charset("GBK"))))
        assertEquals(LyricSource.Local, expected.source)
    }
}
