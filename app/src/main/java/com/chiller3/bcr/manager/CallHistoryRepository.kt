/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.chiller3.bcr.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kotlin.math.abs

internal data class CallHistoryEntry(
    val id: Long,
    val number: String,
    val name: String,
    val timestamp: Long,
    val durationSeconds: Long,
    val direction: String,
    val recordings: List<ManagedRecording> = emptyList(),
)

internal class CallHistoryRepository(private val context: Context) {
    fun canRead(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * CallLog stores call start timestamps, while BCR metadata timestamps may
     * represent when capturing began. Pair by number/direction + a bounded
     * time window; consume each recording at most once.
     *
     * Never infer a match solely because two calls are close in time when
     * phone numbers are available and different.
     */
    fun merge(recordings: List<ManagedRecording>): List<CallHistoryEntry> {
        if (!canRead()) return recordings.mapIndexed { index, recording ->
            CallHistoryEntry(
                id = -index.toLong() - 1,
                number = recording.number,
                name = recording.title,
                timestamp = recording.timestamp,
                durationSeconds = recording.durationSeconds,
                direction = recording.direction,
                recordings = listOf(recording),
            )
        }

        val calls = mutableListOf<CallHistoryEntry>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getInt(5)
                calls.add(
                    CallHistoryEntry(
                        id = cursor.getLong(0),
                        number = cursor.getString(1).orEmpty(),
                        name = cursor.getString(2).orEmpty(),
                        timestamp = cursor.getLong(3),
                        durationSeconds = cursor.getLong(4),
                        direction = when (type) {
                            CallLog.Calls.INCOMING_TYPE -> "in"
                            CallLog.Calls.OUTGOING_TYPE -> "out"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            CallLog.Calls.REJECTED_TYPE -> "rejected"
                            else -> "other"
                        },
                    )
                )
            }
        }

        val assignments = calls.map { mutableListOf<ManagedRecording>() }
        val unmatched = mutableListOf<ManagedRecording>()
        recordings.forEach { recording ->
            val candidates = calls.indices.mapNotNull { index ->
                val call = calls[index]
                val expected = call.direction == recording.direction ||
                    recording.direction.isBlank() || call.direction == "other"
                if (!expected || !sameNumber(call.number, recording.number)) return@mapNotNull null

                // BCR may begin at call start or when the call connects.
                // Keep tolerance small, with duration-informed allowance.
                val delta = abs(call.timestamp - recording.timestamp)
                val tolerance = (call.durationSeconds * 1000 + 45_000L)
                    .coerceIn(90_000L, 3_600_000L)
                if (delta <= tolerance) index to delta else null
            }
            val best = candidates.minByOrNull { it.second }
            if (best != null) assignments[best.first].add(recording)
            else unmatched.add(recording)
        }

        return (calls.mapIndexed { index, call ->
            call.copy(recordings = assignments[index])
        } + unmatched.mapIndexed { index, recording ->
            CallHistoryEntry(
                id = -index.toLong() - 1,
                number = recording.number,
                name = recording.title,
                timestamp = recording.timestamp,
                durationSeconds = recording.durationSeconds,
                direction = recording.direction,
                recordings = listOf(recording),
            )
        }).sortedByDescending { it.timestamp }
    }

    private fun sameNumber(a: String, b: String): Boolean {
        val x = a.filter(Char::isDigit)
        val y = b.filter(Char::isDigit)
        // Unknown/private numbers are not sufficient evidence for a match.
        if (x.length < 7 || y.length < 7) return false
        return x == y || (x.takeLast(9) == y.takeLast(9) && x.length >= 9 && y.length >= 9)
    }
}
