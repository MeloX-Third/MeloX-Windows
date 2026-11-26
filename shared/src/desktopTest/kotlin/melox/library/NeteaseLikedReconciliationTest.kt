package melox.library

import kotlin.test.*

class NeteaseLikedReconciliationTest {
    private fun playlist(id: Long, trackCount: Int, specialType: Int? = null) =
        NeteasePlaylistSummary(id, "Playlist $id", null, trackCount, "Me", 42, specialType = specialType)

    private fun song(id: Long) = melox.model.SearchSong(id, "Song $id", "Artist", "Album", null)

    private fun snapshot(
        playlists: List<NeteasePlaylistSummary>,
        likedSongs: List<melox.model.SearchSong>,
        likedPlaylistId: Long? = playlists.firstOrNull()?.id,
    ) = NeteaseLibrarySnapshot(playlists, likedSongs, emptyList(), likedPlaylistId)

    @Test fun backfillTriggersWhenLikeEndpointIsShortOfPlaylistCount() {
        assertTrue(needsLikedPlaylistBackfill(likedTrackCount = 660, likeIds = (1L..659L).toList(), resolvedDetails = 659))
    }

    @Test fun backfillTriggersWhenSongDetailDropsAnId() {
        assertTrue(needsLikedPlaylistBackfill(likedTrackCount = 660, likeIds = (1L..660L).toList(), resolvedDetails = 659))
    }

    @Test fun backfillStaysOffForHealthySnapshotOrMissingCount() {
        assertFalse(needsLikedPlaylistBackfill(likedTrackCount = 660, likeIds = (1L..660L).toList(), resolvedDetails = 660))
        assertFalse(needsLikedPlaylistBackfill(likedTrackCount = 0, likeIds = (1L..5L).toList(), resolvedDetails = 5))
    }

    @Test fun mergeKeepsPlaylistOrderAndAppendsLikeOnlyIds() {
        assertEquals(listOf(3L, 1L, 2L, 9L), mergeLikedSongIds(likeIds = listOf(1, 2, 9), playlistIds = listOf(3, 1, 2)))
        assertEquals(listOf(1L, 2L), mergeLikedSongIds(likeIds = listOf(1, 2), playlistIds = emptyList()))
    }

    @Test fun transientShortSnapshotKeepsCachedFavorites() {
        val cached = snapshot(listOf(playlist(7, 3, specialType = 5)), listOf(song(1), song(2), song(3)))
        val fresh = snapshot(listOf(playlist(7, 3, specialType = 5)), listOf(song(1), song(2)))
        assertEquals(listOf(1L, 2L, 3L), reconcileLikedSongs(fresh, cached).likedSongs.map { it.id })
    }

    @Test fun realUnlikesAreNotRevivedWhenServerCountShrinks() {
        val cached = snapshot(listOf(playlist(7, 3, specialType = 5)), listOf(song(1), song(2), song(3)))
        val fresh = snapshot(listOf(playlist(7, 2, specialType = 5)), listOf(song(1), song(2)))
        assertEquals(listOf(1L, 2L), reconcileLikedSongs(fresh, cached).likedSongs.map { it.id })
    }

    @Test fun missingServerCountOrNoBaselineUsesFreshResult() {
        val cached = snapshot(listOf(playlist(7, 0)), listOf(song(1), song(2)))
        val fresh = snapshot(listOf(playlist(7, 0)), listOf(song(1)))
        assertEquals(listOf(1L), reconcileLikedSongs(fresh, cached).likedSongs.map { it.id })
        assertEquals(listOf(1L), reconcileLikedSongs(fresh, null).likedSongs.map { it.id })
    }

    @Test fun systemPlaylistIsPreferredOverListOrderAndFallsBackToStoredId() {
        val reordered = snapshot(listOf(playlist(9, 0), playlist(7, 2, specialType = 5)), listOf(song(1)))
        val cached = snapshot(listOf(playlist(9, 0)), listOf(song(1), song(2)))
        assertEquals(listOf(1L, 2L), reconcileLikedSongs(reordered, cached).likedSongs.map { it.id })

        val legacy = snapshot(listOf(playlist(9, 0), playlist(7, 2)), listOf(song(1)), likedPlaylistId = 7)
        assertEquals(listOf(1L, 2L), reconcileLikedSongs(legacy, cached).likedSongs.map { it.id })
    }

    @Test fun playlistDetailRestoresCachedTracksOnlyWhileServerCountProvesGap() {
        val summary = NeteasePlaylistSummary(7, "Playlist", null, 3, "Me")
        val cached = NeteasePlaylistDetail(summary, listOf(song(1), song(2), song(3)))
        val fresh = NeteasePlaylistDetail(summary, listOf(song(1), song(2)))
        assertEquals(listOf(1L, 2L, 3L), reconcilePlaylistDetail(fresh, cached).songs.map { it.id })

        val shrunk = NeteasePlaylistDetail(summary.copy(trackCount = 2), listOf(song(1), song(2)))
        assertEquals(listOf(1L, 2L), reconcilePlaylistDetail(shrunk, cached).songs.map { it.id })
        assertEquals(listOf(1L), reconcilePlaylistDetail(NeteasePlaylistDetail(summary.copy(trackCount = 1), listOf(song(1))), null)
            .songs.map { it.id })
    }
}
