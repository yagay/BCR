/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Native call-history-style browser. Only BCR recordings are shown.
 */
package com.chiller3.bcr.manager

import android.content.ClipData
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chiller3.bcr.RecorderProvider
import com.chiller3.bcr.settings.SettingsActivity
import com.chiller3.bcr.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                ManagerScreen(onSettings = {
                    startActivity(Intent(this, SettingsActivity::class.java))
                })
            }
        }
    }
}

/** Stable grouping key: unknown/private callers must never collapse into one contact. */
private fun recordingGroupKey(file: ManagedRecording): String {
    val digits = file.number.filter(Char::isDigit)
    if (digits.length >= 7) return "number:$digits"
    val name = file.title.trim().lowercase()
    if (name.isBlank() || name in setOf("unknown", "<unknown>", "private", "blocked", "anonymous", "unavailable")) {
        return "recording:${file.uri}"
    }
    return "name:$name"
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ManagerScreen(onSettings: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { RecordingRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var recordings by remember { mutableStateOf<List<ManagedRecording>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("all") }
    var expanded by remember { mutableStateOf<String?>(null) }
    val historyListState = rememberLazyListState()
    var active by remember { mutableStateOf<ManagedRecording?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Re-index completed BCR recordings whenever the manager returns to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var skipInitialResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (skipInitialResume) skipInitialResume = false
                else refresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var operationMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) { repository.scan() }
            recordings = result
            val known = result.map { it.uri.toString() }.toSet()
            selected.removeAll { it !in known }
            if (active?.uri?.toString() !in known) active = null
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Scan failed"
        } finally {
            loading = false
        }
    }

    // Defer slow duration probing until a contact is opened. This keeps
    // initial history loading responsive even with thousands of recordings.
    LaunchedEffect(expanded) {
        val key = expanded ?: return@LaunchedEffect
        val missing = recordings.filter {
            recordingGroupKey(it) == key && it.durationSeconds <= 0L
        }
        if (missing.isNotEmpty()) {
            val durations = withContext(Dispatchers.IO) {
                missing.associate { it.uri.toString() to repository.readDuration(it) }
            }
            recordings = recordings.map { file ->
                val duration = durations[file.uri.toString()] ?: 0L
                if (file.durationSeconds <= 0 && duration > 0) {
                    file.copy(durationSeconds = duration)
                } else file
            }
        }
    }

    val filtered = recordings.filter { r ->
        (direction == "all" || r.direction == direction ||
            (direction == "other" && r.direction !in setOf("in", "out"))) &&
            (r.title.contains(query, true) || r.number.contains(query, true) ||
                r.file.name.orEmpty().contains(query, true))
    }
    val groups = filtered.groupBy(::recordingGroupKey)
        .values.map { it.sortedByDescending(ManagedRecording::timestamp) }
        .sortedByDescending { it.first().timestamp }


    // Calculate the index in the *collapsed* list. Date headers count as items,
    // but the old expanded recording rows must not be counted: those are removed
    // on the same remeasure as the new scroll request.
    fun headerIndex(key: String): Int {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        var previousDay: String? = null
        var index = 0
        for (group in groups) {
            val day = dateFormat.format(Date(group.first().timestamp))
            if (day != previousDay) {
                index++
                previousDay = day
            }
            if (recordingGroupKey(group.first()) == key) return index
            index++
        }
        return 0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) "Recordings" else "${selected.size} selected") },
                actions = {
                    TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh") }
                    TextButton(onClick = onSettings) { Text("Settings") }
                },
            )
        },

    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                label = { Text("Search contact, number or file") }, singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("all" to "All", "in" to "Incoming", "out" to "Outgoing", "other" to "Other")
                    .forEach { (value, label) ->
                        FilterChip(
                            selected = direction == value,
                            onClick = { direction = value },
                            label = { Text(label) },
                        )
                    }
            }
            operationMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = {
                        selected.clear()
                        selected.addAll(filtered.map { it.uri.toString() })
                    }) { Text("Select all") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                    TextButton(onClick = { selected.clear() }) { Text("Cancel") }
                }
            }
            when {
                loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                error != null -> Text("Unable to scan recordings: $error", Modifier.padding(16.dp))
                groups.isEmpty() -> Text("No recordings found", Modifier.padding(16.dp))
                else -> LazyColumn(Modifier.weight(1f), state = historyListState) {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    var previousDay: String? = null
                    groups.forEach { group ->
                        val first = group.first()
                        val day = dateFormat.format(Date(first.timestamp))
                        if (day != previousDay) {
                            item(key = "day:$day") { HistoryDateHeader(day) }
                            previousDay = day
                        }
                        val groupKey = recordingGroupKey(first)
                        item(key = "group:$groupKey") {
                            CallHistoryRow(
                                title = first.title,
                                detail = "${directionLabel(first.direction)} · ${first.dateLabel}",
                                count = group.size,
                                expanded = expanded == groupKey,
                                onClick = {
                                    if (selected.isEmpty()) {
                                        // Collapse/expand and update the scroll anchor in
                                        // the SAME frame. An asynchronous scroll after
                                        // collapsing can let LazyColumn preserve an obsolete
                                        // child index and jump unrelated contacts offscreen.
                                        historyListState.requestScrollToItem(headerIndex(groupKey))
                                        active = null
                                        expanded = if (expanded == groupKey) null else groupKey
                                    } else {
                                        group.forEach { file ->
                                            val id = file.uri.toString()
                                            if (id in selected) selected.remove(id) else selected.add(id)
                                        }
                                    }
                                },
                                onLongClick = {
                                    historyListState.requestScrollToItem(headerIndex(groupKey))
                                    active = null
                                    expanded = groupKey
                                    group.forEach { file ->
                                        val id = file.uri.toString()
                                        if (id !in selected) selected.add(id)
                                    }
                                },
                            )
                        }
                        if (expanded == groupKey) {
                            val phoneNumbers = group.mapNotNull { dialablePhoneNumber(it.number) }
                                .distinctBy { it.filter(Char::isDigit) }
                            item(key = "contact-details:$groupKey") {
                                RecordingContactDetails(
                                    name = first.title,
                                    numbers = phoneNumbers,
                                    onDial = { number ->
                                        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                                            data = Uri.fromParts("tel", number, null)
                                        }
                                        runCatching {
                                            context.startActivity(dialIntent)
                                        }.onFailure {
                                            operationMessage = it.localizedMessage
                                                ?: "Unable to open the phone dialer"
                                        }
                                    },
                                )
                            }
                            items(group, key = { file -> "recording:${file.uri}" }) { file ->
                                val id = file.uri.toString()
                                Column(Modifier.fillMaxWidth().animateContentSize()) {
                                    RecordingHistoryRow(
                                        recording = file,
                                        checked = if (selected.isEmpty()) null else id in selected,
                                        onClick = {
                                            if (selected.isNotEmpty()) {
                                                if (id in selected) selected.remove(id)
                                                else selected.add(id)
                                            } else {
                                                active = if (active?.uri == file.uri) null else file
                                            }
                                        },
                                        onLongClick = {
                                            active = null
                                            if (id !in selected) selected.add(id)
                                        },
                                        onPlay = {
                                            active = if (active?.uri == file.uri) null else file
                                        },
                                    )
                                    if (selected.isEmpty() && active?.uri == file.uri) {
                                        key(file.uri) {
                                            RecordingPlayer(
                                                recording = file,
                                                onClose = { active = null },
                                                onShare = {
                                                    val uri = RecorderProvider.fromOrigUri(file.uri)
                                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                                        type = file.file.type ?: "audio/*"
                                                        putExtra(Intent.EXTRA_STREAM, uri)
                                                        clipData = ClipData.newUri(context.contentResolver, "Recording", uri)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    runCatching {
                                                        context.startActivity(
                                                            Intent.createChooser(intent, "Share recording")
                                                        )
                                                    }.onFailure {
                                                        operationMessage = it.localizedMessage ?: "Sharing failed"
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "divider:$groupKey") {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 68.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("Delete recordings?") },
            text = { Text("Audio files and corresponding JSON metadata will be permanently removed.") },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    val files = recordings.filter { it.uri.toString() in selected }
                    if (files.any { it.uri == active?.uri }) active = null
                    scope.launch {
                        try {
                            val deleted = withContext(Dispatchers.IO) { repository.delete(files) }
                            if (deleted == files.size) {
                                selected.clear()
                                operationMessage = "$deleted recording(s) deleted"
                            } else {
                                operationMessage = "$deleted of ${files.size} deleted. Remaining recordings were kept."
                            }
                            confirmDelete = false
                            refresh++
                        } catch (e: Exception) {
                            operationMessage = e.localizedMessage ?: "Delete failed"
                            confirmDelete = false
                        } finally {
                            deleting = false
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { confirmDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun RecordingPlayer(
    recording: ManagedRecording,
    onClose: () -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(1f) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(recording.uri) {
        val mp = MediaPlayer()
        var released = false
        player = null
        playing = false
        position = 0f
        duration = 1f
        error = null
        try {
            mp.setDataSource(context, recording.uri)
            mp.setOnPreparedListener {
                if (!released) {
                    duration = mp.duration.coerceAtLeast(1).toFloat()
                    player = mp
                    runCatching {
                        mp.start()
                        playing = true
                    }.onFailure { error = it.localizedMessage ?: "Playback failed" }
                }
            }
            mp.setOnCompletionListener {
                if (!released) {
                    playing = false
                    position = 0f
                    runCatching { mp.seekTo(0) }
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                if (!released) {
                    playing = false
                    error = "Cannot play this recording"
                }
                true
            }
            // Never perform synchronous prepare() on the main/UI thread.
            mp.prepareAsync()
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Playback failed"
            runCatching { mp.release() }
            released = true
        }
        onDispose {
            released = true
            runCatching {
                mp.setOnPreparedListener(null)
                mp.setOnCompletionListener(null)
                mp.setOnErrorListener(null)
                mp.release()
            }
            player = null
            playing = false
        }
    }
    LaunchedEffect(player, playing) {
        while (playing) {
            position = runCatching { player?.currentPosition?.toFloat() ?: 0f }.getOrDefault(0f)
            delay(250)
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 68.dp, end = 16.dp, bottom = 12.dp)) {
        Text(recording.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Slider(
            value = position.coerceIn(0f, duration),
            onValueChange = {
                position = it
                player?.seekTo(it.toInt())
            },
            valueRange = 0f..duration,
            enabled = player != null,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = player != null, onClick = {
                player?.let {
                    if (it.isPlaying) { it.pause(); playing = false }
                    else { it.start(); playing = true }
                }
            }) { Text(if (playing) "Pause" else "Play") }
            TextButton(onClick = onShare) { Text("Share") }
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}
