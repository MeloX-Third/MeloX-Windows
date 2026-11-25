package melox.playback

import melox.music.model.*
import kotlin.test.*

class PlaybackDynamicIdentityTest {
    @Test fun dynamicIdentityRoundTripsWithoutBecomingNetease() {
        val id = MusicResourceId(SourceId.parse("plugin:example.catalog")!!, "123/歌曲%:😀")
        val encoded = PlaybackTrackIdentity.encode(id)
        assertEquals(id, PlaybackTrackIdentity.decode(encoded))
        assertEquals(id, PlaybackTrackIdentity.fromMediaItem(encoded))
        assertNull(PlaybackTrackIdentity.neteaseNumericId(encoded))
        val numeric = PlaybackTrackIdentity.encode(id.copy(value = "123"))
        assertNull(PlaybackTrackIdentity.neteaseNumericId(numeric))
        assertNull(PlaybackTrackIdentity.decode(encoded.replace("%2F", "%2f")))
        assertNull(PlaybackTrackIdentity.decode("melox:plugin:example.catalog:123"))
    }

    @Test fun existingWindowsQueueIdsRemainCompatible() {
        assertEquals(123L, PlaybackTrackIdentity.neteaseNumericId("123"))
        assertEquals(MusicResourceId(MusicSource.QQMusic, "abc:def/100%"),
            PlaybackTrackIdentity.decode("melox:qq_music:abc:def/100%"))
        MusicSource.entries.forEach { source ->
            val id = MusicResourceId(source, "abc:def/100%")
            assertEquals("melox:${source.storageValue}:${id.value}", PlaybackTrackIdentity.encode(id))
            assertEquals(id, PlaybackTrackIdentity.decode(PlaybackTrackIdentity.encode(id)))
        }
        assertNull(PlaybackTrackIdentity.decode("melox:future_provider:123"))
    }
}
