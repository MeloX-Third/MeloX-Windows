package melox.playback

import melox.music.model.*
import kotlin.test.*

class LxDynamicSourceBoundaryTest {
    @Test fun dynamicTrackDoesNotEnterBuiltInLxCompatibilityRoutes() {
        val resolver = LxUserPlaybackResolver()
        val track = MusicTrack(MusicResourceId(SourceId.parse("plugin:test.lx.boundary")!!, "123"), "Song", emptyList())
        assertNull(resolver.resolve(track, AudioQualityTier.Standard))
        assertNull(resolver.resolveArtwork(track))
        assertNull(resolver.resolveLyrics(track))
    }
}
