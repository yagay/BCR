package com.chiller3.bcr.voip

import android.content.Context
import android.media.AudioManager

/**
 * Supported app *candidates*, not a claim that their calls can be captured.
 * Borrowed the explicit opt-in model from JemRec (Apache-2.0).
 */
object VoipTargets {
    data class Target(val packageName: String, val name: String, val tag: String)

    val known = listOf(
        Target("com.whatsapp", "WhatsApp", "whatsapp"),
        Target("com.whatsapp.w4b", "WhatsApp Business", "whatsapp-business"),
        Target("org.telegram.messenger", "Telegram", "telegram"),
        Target("org.thunderdog.challegram", "Telegram X", "telegram-x"),
        Target("org.thoughtcrime.securesms", "Signal", "signal"),
        Target("com.viber.voip", "Viber", "viber"),
        Target("com.google.android.apps.tachyon", "Google Meet", "meet"),
        Target("com.tencent.mm", "WeChat", "wechat"),
    )
    private const val PREF = "voip_capture_experimental"
    private const val APPS = "enabled_apps"
    private const val AUTO = "automatic_notification_detection"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun enabled(context: Context): Set<String> =
        (prefs(context).getStringSet(APPS, emptySet()) ?: emptySet())
            .filter { app -> known.any { it.packageName == app } }.toSet()

    fun setEnabled(context: Context, pkg: String, enabled: Boolean) {
        if (known.none { it.packageName == pkg }) return
        val next = enabled(context).toMutableSet()
        if (enabled) next.add(pkg) else next.remove(pkg)
        prefs(context).edit().putStringSet(APPS, next).apply()
    }

    fun automatic(context: Context): Boolean = prefs(context).getBoolean(AUTO, false)
    fun setAutomatic(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(AUTO, enabled).apply()

    fun tagFor(pkg: String) = known.firstOrNull { it.packageName == pkg }?.tag

    /** Audio mode is a hint only; it does not identify the app or guarantee audio access. */
    fun isCommunicationMode(mode: Int): Boolean =
        mode == AudioManager.MODE_IN_COMMUNICATION ||
            (android.os.Build.VERSION.SDK_INT >= 33 &&
                mode == AudioManager.MODE_COMMUNICATION_REDIRECT)
}
