/*
 * Copyright (C) 2026 Nanas
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.nanzmusify.nanas.widget

import android.content.Context
import com.nanzmusify.nanas.data.LibraryRepository

/**
 * Status "sedang menjelajah apa" untuk widget "NanzMusify Offline" — kategori
 * (Semua Offline / Favorit / satu playlist) dan indeks lagu di dalamnya.
 * SharedPreferences dipilih (bukan DataStore) karena widget dibaca-tulis dari
 * [android.appwidget.AppWidgetProvider] yang tidak punya scope Compose/coroutine
 * siap pakai; akses sinkron singkat di sini tidak masalah (data kecil).
 */
object OfflineWidgetPrefs {

    private const val PREFS_NAME = "nanzmusify_offline_widget"
    private const val KEY_CATEGORY = "category_id"
    private const val KEY_INDEX = "track_index"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun categoryId(context: Context): String =
        prefs(context).getString(KEY_CATEGORY, LibraryRepository.OFFLINE_ALL) ?: LibraryRepository.OFFLINE_ALL

    fun trackIndex(context: Context): Int = prefs(context).getInt(KEY_INDEX, 0)

    fun save(context: Context, categoryId: String, trackIndex: Int) {
        prefs(context).edit()
            .putString(KEY_CATEGORY, categoryId)
            .putInt(KEY_INDEX, trackIndex)
            .apply()
    }
}
