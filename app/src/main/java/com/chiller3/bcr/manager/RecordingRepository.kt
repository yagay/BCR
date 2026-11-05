/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Native recording browser for BCR. Reads the same output location as the
 * recorder without altering its write path or recording preferences.
 */
package com.chiller3.bcr.manager

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.chiller3.bcr.Preferences
import com.chiller3.bcr.extension.toDocumentFile
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.text.DateFormat
import java.util.Date

internal data class ManagedRecording(
    val file: DocumentFile,
    val metadata: DocumentFile?,
    val title: String,
    val number: String,
    val direction: String,
    val timestamp: Long,
    val durationSeconds: Long,
) {
    val uri: Uri get() = file.uri
    val dateLabel: String get() = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(timestamp))
}

internal class RecordingRepository(private val context: Context) {
    private val extensions = setOf("oga", "ogg", "m4a", "mp3", "wav", "flac", "amr", "3gp", "aac", "opus", "mka", "webm")

    // Default BCR filename layout, with optional direction/SIM/contact suffix.
    // Custom templates still work with JSON, otherwise keep a usable filename.
    private val defaultName = Regex(
        """^(\d{8}_\d{6}(?:\.\d+)?(?:[+-]\d{4})?)(?:_(in|out|conference))?(?:_sim\d+)?(?:_([+\d][+\d -]*|unknown))?(?:_(.*))?$""",
        RegexOption.IGNORE_CASE,
    )

    fun scan(): List<ManagedRecording> {
        val root = Preferences(context).outputDirOrDefault.toDocumentFile(context)
        require(root.exists() && root.isDirectory) { "BCR recording folder is not available" }

        // BCR filename templates may contain subdirectories. Use an explicit
        // stack so a large recording collection doesn't overflow recursion.
        val audio = mutableListOf<DocumentFile>()
        val metadata = mutableMapOf<String, DocumentFile>()
        val directories = ArrayDeque<Pair<DocumentFile, String>>()
        directories.add(root to "")
        val paths = mutableMapOf<String, String>()
        while (directories.isNotEmpty()) {
            val (dir, prefix) = directories.removeLast()
            for (file in dir.listFiles()) {
                val name = file.name ?: continue
                val path = "$prefix/$name"
                if (file.isDirectory) {
                    directories.add(file to path)
                } else {
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension in extensions) {
                        audio.add(file)
                        paths[file.uri.toString()] = path
                    } else if (extension == "json") {
                        metadata[path.substringBeforeLast('.')] = file
                    }
                }
            }
        }

        return audio.map { file ->
            val name = file.name.orEmpty()
            val path = paths[file.uri.toString()].orEmpty()
            val companion = metadata[path.substringBeforeLast('.')]
            val json = companion?.let {
                runCatching {
                    context.contentResolver.openInputStream(it.uri)?.bufferedReader()?.use { reader ->
                        JSONObject(reader.readText())
                    }
                }.getOrNull()
            }
            val firstCall = json?.optJSONArray("calls")?.optJSONObject(0)
            fun clean(value: String?): String? = value?.takeIf { it.isNotBlank() && it != "null" }
            val fallback = defaultName.matchEntire(name.substringBeforeLast('.'))
            val number = firstCall?.optString("phone_number_formatted")?.let(::clean)
                ?: firstCall?.optString("phone_number")?.let(::clean)
                ?: fallback?.groupValues?.get(3).orEmpty()
            val title = firstCall?.optString("contact_name")?.let(::clean)
                ?: json?.optString("call_log_name")?.let(::clean)
                ?: firstCall?.optString("caller_name")?.let(::clean)
                ?: fallback?.groupValues?.get(4)?.let(::clean)
                ?: number.ifBlank { name.substringBeforeLast('.') }
            val filenameTimestamp = fallback?.groupValues?.get(1)?.let { encoded ->
                runCatching {
                    val parts = Regex("""^(\\d{8}_\\d{6})(?:\\.(\\d{1,3}))?([+-]\\d{4})?$""")
                        .matchEntire(encoded) ?: return@runCatching null
                    val millis = parts.groupValues[2].padEnd(3, '0').ifBlank { "000" }
                    val offset = parts.groupValues[3]
                    val pattern = if (offset.isNotEmpty()) "yyyyMMdd_HHmmss.SSSZ"
                        else "yyyyMMdd_HHmmss.SSS"
                    SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }
                        .parse(parts.groupValues[1] + "." + millis + offset)?.time
                }.getOrNull()
            }
            ManagedRecording(
                file = file,
                metadata = companion,
                title = title,
                number = number,
                direction = json?.optString("direction")?.let(::clean)
                    ?: fallback?.groupValues?.get(2).orEmpty(),
                timestamp = json?.optLong("timestamp_unix_ms")?.takeIf { it > 0 }
                    ?: filenameTimestamp ?: file.lastModified(),
                durationSeconds = json?.optJSONObject("output")?.optJSONObject("recording")
                    ?.optLong("duration_secs_encoded") ?: 0,
            )
        }.sortedByDescending { it.timestamp }
    }

    fun delete(items: List<ManagedRecording>): Int {
        var deleted = 0
        for (item in items) {
            // Never delete metadata before deleting the associated audio.
            if (item.file.delete()) {
                item.metadata?.delete()
                deleted++
            }
        }
        return deleted
    }
}
