/*
 * Copyright (C) 2026 Nanas
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.nanzmusify.nanas.data

import com.nanzmusify.nanas.data.db.DownloadDao
import com.nanzmusify.nanas.data.db.DownloadEntity
import com.nanzmusify.nanas.data.db.DownloadState
import com.nanzmusify.nanas.data.db.HistoryEntity
import com.nanzmusify.nanas.data.db.LibraryDao
import com.nanzmusify.nanas.data.db.LikedTrackEntity
import com.nanzmusify.nanas.data.db.PlaylistEntity
import com.nanzmusify.nanas.data.db.PlaylistItemEntity
import com.nanzmusify.nanas.data.model.NanzMusifyTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository(
    private val libraryDao: LibraryDao,
    private val downloadDao: DownloadDao,
) {

    // ---- Liked ----
    val likedTracks: Flow<List<NanzMusifyTrack>> =
        libraryDao.likedTracks().map { list -> list.map { it.toTrack() } }

    val likedIds: Flow<Set<String>> =
        libraryDao.likedIds().map { it.toSet() }

    fun isLiked(videoId: String): Flow<Boolean> = libraryDao.isLiked(videoId)

    /** @return true bila setelah aksi ini lagu menjadi disukai. */
    suspend fun toggleLike(track: NanzMusifyTrack): Boolean {
        return if (libraryDao.isLikedOnce(track.videoId)) {
            libraryDao.unlike(track.videoId)
            false
        } else {
            like(track)
            true
        }
    }

    suspend fun like(track: NanzMusifyTrack) {
        libraryDao.like(
            LikedTrackEntity(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                thumbnailUrl = track.thumbnailUrl,
                durationSec = track.durationSec,
                likedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun unlike(videoId: String) = libraryDao.unlike(videoId)

    // ---- History ----
    val history: Flow<List<NanzMusifyTrack>> =
        libraryDao.history(100).map { list -> list.map { it.toTrack() } }

    val mostPlayed: Flow<List<NanzMusifyTrack>> =
        libraryDao.mostPlayed(20).map { list -> list.map { it.toTrack() } }

    suspend fun recordPlay(track: NanzMusifyTrack) {
        val existing = libraryDao.historyEntry(track.videoId)
        libraryDao.upsertHistory(
            HistoryEntity(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                thumbnailUrl = track.thumbnailUrl,
                durationSec = track.durationSec,
                lastPlayedAt = System.currentTimeMillis(),
                playCount = (existing?.playCount ?: 0) + 1,
            ),
        )
        libraryDao.trimHistory()
    }

    suspend fun clearHistory() = libraryDao.clearHistory()

    // ---- Playlists ----
    data class PlaylistSummary(
        val id: Long,
        val name: String,
        val createdAt: Long,
        val itemCount: Int,
        val coverUrl: String,
    )

    val playlists: Flow<List<PlaylistEntity>> = libraryDao.playlists()

    val playlistsWithStats: Flow<List<com.nanzmusify.nanas.data.db.PlaylistWithStats>> =
        libraryDao.playlistsWithStats()

    fun playlistItems(playlistId: Long): Flow<List<NanzMusifyTrack>> =
        libraryDao.playlistItems(playlistId).map { list -> list.map { it.toTrack() } }

    suspend fun createPlaylist(name: String): Long =
        libraryDao.insertPlaylist(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))

    suspend fun renamePlaylist(id: Long, name: String) = libraryDao.renamePlaylist(id, name)

    suspend fun deletePlaylist(playlist: PlaylistEntity) {
        libraryDao.clearPlaylist(playlist.id)
        libraryDao.deletePlaylist(playlist)
    }

    suspend fun addToPlaylist(playlistId: Long, track: NanzMusifyTrack) {
        val size = libraryDao.playlistSize(playlistId)
        libraryDao.insertPlaylistItem(
            PlaylistItemEntity(
                playlistId = playlistId,
                videoId = track.videoId,
                position = size,
                title = track.title,
                artist = track.artist,
                album = track.album,
                thumbnailUrl = track.thumbnailUrl,
                durationSec = track.durationSec,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeFromPlaylist(playlistId: Long, videoId: String) {
        libraryDao.removePlaylistItem(playlistId, videoId)
        // rapikan posisi
        val items = libraryDao.playlistItemsOnce(playlistId)
        items.forEachIndexed { index, item ->
            if (item.position != index) {
                libraryDao.insertPlaylistItem(item.copy(position = index))
            }
        }
    }

    suspend fun moveInPlaylist(playlistId: Long, from: Int, to: Int) {
        val items = libraryDao.playlistItemsOnce(playlistId).toMutableList()
        if (from !in items.indices || to !in items.indices) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        items.forEachIndexed { index, item ->
            if (item.position != index) {
                libraryDao.insertPlaylistItem(item.copy(position = index))
            }
        }
    }

    // ---- Downloads (data access; mesin unduh di download/NanzMusifyDownloadManager) ----
    val downloads: Flow<List<DownloadEntity>> = downloadDao.all()

    fun downloadState(videoId: String): Flow<DownloadEntity?> = downloadDao.byId(videoId)

    val downloadedIds: Flow<Set<String>> = downloadDao.all().map { list ->
        list.filter { it.state == DownloadState.DONE }.map { it.videoId }.toSet()
    }

    // ---- Kategori offline (untuk widget "NanzMusify Offline") ----
    // Sengaja dihitung dari data yang benar-benar tersedia offline (tabel
    // `downloads` dengan state DONE), bukan playlist YouTube mentah — supaya
    // widget tidak pernah menawarkan lagu yang tidak bisa diputar tanpa jaringan.
    data class OfflineCategory(val id: String, val label: String, val trackCount: Int)

    suspend fun offlineCategories(): List<OfflineCategory> {
        val done = downloadDao.doneOnce()
        if (done.isEmpty()) return emptyList()
        val doneIds = done.map { it.videoId }.toSet()
        val result = mutableListOf<OfflineCategory>()
        result += OfflineCategory(OFFLINE_ALL, "Semua Offline", done.size)
        val likedIds = libraryDao.likedIdsOnce().toSet()
        val likedCount = doneIds.count { it in likedIds }
        if (likedCount > 0) result += OfflineCategory(OFFLINE_LIKED, "Favorit", likedCount)
        libraryDao.playlistsOnce().forEach { pl ->
            val items = libraryDao.playlistItemsOnce(pl.id)
            val count = items.count { it.videoId in doneIds }
            if (count > 0) result += OfflineCategory("$OFFLINE_PLAYLIST_PREFIX${pl.id}", pl.name, count)
        }
        return result
    }

    suspend fun offlineTracksFor(categoryId: String): List<NanzMusifyTrack> {
        val done = downloadDao.doneOnce()
        return when {
            categoryId == OFFLINE_ALL -> done.map { it.toTrack() }
            categoryId == OFFLINE_LIKED -> {
                val likedIds = libraryDao.likedIdsOnce().toSet()
                done.filter { it.videoId in likedIds }.map { it.toTrack() }
            }
            categoryId.startsWith(OFFLINE_PLAYLIST_PREFIX) -> {
                val id = categoryId.removePrefix(OFFLINE_PLAYLIST_PREFIX).toLongOrNull()
                    ?: return emptyList()
                val doneById = done.associateBy { it.videoId }
                libraryDao.playlistItemsOnce(id).mapNotNull { doneById[it.videoId] }.map { it.toTrack() }
            }
            else -> emptyList()
        }
    }

    companion object {
        const val OFFLINE_ALL = "all"
        const val OFFLINE_LIKED = "liked"
        const val OFFLINE_PLAYLIST_PREFIX = "playlist:"
    }

    // ---- Mappers ----
    private fun LikedTrackEntity.toTrack() =
        NanzMusifyTrack(videoId, title, artist, album, durationSec, thumbnailUrl)

    private fun HistoryEntity.toTrack() =
        NanzMusifyTrack(videoId, title, artist, album, durationSec, thumbnailUrl)

    private fun PlaylistItemEntity.toTrack() =
        NanzMusifyTrack(videoId, title, artist, album, durationSec, thumbnailUrl)

    private fun DownloadEntity.toTrack() =
        NanzMusifyTrack(videoId, title, artist, album, durationSec, thumbnailUrl)
}
