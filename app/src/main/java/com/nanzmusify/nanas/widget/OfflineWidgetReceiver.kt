/*
 * Copyright (C) 2026 Nanas
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.nanzmusify.nanas.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.widget.RemoteViews
import com.nanzmusify.nanas.NanzMusifyApp
import com.nanzmusify.nanas.R
import com.nanzmusify.nanas.data.LibraryRepository
import com.nanzmusify.nanas.data.model.NanzMusifyTrack
import com.nanzmusify.nanas.player.ACTION_PLAY_OFFLINE_QUEUE
import com.nanzmusify.nanas.player.EXTRA_CATEGORY_ID
import com.nanzmusify.nanas.player.EXTRA_START_INDEX
import com.nanzmusify.nanas.player.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Widget "NanzMusify Offline" (nambahan dari wishlist widget pemutar biasa):
 * jelajahi & putar lagu yang SUDAH DIUNDUH langsung dari layar utama, tanpa
 * pernah membuka app — termasuk berpindah PLAYLIST/kategori, bukan cuma
 * lagu satu-satu. Sengaja widget terpisah dari [PlayerWidgetReceiver] (yang
 * mencerminkan sesi putar aktif) karena fungsinya beda: ini adalah rak lagu
 * offline yang bisa dipilih sendiri, bukan cermin dari apa yang sedang app
 * putar.
 *
 * Semua kueri DB & keputusan kategori/indeks dilakukan lewat
 * [LibraryRepository.offlineCategories]/[LibraryRepository.offlineTracksFor]
 * — satu-satunya sumber kebenaran "apa saja yang benar-benar bisa diputar
 * tanpa jaringan", supaya widget tidak pernah menawarkan lagu yang gagal
 * diputar saat offline.
 */
class OfflineWidgetReceiver : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CATEGORY_PREV, ACTION_CATEGORY_NEXT -> {
                goAsync {
                    stepCategory(context, forward = intent.action == ACTION_CATEGORY_NEXT)
                    renderAll(context)
                }
            }
            ACTION_TRACK_PREV, ACTION_TRACK_NEXT -> {
                goAsync {
                    stepTrack(context, forward = intent.action == ACTION_TRACK_NEXT)
                    renderAll(context)
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        goAsync { renderAll(context) }
    }

    /** [android.appwidget.AppWidgetProvider.goAsync] + coroutine, meniru pola [PlayerWidgetReceiver]. */
    private fun goAsync(block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            runCatching { block() }
            runCatching { pending.finish() }
        }
    }

    /** Pindah kategori (wrap-around), reset ke lagu pertama. Tidak otomatis memutar. */
    private suspend fun stepCategory(context: Context, forward: Boolean) {
        val locator = (context.applicationContext as NanzMusifyApp).locator
        val categories = locator.library.offlineCategories()
        if (categories.isEmpty()) return
        val currentId = OfflineWidgetPrefs.categoryId(context)
        val currentIndex = categories.indexOfFirst { it.id == currentId }.let { if (it < 0) 0 else it }
        val nextIndex = (currentIndex + if (forward) 1 else -1).mod(categories.size)
        OfflineWidgetPrefs.save(context, categories[nextIndex].id, 0)
    }

    /** Pindah lagu (wrap-around) di dalam kategori aktif, LALU langsung putar dari sana. */
    private suspend fun stepTrack(context: Context, forward: Boolean) {
        val locator = (context.applicationContext as NanzMusifyApp).locator
        val categories = locator.library.offlineCategories()
        if (categories.isEmpty()) return
        var categoryId = OfflineWidgetPrefs.categoryId(context)
        if (categories.none { it.id == categoryId }) categoryId = categories.first().id
        val tracks = locator.library.offlineTracksFor(categoryId)
        if (tracks.isEmpty()) return
        val currentIndex = OfflineWidgetPrefs.trackIndex(context).coerceIn(0, tracks.lastIndex)
        val nextIndex = (currentIndex + if (forward) 1 else -1).mod(tracks.size)
        OfflineWidgetPrefs.save(context, categoryId, nextIndex)

        // Putar langsung — inilah yang membuat widget bisa dipakai walau app
        // tidak pernah dibuka: perintah dikirim langsung ke PlaybackService.
        runCatching {
            val playIntent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_PLAY_OFFLINE_QUEUE
                putExtra(EXTRA_CATEGORY_ID, categoryId)
                putExtra(EXTRA_START_INDEX, nextIndex)
            }
            context.startService(playIntent)
        }
    }

    /** Render ulang semua instance widget berdasarkan status tersimpan saat ini. */
    private suspend fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, OfflineWidgetReceiver::class.java))
        if (ids.isEmpty()) return

        val locator = (context.applicationContext as NanzMusifyApp).locator
        val categories = locator.library.offlineCategories()
        if (categories.isEmpty()) {
            ids.forEach { id -> runCatching { manager.updateAppWidget(id, buildEmptyView(context)) } }
            return
        }
        var categoryId = OfflineWidgetPrefs.categoryId(context)
        if (categories.none { it.id == categoryId }) {
            categoryId = categories.first().id
            OfflineWidgetPrefs.save(context, categoryId, 0)
        }
        val category = categories.first { it.id == categoryId }
        val tracks = locator.library.offlineTracksFor(categoryId)
        val index = OfflineWidgetPrefs.trackIndex(context).coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        val track = tracks.getOrNull(index)

        ids.forEach { id -> runCatching { manager.updateAppWidget(id, buildViews(context, category.label, track, null)) } }
        val artUrl = track?.thumbnailUrl.orEmpty()
        if (artUrl.isBlank()) return
        val art = loadArtwork(artUrl)
        if (art != null) {
            ids.forEach { id -> runCatching { manager.updateAppWidget(id, buildViews(context, category.label, track, art)) } }
        }
    }

    private fun buildViews(context: Context, categoryLabel: String, track: NanzMusifyTrack?, art: Bitmap?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_offline_player)
        views.setTextViewText(R.id.offline_category_label, categoryLabel)
        views.setTextViewText(
            R.id.offline_title,
            track?.title?.ifBlank { null } ?: context.getString(R.string.widget_empty_title),
        )
        views.setTextViewText(
            R.id.offline_artist,
            track?.artist?.ifBlank { null } ?: context.getString(R.string.common_youtube),
        )
        if (art != null) {
            views.setImageViewBitmap(R.id.offline_art, art)
        } else {
            views.setImageViewResource(R.id.offline_art, R.drawable.ic_widget_note)
        }
        val snap = WidgetState.snapshot
        views.setImageViewResource(
            R.id.offline_play,
            if (snap.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        views.setOnClickPendingIntent(R.id.offline_cat_prev, broadcast(context, ACTION_CATEGORY_PREV, REQ_CAT_PREV))
        views.setOnClickPendingIntent(R.id.offline_cat_next, broadcast(context, ACTION_CATEGORY_NEXT, REQ_CAT_NEXT))
        views.setOnClickPendingIntent(R.id.offline_prev, broadcast(context, ACTION_TRACK_PREV, REQ_TRACK_PREV))
        views.setOnClickPendingIntent(R.id.offline_next, broadcast(context, ACTION_TRACK_NEXT, REQ_TRACK_NEXT))
        views.setOnClickPendingIntent(R.id.offline_play, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.offline_root, openApp(context))
        return views
    }

    private fun buildEmptyView(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_offline_player)
        views.setTextViewText(R.id.offline_category_label, context.getString(R.string.widget_offline_label))
        views.setTextViewText(R.id.offline_title, context.getString(R.string.widget_offline_empty))
        views.setTextViewText(R.id.offline_artist, "")
        views.setImageViewResource(R.id.offline_art, R.drawable.ic_widget_note)
        views.setImageViewResource(R.id.offline_play, R.drawable.ic_widget_play)
        views.setOnClickPendingIntent(R.id.offline_root, openApp(context))
        // Tanpa lagu offline: tombol navigasi tidak diberi PendingIntent (nonaktif secara halus).
        return views
    }

    companion object {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val httpClient by lazy { OkHttpClient() }
        private const val ART_MAX_PX = 256

        @Volatile private var cachedArtUrl: String = ""
        @Volatile private var cachedArt: Bitmap? = null

        const val ACTION_CATEGORY_PREV = "com.nanzmusify.nanas.widget.offline.CATEGORY_PREV"
        const val ACTION_CATEGORY_NEXT = "com.nanzmusify.nanas.widget.offline.CATEGORY_NEXT"
        const val ACTION_TRACK_PREV = "com.nanzmusify.nanas.widget.offline.TRACK_PREV"
        const val ACTION_TRACK_NEXT = "com.nanzmusify.nanas.widget.offline.TRACK_NEXT"

        private const val REQ_OPEN_APP = 20
        private const val REQ_CAT_PREV = 21
        private const val REQ_CAT_NEXT = 22
        private const val REQ_TRACK_PREV = 23
        private const val REQ_TRACK_NEXT = 24
        private const val REQ_PLAY_PAUSE = 25

        /** Minta launcher merender ulang semua instance widget ini. */
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, OfflineWidgetReceiver::class.java))
                if (ids.isEmpty()) return
                val intent = Intent(context, OfflineWidgetReceiver::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }

        private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, OfflineWidgetReceiver::class.java).apply { this.action = action }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = ComponentName(context, PlaybackService::class.java)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            }
            return PendingIntent.getService(
                context,
                REQ_PLAY_PAUSE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openApp(context: Context): PendingIntent {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, PlaybackService::class.java)
            return PendingIntent.getActivity(
                context,
                REQ_OPEN_APP,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Sama seperti [PlayerWidgetReceiver]: cache satu-entri, gagal jaringan tidak masalah. */
        private fun loadArtwork(url: String): Bitmap? {
            if (url.isBlank()) return null
            cachedArt?.let { if (cachedArtUrl == url) return it }
            val decoded = runCatching {
                val call = httpClient.newCall(Request.Builder().url(url).build())
                call.execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull() ?: return null
            val art = if (decoded.width > ART_MAX_PX || decoded.height > ART_MAX_PX) {
                runCatching { Bitmap.createScaledBitmap(decoded, ART_MAX_PX, ART_MAX_PX, true) }.getOrDefault(decoded)
            } else {
                decoded
            }
            cachedArtUrl = url
            cachedArt = art
            return art
        }
    }
}
