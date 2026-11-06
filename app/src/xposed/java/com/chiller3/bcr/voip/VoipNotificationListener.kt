package com.chiller3.bcr.voip

import android.app.Notification
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Strict opt-in notification trigger; only package and notification category
 * are read. No text, numbers, messages, contacts or callers are persisted.
 *
 * Calls can post their ongoing notification before AudioManager switches into
 * communication mode. Retry for a bounded time, similar to ShizuCallRecorder's
 * delayed audio-mode detection, without constantly polling in the background.
 */
class VoipNotificationListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashSet<String>()
    private var activeKey: String? = null
    private var activePackage: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        main.post {
            activeNotifications.orEmpty().forEach(::handlePosted)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        main.post { handlePosted(item) }
    }

    private fun handlePosted(item: StatusBarNotification) {
        if (!VoipTargets.automatic(this) || item.packageName !in VoipTargets.enabled(this)) return
        if (!isCall(item) || activeKey != null || !pending.add(item.key)) return
        tryStart(item.key, item.packageName, MAX_RETRIES)
    }

    private fun tryStart(key: String, pkg: String, retries: Int) {
        if (key !in pending) return
        if (!VoipTargets.automatic(this) || pkg !in VoipTargets.enabled(this) ||
            activeKey != null || VoipStatus.running(this)) {
            pending.remove(key)
            return
        }
        val item = runCatching {
            activeNotifications.orEmpty().firstOrNull { it.key == key }
        }.getOrNull()
        if (item == null || !isCall(item)) {
            pending.remove(key)
            return
        }
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (VoipTargets.isCommunicationMode(audio.mode)) {
            pending.remove(key)
            if (VoipRecorderService.start(this, pkg)) {
                activeKey = key
                activePackage = pkg
                Log.i(TAG, "Auto VoIP capture requested for approved package $pkg")
            }
        } else if (retries > 0) {
            main.postDelayed({ tryStart(key, pkg, retries - 1) }, POLL_MS)
        } else {
            pending.remove(key)
        }
    }

    private fun isCall(item: StatusBarNotification): Boolean {
        val n = item.notification
        return n.category == Notification.CATEGORY_CALL &&
            n.flags and Notification.FLAG_ONGOING_EVENT != 0
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        main.post {
            pending.remove(item.key)
            if (item.key != activeKey) return@post
            val pkg = activePackage
            activeKey = null
            activePackage = null
            if (pkg != null && activeNotifications.orEmpty().any {
                    it.packageName == pkg && isCall(it)
                }) return@post
            VoipRecorderService.stop(this)
        }
    }

    override fun onDestroy() {
        pending.clear()
        main.removeCallbacksAndMessages(null)
        if (activeKey != null) VoipRecorderService.stop(this)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BCR/VoIPListener"
        const val POLL_MS = 80L
        const val MAX_RETRIES = 10
    }
}
