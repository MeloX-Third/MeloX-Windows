package melox.recommendation

import melox.music.model.SourceResourceUri
import melox.music.provider.AggregatedTrack

/** Merge source-owned behavior only when the aggregation corpus contains that exact resource. */
internal fun recommendationTrainingFeatures(groups: List<AggregatedTrack>, existing: List<LocalTrackFeatures>): List<LocalTrackFeatures> {
    val consumed = mutableSetOf<String>()
    val imported = groups.mapNotNull { group ->
        val candidate = group.recommendation ?: return@mapNotNull null
        val key = "${group.key.title}|${group.key.artist}|${group.key.version}"
        val identities = group.candidates.mapTo(hashSetOf()) {
            SourceResourceUri.encode(it.track.id.sourceId, it.track.id.value)
        }
        val behavior = existing.filter { it.trackKey in identities && it.trackKey !in consumed }
        val old = existing.firstOrNull { it.trackKey == key && it.trackKey !in consumed }
        val samples = listOfNotNull(old) + behavior
        consumed += samples.map(LocalTrackFeatures::trackKey)
        LocalTrackFeatures(key, candidate.track.title, candidate.track.artistText,
            playCount = samples.sumOf { it.playCount }, completedCount = samples.sumOf { it.completedCount },
            skipCount = samples.sumOf { it.skipCount }, liked = old?.liked ?: true,
            lastPlayedAtMs = samples.maxOfOrNull { it.lastPlayedAtMs } ?: 0L,
            sourceCount = group.candidates.size, versionKind = group.key.version.name,
            qualityPreference = candidate.totalScore / 100f)
    }
    // History outside the current library remains training input, including unavailable plugins.
    return (existing.filter { it.trackKey !in consumed } + imported).takeLast(2_000)
}
