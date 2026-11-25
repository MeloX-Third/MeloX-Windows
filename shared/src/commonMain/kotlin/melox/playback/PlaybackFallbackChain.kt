package melox.playback

import melox.music.model.*
import kotlinx.coroutines.CancellationException

internal data class PlaybackFallbackResolution(
    val url: String, val requestHeaders: Map<String, String>, val expiresAtEpochMs: Long?,
    val cacheIdentity: String, val crossProvider: CrossProviderFallbackResult?,
)

internal class PlaybackFallbackChain(
    private val crossProvider: CrossProviderPlaybackFallbackResolver?,
    private val thirdParty: ThirdPartyPlaybackResolver?,
) {
    fun cacheIdentity() = "${crossProvider?.cacheIdentity().orEmpty()}:${thirdParty?.cacheIdentity().orEmpty()}"
    fun resolve(track: MusicTrack, quality: AudioQualityTier, membershipFailure: Boolean): PlaybackFallbackResolution? {
        val source = track.id.sourceId.builtIn ?: return null
        if (source == MusicSource.Local || source == MusicSource.Jellyfin) return null
        val cross = runCatching {
            crossProvider?.resolve(CrossProviderFallbackRequest(track.id.value.toLongOrNull() ?: 0L,
                track.title, track.artistText, track.durationMs, quality, source, track.id.value))
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        if (cross != null) return PlaybackFallbackResolution(cross.url, cross.requestHeaders, cross.expiresAtEpochMs,
            "${cross.source.storageValue}:${cross.resourceId}", cross)
        val third = thirdParty?.resolve(track, quality, membershipFailure) ?: return null
        return PlaybackFallbackResolution(third.url, third.requestHeaders, null, "third:${third.sourceLabel}", null)
    }
}
