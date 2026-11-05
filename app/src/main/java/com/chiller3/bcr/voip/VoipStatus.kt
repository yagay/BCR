package com.chiller3.bcr.voip

import android.content.Context

/**
 * BCR does not store notification text, contact details or raw call metadata.
 * Only minimal local diagnostics are persisted.
 */
object VoipStatus {
    private const val PREFS = "voip_capture_state"
    private const val MESSAGE = "message"
    private const val FILE = "last_file"
    private const val RUNNING = "running"

    fun update(context: Context, running: Boolean, message: String, filename: String? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(RUNNING, running)
            .putString(MESSAGE, message.take(500))
            .apply {
                if (filename != null) putString(FILE, filename)
            }
            .apply()
    }
    fun running(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(RUNNING, false)
    fun message(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MESSAGE, "No VoIP recording attempted") ?: "No VoIP recording attempted"
    fun lastFile(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FILE, null)
}
