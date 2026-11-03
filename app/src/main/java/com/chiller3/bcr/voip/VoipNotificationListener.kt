package com.chiller3.bcr.voip

import android.app.Notification
import android.content.Context
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Opt-in detection for apps with an actual ongoing call notification.
 * No titles, callers, message contents, call participants or other sensitive
 * fields are read. Unsupported OEM notification styles are deliberately ignored
 * to avoid recording arbitrary media sessions.
 */
class VoipNotificationListener : NotificationListenerService() {
    private var activeKey: String? = null
    private var activePackage: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        if (!VoipTargets.automatic(this) || item.packageName !in VoipTargets.enabled(this)) {
            return
        }
        val n = item.notification ?: return
        if (n.category != Notification.CATEGORY_CALL ||
            n.flags and Notification.FLAG_ONGOING_EVENT == 0) return
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!VoipTargets.isCommunicationMode(audio.mode)) return
        if (activeKey != null || VoipStatus.running(this)) return
        if (VoipRecorderService.start(this, item.packageName)) {
            activeKey = item.key
            activePackage = item.packageName
            Log.i(TAG, "Auto capture requested for approved VoIP package ${item.packageName}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        if (item.key != activeKey) return
        val pkg = activePackage
        activeKey = null
        activePackage = null
        if (pkg != null && activeNotifications.orEmpty().any { other ->
                other.packageName == pkg &&
                    other.notification?.category == Notification.CATEGORY_CALL &&
                    other.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
            }) return
        VoipRecorderService.stop(this)
    }

    companion object {
        private const val TAG = "BCR/VoIPListener"
    }
}
