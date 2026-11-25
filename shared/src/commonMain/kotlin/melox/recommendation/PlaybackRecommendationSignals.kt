package melox.recommendation

import melox.music.model.MusicTrack
import melox.music.model.SourceResourceUri

/** Signals follow audible playback, not queue selection or URL resolution. */
internal class PlaybackRecommendationSignals(private val record: (MusicTrack, Boolean, Boolean) -> Unit) {
    private var identity: String? = null
    private var started = false
    private var completed = false

    fun sample(track: MusicTrack?, playing: Boolean, positionMs: Long, durationMs: Long) {
        select(track)
        if (track == null || !playing) return
        if (!started) { started = true; record(track, false, false) }
        if (!completed && durationMs > 0 && positionMs >= (durationMs * .85).toLong()) {
            completed = true
            record(track, true, false)
        }
    }

    fun skip(track: MusicTrack?, positionMs: Long) {
        select(track)
        if (track != null && started && !completed && positionMs < 30_000) record(track, false, true)
    }

    private fun select(track: MusicTrack?) {
        val key = track?.let { SourceResourceUri.encode(it.id.sourceId, it.id.value) }
        if (key != identity) { identity = key; started = false; completed = false }
    }
}
