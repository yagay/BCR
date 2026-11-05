/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Native recording browser for BCR. Reads the same output location as the
 * recorder without altering its write path or recording preferences.
 */
package com.chiller3.bcr.manager

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import androidx.documentfile.provider.DocumentFile
import com.chiller3.bcr.Preferences
import com.chiller3.bcr.output.PhoneNumber
import com.google.i18n.phonenumbers.PhoneNumberUtil
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
    val normalizedNumber: String = "",
    val contactName: String = "",
    val direction: String,
    val timestamp: Long,
    val durationSeconds: Long,
    val simSlot: Int? = null,
    val sizeBytes: Long = -1L,
    val format: String = "",
) {
    val uri: Uri get() = file.uri
    val dateLabel: String get() = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(timestamp))
}

internal class RecordingRepository(private val context: Context) {
    // Full SAF enumeration remains authoritative: caching never hides new files.
    private data class CachedMetadata(val modified: Long, val size: Long, val value: JSONObject)
    private val jsonCache = LinkedHashMap<String, CachedMetadata>()

    private fun readMetadata(file: DocumentFile): JSONObject? {
        val uri = file.uri.toString()
        val modified = file.lastModified()
        val size = file.length()
        if (modified > 0 && size > 0) {
            val cached = jsonCache[uri]
            if (cached != null && cached.modified == modified && cached.size == size) {
                return cached.value
            }
        }
        val parsed = runCatching {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                JSONObject(reader.readText())
            }
        }.getOrNull() ?: return null
        if (modified > 0 && size > 0) {
            if (jsonCache.size >= 512) jsonCache.remove(jsonCache.keys.first())
            jsonCache[uri] = CachedMetadata(modified, size, parsed)
        }
        return parsed
    }

    private val extensions = setOf("oga", "ogg", "m4a", "mp3", "wav", "flac", "amr", "3gp", "aac", "opus", "mka", "webm")

    // Default BCR filename layout, with optional direction/SIM/contact suffix.
    // Custom templates still work with JSON, otherwise keep a usable filename.
    private val simName = Regex("""(?:^|_)sim([0-9]+)(?:_|$)""", RegexOption.IGNORE_CASE)
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
                    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
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
            val json = companion?.let(::readMetadata)
            val firstCall = json?.optJSONArray("calls")?.optJSONObject(0)
            val output = json?.optJSONObject("output")
            val recordingJson = output?.optJSONObject("recording")
            fun clean(value: String?): String? = value?.takeIf { it.isNotBlank() && it != "null" }
            val fallback = defaultName.matchEntire(name.substringBeforeLast('.'))
            val number = firstCall?.optString("phone_number_formatted")?.let(::clean)
                ?: firstCall?.optString("phone_number")?.let(::clean)
                ?: fallback?.groupValues?.get(3).orEmpty()
            val contactName = firstCall?.optString("contact_name")?.let(::clean)
                ?: json?.optString("call_log_name")?.let(::clean)
            val title = contactName
                ?: firstCall?.optString("caller_name")?.let(::clean)
                ?: fallback?.groupValues?.get(4)?.let(::clean)
                ?: number.ifBlank { name.substringBeforeLast('.') }
            val e164 = firstCall?.optString("phone_number_e164")?.let(::clean)
                ?: number.takeIf { it.isNotBlank() }?.let {
                    runCatching { PhoneNumber(it).format(context, PhoneNumberUtil.PhoneNumberFormat.E164) }.getOrNull()
                }
            // val title was computed above
            /*
                ?: json?.optString("call_log_name")?.let(::clean)
                ?: firstCall?.optString("caller_name")?.let(::clean)
                ?: fallback?.groupValues?.get(4)?.let(::clean)
                ?: number.ifBlank { name.substringBeforeLast('.') } */
            val filenameTimestamp = fallback?.groupValues?.get(1)?.let { encoded ->
                runCatching {
                    val parts = Regex("""^(\d{8}_\d{6})(?:\.(\d{1,3}))?([+-]\d{4})?$""")
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
                normalizedNumber = e164.orEmpty(),
                contactName = contactName.orEmpty(),
                direction = json?.optString("direction")?.let(::clean)
                    ?: fallback?.groupValues?.get(2).orEmpty(),
                timestamp = json?.optLong("timestamp_unix_ms")?.takeIf { it > 0 }
                    ?: filenameTimestamp ?: file.lastModified(),
                durationSeconds = recordingJson?.optDouble("duration_secs_encoded")
                    ?.takeIf { it > 0 }?.toLong() ?: 0L,
                simSlot = json?.optInt("sim_slot", -1)?.takeIf { it > 0 }
                    ?: simName.find(name.substringBeforeLast('.'))?.groupValues?.get(1)?.toIntOrNull(),
                sizeBytes = file.length(),
                format = name.substringAfterLast('.', "").uppercase(Locale.ROOT),
            )
        }.sortedByDescending { it.timestamp }
    }

    /** Read missing durations on demand, never during the initial directory scan. */
    fun readDuration(file: ManagedRecording): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, file.uri)
            val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            (millis / 1000L).coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    data class DeleteResult(val deleted: Int, val metadataFailures: Int, val failedUris: Set<String>)

    fun delete(items: List<ManagedRecording>, all: List<ManagedRecording>): DeleteResult {
        var deleted = 0
        var metadataFailures = 0
        val failed = mutableSetOf<String>()
        val deletedMetadataCandidates = mutableSetOf<String>()
        // Audio deletion is authoritative; never delete JSON if any associated
        // audio file survives (including another recording outside selection).
        for (item in items.distinctBy { it.uri.toString() }) {
            val ok = runCatching { item.file.delete() }.getOrDefault(false)
            if (ok) {
                deleted++
                item.metadata?.let { deletedMetadataCandidates.add(it.uri.toString()) }
            } else {
                failed.add(item.uri.toString())
            }
        }
        val selectedUris = items.map { it.uri.toString() }.toSet()
        for (metadataUri in deletedMetadataCandidates) {
            val siblings = all.filter { it.metadata?.uri?.toString() == metadataUri }
            val anyRemaining = siblings.any { sibling ->
                sibling.uri.toString() !in selectedUris ||
                    sibling.uri.toString() in failed
            }
            if (!anyRemaining) {
                val metadataFile = siblings.firstOrNull()?.metadata
                if (metadataFile != null && !runCatching { metadataFile.delete() }.getOrDefault(false)) {
                    metadataFailures++
                }
            }
        }
        return DeleteResult(deleted, metadataFailures, failed)
    }
}
