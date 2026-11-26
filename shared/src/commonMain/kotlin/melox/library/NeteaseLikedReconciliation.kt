package melox.library

/**
 * Merges the playlist's own track ids into the like/get result.
 *
 * The playlist order is authoritative when the fallback request actually returned
 * tracks; like-only ids (server sync lag) are appended so nothing is lost.
 */
internal fun mergeLikedSongIds(likeIds: List<Long>, playlistIds: List<Long>): List<Long> {
    if (playlistIds.isEmpty()) return likeIds
    val known = playlistIds.toHashSet()
    return playlistIds + likeIds.filterNot { it in known }
}

/**
 * A backfill is only warranted when the system playlist reports more tracks than
 * like/get returned, or when song/detail silently dropped ids. A missing
 * trackCount (0) must not trigger an extra request.
 */
internal fun needsLikedPlaylistBackfill(
    likedTrackCount: Int,
    likeIds: List<Long>,
    resolvedDetails: Int,
): Boolean = likedTrackCount > likeIds.size || resolvedDetails < likeIds.size

/**
 * Keeps favorites visible when a transient short snapshot would otherwise erase
 * them. Mirrors Android edc829e `reconcileLikedSongs`: trust the fresh result
 * unless the server's own track count proves it is incomplete, and never revive
 * songs when the server itself reports a smaller total (real unlikes stay gone).
 */
fun reconcileLikedSongs(
    fresh: NeteaseLibrarySnapshot,
    cached: NeteaseLibrarySnapshot?,
): NeteaseLibrarySnapshot {
    val cachedSongs = cached?.likedSongs.orEmpty()
    if (cachedSongs.isEmpty() || fresh.likedSongs.size >= cachedSongs.size) return fresh
    val likedPlaylist = fresh.playlists.firstOrNull { it.specialType == 5 }
        ?: fresh.playlists.firstOrNull { it.id == fresh.likedPlaylistId }
    val serverCount = likedPlaylist?.trackCount
    if (serverCount == null || serverCount <= 0) return fresh
    if (serverCount <= fresh.likedSongs.size) return fresh
    val present = fresh.likedSongs.mapTo(HashSet(fresh.likedSongs.size)) { it.id }
    val missing = cachedSongs.filterNot { it.id in present }
    if (missing.isEmpty()) return fresh
    return fresh.copy(likedSongs = fresh.likedSongs + missing)
}

/**
 * Same guard for a playlist detail page: only restore cached tracks while the
 * server's trackCount still exceeds what the fresh page contains.
 */
fun reconcilePlaylistDetail(
    fresh: NeteasePlaylistDetail,
    cached: NeteasePlaylistDetail?,
): NeteasePlaylistDetail {
    val cachedSongs = cached?.songs.orEmpty()
    if (cachedSongs.isEmpty() || fresh.songs.size >= cachedSongs.size) return fresh
    if (fresh.summary.trackCount <= fresh.songs.size) return fresh
    val present = fresh.songs.mapTo(HashSet(fresh.songs.size)) { it.id }
    val missing = cachedSongs.filterNot { it.id in present }
    if (missing.isEmpty()) return fresh
    return fresh.copy(songs = fresh.songs + missing)
}
