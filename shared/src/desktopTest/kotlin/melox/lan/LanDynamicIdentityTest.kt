package melox.lan

import melox.music.model.SourceId
import kotlin.test.*

class LanDynamicIdentityTest {
    @Test fun pluginQueueIdentitySurvivesWireAndLegacyUiRoundTrip(): Unit {
        val track = LanTrack("plugin:example.lan", "id:%/中文", "Title", "Artist", "Album", durationMs = 1234)
        val song = MeloXLanRuntime.song(LanTrack.parse(track.json()))
        assertEquals(SourceId.parse(track.source), song.providerTrack?.id?.sourceId)
        assertEquals(track.id, song.providerTrack?.id?.value)
        assertEquals(track, MeloXLanRuntime.fromSong(song))
        assertFails { MeloXLanRuntime.song(track.copy(source = "plugin:invalid")) }
        assertFails { MeloXLanRuntime.song(track.copy(source = "apple_music")) }
        assertEquals("qq_music", MeloXLanRuntime.song(track.copy(source = "qq")).providerTrack?.id?.sourceId?.value)
    }
}
