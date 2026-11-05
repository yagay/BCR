/*
 * SPDX-License-Identifier: GPL-3.0-only
 * UI-only call-history presentation; the BCR recording engine remains independent.
 */
package com.chiller3.bcr.manager

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun durationLabel(seconds: Long): String =
    if (seconds <= 0L) "—" else "%02d:%02d".format(seconds / 60, seconds % 60)

internal fun directionLabel(direction: String): String = when (direction) {
    "in" -> "↙ Incoming"
    "out" -> "↗ Outgoing"
    "conference" -> "◈ Conference"
    else -> "• Recording"
}

@Composable
internal fun HistoryDateHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CallHistoryRow(
    title: String,
    detail: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick, onLongClick = onLongClick,
        ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (count > 1) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(count.toString(), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
            }
        }
        Text(if (expanded) "⌃" else "⌄",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleLarge)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecordingHistoryRow(
    recording: ManagedRecording,
    checked: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick, onLongClick = onLongClick,
        ).padding(start = 68.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (checked != null) {
            Checkbox(checked = checked, onCheckedChange = { onClick() })
        }
        Column(Modifier.weight(1f)) {
            Text(recording.dateLabel, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${directionLabel(recording.direction)} · ${durationLabel(recording.durationSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (checked == null) {
            TextButton(onClick = onPlay) { Text("▶ Play") }
        }
    }
}
