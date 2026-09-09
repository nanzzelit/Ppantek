/*
 * Copyright (C) 2026 Nanas
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.nanzmusify.nanas.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.nanzmusify.nanas.data.model.NanzMusifyTrack
import com.nanzmusify.nanas.local.LocalMusicRepository

/**
 * Bangun [MediaItem] media3 dari [NanzMusifyTrack] — satu-satunya jalur, dipakai
 * baik oleh [PlayerManager] (kendali dari UI) maupun [PlaybackService] (kendali
 * langsung dari widget "NanzMusify Offline" saat app belum dibuka sama sekali).
 */
fun NanzMusifyTrack.toPlayableMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(videoId)
        .setUri(
            // Lagu lokal: file di penyimpanan perangkat (diputar langsung via
            // DefaultDataSource). Lagu YouTube (termasuk yang sudah diunduh):
            // skema nanzmusify://audio — ResolvingDataSource yang memutuskan
            // file unduhan lokal atau stream jaringan.
            if (isLocal) {
                LocalMusicRepository.uriForVideoId(videoId) ?: ResolvingDataSource.uriOf(videoId)
            } else {
                ResolvingDataSource.uriOf(videoId)
            },
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album.ifBlank { null })
                .setArtworkUri(thumbnailUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()
