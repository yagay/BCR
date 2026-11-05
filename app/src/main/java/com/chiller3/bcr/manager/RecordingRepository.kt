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
import com.chiller3.bcr.output.OutputFilenameGenerator
import com.chiller3.bcr.format.Format
import com.chiller3.bcr.extension.listFilesWithNames
import androidx.core.net.toUri
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
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
    val codec: String = "",
    val mimeType: String = "",
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val participantNumbers: List<String> = emptyList(),
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


    // These extensions include every BCR built-in output format plus common
    // legacy/imported formats. The MIME type is also checked, because BCR's
    // SAF provider may omit or substitute file extensions.
    private val audioExtensions = setOf(
        "oga", "ogg", "opus", "m4a", "mp4", "aac", "mp3",
        "wav", "wave", "flac", "amr", "3gp", "3gpp",
        "mka", "webm",
    )
    private val audioMimeTypes: Set<String> by lazy {
        Format.all.flatMap { listOf(it.mimeTypeContainer, it.mimeTypeAudio) }
            .map { it.lowercase(Locale.ROOT) }.toSet()
    }
    private val simName = Regex("""(?:^|_)sim([0-9]+)(?:_|$)""", RegexOption.IGNORE_CASE)
    private val defaultName = Regex(
        """^(\d{8}_\d{6}(?:\.\d+)?(?:[+-]\d{4})?)(?:_(in|out|conference))?(?:_sim\d+)?(?:_([+\d][+\d -]*|unknown))?(?:_(.*))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val encodedDate = Regex("""^(\d{8}_\d{6})(?:\.(\d{1,3}))?([+-]\d{4})?$""")

    private data class RecordingCandidate(
        val file: DocumentFile,
        val relativePath: List<String>,
        val rootId: String,
    )

    private fun isAudioFile(file: DocumentFile, name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = file.type?.lowercase(Locale.ROOT)?.substringBefore(';').orEmpty()
        return extension in audioExtensions || mime.startsWith("audio/") ||
            mime in audioMimeTypes
    }

    private fun parseDefaultTimestamp(stem: String): Long? {
        val encoded = defaultName.matchEntire(stem)?.groupValues?.get(1) ?: return null
        val parts = encodedDate.matchEntire(encoded) ?: return null
        val fraction = parts.groupValues[2].padEnd(3, '0').ifBlank { "000" }
        val offset = parts.groupValues[3]
        val pattern = if (offset.isNotEmpty()) "yyyyMMdd_HHmmss.SSSZ"
            else "yyyyMMdd_HHmmss.SSS"
        return runCatching {
            SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }
                .parse(parts.groupValues[1] + "." + fraction + offset)?.time
        }.getOrNull()
    }

    private fun toEpochMillis(temporal: Any?): Long? = runCatching {
        when (temporal) {
            is ZonedDateTime -> temporal.toInstant().toEpochMilli()
            is LocalDateTime -> temporal.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            is LocalDate -> temporal.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            is Instant -> temporal.toEpochMilli()
            else -> null
        }
    }.getOrNull()

    fun scan(): List<ManagedRecording> {
        val prefs = Preferences(context)
        val activeRoot = prefs.outputDirOrDefault
        val roots = listOf(
            activeRoot,
            prefs.defaultOutputDir.toUri(), // BCR keeps recordings here when SAF moves fail.
        ).distinctBy { it.toString() }

        val generator = OutputFilenameGenerator(context)
        val templateFallback = RecordingTemplateFallback(
            listOfNotNull(prefs.filenameTemplate, Preferences.DEFAULT_FILENAME_TEMPLATE).distinct()
        )
        val audio = LinkedHashMap<String, RecordingCandidate>()
        val unrecognized = mutableListOf<RecordingCandidate>()
        val sidecars = mutableMapOf<String, DocumentFile>()
        var primaryAccessible = false

        for ((rootIndex, rootUri) in roots.withIndex()) {
            val root = runCatching { rootUri.toDocumentFile(context) }.getOrNull()
            if (root == null || !root.exists() || !root.isDirectory) {
                if (rootIndex == 0) throw IOException("BCR output folder is not accessible")
                continue
            }
            if (rootIndex == 0) primaryAccessible = true
            val queue = ArrayDeque<Pair<DocumentFile, List<String>>>()
            queue.add(root to emptyList())
            while (queue.isNotEmpty()) {
                val (folder, folderPath) = queue.removeLast()
                // The project provides this optimized SAF reader, reducing
                // thousands of per-file display-name queries to one per folder.
                val children = folder.listFilesWithNames().ifEmpty {
                    folder.listFiles().mapNotNull { child ->
                        child.name?.let { child to it }
                    }
                }
                for ((file, name) in children) {
                    val relative = folderPath + name
                    if (file.isDirectory) {
                        queue.add(file to relative)
                        continue
                    }
                    val key = rootUri.toString() + "/" + relative.dropLast(1).joinToString("/") +
                        "/" + name.substringBeforeLast('.')
                    if (name.endsWith(".json", ignoreCase = true)) {
                        sidecars[key] = file
                    } else if (isAudioFile(file, name)) {
                        audio.putIfAbsent(
                            file.uri.toString(),
                            RecordingCandidate(file, relative, rootUri.toString()),
                        )
                    } else if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in
                        setOf("txt", "log", "zip", "xml", "nfo", "db")) {
                        // Some SAF providers return application/octet-stream or
                        // no extension for BCR's audio files. Only accept those
                        // when a BCR JSON sidecar confirms the audio container.
                        unrecognized.add(RecordingCandidate(file, relative, rootUri.toString()))
                    }
                }
            }
        }
        if (!primaryAccessible) throw IOException("BCR recording folder is not available")

        for (candidate in unrecognized) {
            val stem = candidate.relativePath.last().substringBeforeLast('.')
            val sidecarKey = candidate.rootId + "/" +
                candidate.relativePath.dropLast(1).joinToString("/") + "/" + stem
            val json = sidecars[sidecarKey]?.let(::readMetadata)
            if (json?.optJSONObject("output")?.optJSONObject("format") != null) {
                audio.putIfAbsent(candidate.file.uri.toString(), candidate)
            }
        }

        return audio.values.map { candidate ->
            val file = candidate.file
            val name = candidate.relativePath.last()
            val stem = name.substringBeforeLast('.', name)
            val key = candidate.rootId + "/" +
                candidate.relativePath.dropLast(1).joinToString("/") + "/" + stem
            val sidecar = sidecars[key]
            val json = sidecar?.let(::readMetadata)
            val calls = json?.optJSONArray("calls")
            val firstCall = calls?.optJSONObject(0)
            val recordingJson = json?.optJSONObject("output")?.optJSONObject("recording")
            val formatJson = json?.optJSONObject("output")?.optJSONObject("format")
            fun clean(v: String?): String? = v?.trim()?.takeIf {
                it.isNotEmpty() && it != "null"
            }
            // Parse current and default BCR templates, never invent metadata
            // from an unknown filename layout.
            val relativeStem = (candidate.relativePath.dropLast(1) + stem).joinToString("/")
            val templateFields = templateFallback.extract(relativeStem)
            fun field(name: String): String? = clean(templateFields[name])
            val fallback = defaultName.matchEntire(stem)
            val number = clean(firstCall?.optString("phone_number_formatted"))
                ?: clean(firstCall?.optString("phone_number"))
                ?: field("phone_number")
                ?: clean(fallback?.groupValues?.get(3))
                ?: ""
            val contactName = clean(firstCall?.optString("contact_name"))
                ?: clean(json?.optString("call_log_name"))
                ?: field("contact_name")
                ?: field("call_log_name")
            val callerName = clean(firstCall?.optString("caller_name"))
                ?: field("caller_name")
            val participants = buildList {
                if (calls != null) {
                    for (i in 0 until calls.length()) {
                        val call = calls.optJSONObject(i) ?: continue
                        val phone = clean(call.optString("phone_number_formatted"))
                            ?: clean(call.optString("phone_number"))
                        if (phone != null) add(phone)
                    }
                }
            }.distinct()
            val title = contactName ?: callerName
                ?: clean(fallback?.groupValues?.get(4))
                ?: number.ifBlank { stem }
            val canonical = clean(firstCall?.optString("phone_number_e164"))
                ?: number.takeIf { it.isNotEmpty() }?.let {
                    runCatching {
                        PhoneNumber(it).format(context, PhoneNumberUtil.PhoneNumberFormat.E164)
                    }.getOrNull()
                }
            val parsedTime = json?.optLong("timestamp_unix_ms")?.takeIf { it > 0 }
                ?: toEpochMillis(runCatching {
                    generator.parseTimestampFromPath(candidate.relativePath)
                }.getOrNull())
                ?: templateFallback.unixTimestampMillis(relativeStem)
                ?: parseDefaultTimestamp(stem)
                ?: file.lastModified().takeIf { it > 0 }
                ?: 0L

            ManagedRecording(
                file = file,
                metadata = sidecar,
                title = title,
                number = number,
                normalizedNumber = canonical.orEmpty(),
                contactName = contactName.orEmpty(),
                direction = clean(json?.optString("direction"))
                    ?: field("direction")?.lowercase(Locale.ROOT)?.takeIf {
                        it in setOf("in", "out", "conference")
                    }
                    ?: clean(fallback?.groupValues?.get(2))
                    ?: "",
                timestamp = parsedTime,
                durationSeconds = recordingJson?.optDouble("duration_secs_encoded")
                    ?.takeIf { it > 0 }?.toLong() ?: 0L,
                simSlot = json?.optInt("sim_slot", -1)?.takeIf { it > 0 }
                    ?: field("sim_slot")?.toIntOrNull()
                    ?: simName.find(stem)?.groupValues?.get(1)?.toIntOrNull(),
                sizeBytes = file.length(),
                format = clean(formatJson?.optString("type"))
                    ?: name.substringAfterLast('.', "").uppercase(Locale.ROOT)
                        .ifBlank { file.type.orEmpty() },
                codec = clean(formatJson?.optString("mime_type_audio")).orEmpty(),
                mimeType = clean(formatJson?.optString("mime_type_container"))
                    ?: file.type ?: "audio/*",
                sampleRate = recordingJson?.optInt("sample_rate", 0) ?: 0,
                channels = recordingJson?.optInt("channel_count", 0) ?: 0,
                participantNumbers = participants,
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
