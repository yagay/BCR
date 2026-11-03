package com.chiller3.bcr

import android.content.Context
import android.util.Log

/**
 * Stores only lifecycle timestamps. Never records phone numbers, contact
 * information, audio, call objects, or personal metadata.
 *
 * Device-protected storage is available before first unlock for BCR's
 * direct-boot aware InCallService.
 */
object IntegrationStatus {
    enum class Event(val key: String, val label: String) {
        SERVICE_CREATED("service_created", "InCallService created"),
        CALL_ADDED("call_added", "Call received by InCallService"),
        RECORD_THREAD_STARTED("record_thread_started", "Recorder thread requested"),
        CALL_REMOVED("call_removed", "Call removed by Telecom"),
    }

    private const val PREFS = "integration_lifecycle"

    fun mark(context: Context, event: Event) {
        try {
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(event.key, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w("BCR/Integration", "Unable to save lifecycle event ${event.key}", e)
        }
    }

    fun snapshot(context: Context): Map<Event, Long?> {
        return try {
            val prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            Event.entries.associateWith { event ->
                prefs.getLong(event.key, 0L).takeIf { it > 0L }
            }
        } catch (e: Exception) {
            Log.w("BCR/Integration", "Unable to read lifecycle status", e)
            Event.entries.associateWith { null }
        }
    }
}
