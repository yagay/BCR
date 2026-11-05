/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Native call-history-style browser. Only BCR recordings are shown.
 */
package com.chiller3.bcr.manager

import android.content.ClipData
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    var active by remember { mutableStateOf<ManagedRecording?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

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

    val filtered = recordings.filter { r ->
        (direction == "all" || r.direction == direction ||
            (direction == "other" && r.direction !in setOf("in", "out"))) &&
            (r.title.contains(query, true) || r.number.contains(query, true) ||
                r.file.name.orEmpty().contains(query, true))
    }
    val groups = filtered.groupBy { r ->
        r.number.filter(Char::isDigit).takeIf { it.length >= 7 }
            ?: r.title.lowercase().trim()
    }.values.map { it.sortedByDescending(ManagedRecording::timestamp) }
        .sortedByDescending { it.first().timestamp }

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
        bottomBar = {
            if (selected.isEmpty()) {
                active?.let { file ->
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
                            context.startActivity(Intent.createChooser(intent, "Share recording"))
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                label = { Text("Search contact, number or file") }, singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(groups, key = { it.first().uri.toString() }) { group ->
                        val first = group.first()
                        val groupKey = first.number.filter(Char::isDigit)
                            .takeIf { it.length >= 7 } ?: first.title.lowercase().trim()
                        val open = expanded == groupKey || selected.isNotEmpty()
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(
                                onClick = { expanded = if (open) null else groupKey },
                                onLongClick = {
                                    if (selected.isEmpty()) selected.add(first.uri.toString())
                                },
                            ).padding(16.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(first.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${group.size} recording(s) · ${first.dateLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(if (open) "⌃" else "⌄")
                        }
                        if (open) group.forEach { file ->
                            val key = file.uri.toString()
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(
                                    onClick = {
                                        if (selected.isNotEmpty()) {
                                            if (key in selected) selected.remove(key) else selected.add(key)
                                        } else active = file
                                    },
                                    onLongClick = { if (key !in selected) selected.add(key) },
                                ).padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                            ) {
                                if (selected.isNotEmpty()) Checkbox(
                                    checked = key in selected,
                                    onCheckedChange = {
                                        if (it && key !in selected) selected.add(key)
                                        else if (!it) selected.remove(key)
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(file.dateLabel)
                                    Text(
                                        "${file.direction.ifBlank { "Call" }} · ${file.durationSeconds}s",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (selected.isEmpty()) {
                                    TextButton(onClick = { active = file }) { Text("Play") }
                                }
                            }
                        }
                        HorizontalDivider()
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
                            withContext(Dispatchers.IO) { repository.delete(files) }
                            selected.clear()
                            confirmDelete = false
                            refresh++
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: "Delete failed"
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
        try {
            mp.setDataSource(context, recording.uri)
            mp.prepare()
            duration = mp.duration.coerceAtLeast(1).toFloat()
            mp.setOnCompletionListener { playing = false; position = 0f }
            player = mp
            mp.start()
            playing = true
            error = null
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Playback failed"
            mp.release()
        }
        onDispose {
            runCatching { mp.release() }
            player = null
        }
    }
    LaunchedEffect(player, playing) {
        while (playing) {
            position = runCatching { player?.currentPosition?.toFloat() ?: 0f }.getOrDefault(0f)
            delay(250)
        }
    }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
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
