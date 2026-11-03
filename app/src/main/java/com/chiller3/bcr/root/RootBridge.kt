package com.chiller3.bcr.root

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * A manually invoked, least-scope root helper. Nothing runs automatically at boot
 * or when a call starts. In particular, this does not make a user app privileged.
 */
object RootBridge {
    data class Result(val success: Boolean, val message: String)

    suspend fun checkRoot(): Result = withContext(Dispatchers.IO) {
        val result = execute("id -u")
        if (result.success && result.message.trim() == "0") {
            Result(true, "Root available (uid 0)")
        } else {
            Result(false, "Root unavailable or denied: " + result.message)
        }
    }

    suspend fun exemptCallLog(context: Context): Result = withContext(Dispatchers.IO) {
        val apk = context.applicationInfo.sourceDir
        if (apk.isNullOrEmpty()) {
            return@withContext Result(false, "Cannot locate installed BCR APK")
        }
        // Reuse BCR's existing permission-manager CLI, rather than inventing an
        // unsupported 'pm grant' for restricted or signature permissions.
        val command = "CLASSPATH=" + shellQuote(apk) +
            " /system/bin/app_process / com.chiller3.bcr.standalone.RemoveHardRestrictionsKt"
        val result = execute(command)
        if (result.success) {
            Result(true, "READ_CALL_LOG restriction helper completed. Verify permission in Android settings.")
        } else {
            Result(false, "READ_CALL_LOG helper failed: " + result.message +
                ". See logcat for RemoveHardRestrictions diagnostics.")
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun execute(command: String): Result {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return Result(false, e.toString())
        }
        return try {
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Result(false, "Root process timed out (25 seconds)")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText().take(3000) }
                Result(process.exitValue() == 0, output.ifBlank { "exit=" + process.exitValue() })
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            Result(false, e.toString())
        }
    }
}
