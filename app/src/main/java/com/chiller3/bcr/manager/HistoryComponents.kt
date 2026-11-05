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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.chiller3.bcr.R
import java.text.DateFormat
import java.util.Date

internal fun durationLabel(seconds: Long): String =
    if (seconds <= 0L) "—" else "%02d:%02d".format(seconds / 60, seconds % 60)

internal fun directionLabel(direction: String): String = when (direction) {
    "in" -> "↙ Incoming"
    "out" -> "↗ Outgoing"
    "conference" -> "◈ Conference"
    else -> "• Recording"
}

/**
 * Only offer calling for actual phone-number-shaped metadata. Never interpret a
 * contact name or recording filename as a telephone number.
 */
internal fun dialablePhoneNumber(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || !trimmed.matches(Regex("""\+?[0-9 ()-]+"""))) return null
    val digits = trimmed.count(Char::isDigit)
    if (digits !in 3..15) return null
    return trimmed
}

@Composable
internal fun RecordingContactDetails(
    numbers: List<String>,
    onDial: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(start = 68.dp, end = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (numbers.isEmpty()) {
            Text(
                "No phone number available",
                modifier = Modifier.padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            numbers.forEach { number ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        number,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { onDial(number) }) {
                        Icon(
                            painterResource(R.drawable.ic_manager_phone),
                            contentDescription = "Dial $number",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HistoryDateHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 3.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CallHistoryRow(
    title: String,
    detail: String,
    expanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick, onLongClick = onLongClick,
        ).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (title.trim().firstOrNull()?.isLetter() == true) {
                Text(
                    text = title.trim().first().uppercaseChar().toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_manager_person),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // The recording count is in the subtitle, keeping the right edge uncluttered.
        Icon(
            painterResource(
                if (expanded) R.drawable.ic_manager_chevron_up
                else R.drawable.ic_manager_chevron_down
            ),
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
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
        ).padding(start = 68.dp, end = 16.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (checked != null) {
            Checkbox(checked = checked, onCheckedChange = { onClick() })
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(recording.timestamp)),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${when (recording.direction) {
                    "in" -> "↙"
                    "out" -> "↗"
                    "conference" -> "◈"
                    else -> "•"
                }}  ${durationLabel(recording.durationSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (checked == null) {
            IconButton(onClick = onPlay) {
                Icon(
                    painterResource(R.drawable.ic_manager_play),
                    contentDescription = "Play recording",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
