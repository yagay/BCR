/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.chiller3.bcr.audio

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Fork-owned recording gain preference stored in the same device-protected
 * preferences file/key as the previous integration. No data migration needed.
 *
 * This avoids adding extension-specific properties to upstream Preferences.
 */
class RecordingGainPreferences(initialContext: Context) {
    private val context = if (initialContext.isDeviceProtectedStorage) {
        initialContext
    } else {
        initialContext.createDeviceProtectedStorageContext()
    }
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var gainDb: Int
        get() = prefs.getInt(KEY, 0).takeIf { it in PcmGain.OPTIONS_DB } ?: 0
        set(value) {
            require(value in PcmGain.OPTIONS_DB) { "Unsupported recording gain: $value dB" }
            prefs.edit { putInt(KEY, value) }
        }

    private companion object {
        const val KEY = "recording_gain_db"
    }
}
