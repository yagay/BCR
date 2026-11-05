/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Native call-history-style browser. Only BCR recordings are shown.
 */
package com.chiller3.bcr.manager

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chiller3.bcr.R
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
    val digits = (file.normalizedNumber.ifBlank { file.number }).filter(Char::isDigit)
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
    val contacts = remember { ContactDirectory(context.applicationContext) }
    val playback = remember { RecordingPlaybackController(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var recordings by remember { mutableStateOf<List<ManagedRecording>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf("all") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var showAllExpanded by remember { mutableStateOf(false) }
    val historyListState = rememberLazyListState()
    var active by remember { mutableStateOf<ManagedRecording?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Re-index completed BCR recordings whenever the manager returns to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(playback) { onDispose { playback.close() } }
    LaunchedEffect(active?.uri) { playback.open(active?.uri) }
    LaunchedEffect(playback.playing, playback.fileUri) {
        while (playback.playing) {
            playback.pollPosition()
            delay(200)
        }
    }
    DisposableEffect(lifecycleOwner) {
        var skipInitialResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playback.pause()
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
                title = {
                    Text(
                        if (selected.isEmpty()) "Recordings" else "${selected.size} selected",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(onClick = {
                        searchVisible = !searchVisible
                        if (!searchVisible) query = ""
                    }) {
                        Icon(
                            painterResource(
                                if (searchVisible) R.drawable.ic_manager_close
                                else R.drawable.ic_manager_search
                            ),
                            contentDescription = if (searchVisible) "Close search" else "Search recordings",
                        )
                    }
                    IconButton(onClick = { refresh++ }, enabled = !loading) {
                        Icon(painterResource(R.drawable.ic_manager_refresh), contentDescription = "Refresh recordings")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_manager_settings), contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searchVisible) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Search contact, number or file") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_manager_search), contentDescription = null) },
                    singleLine = true,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("all" to "All", "in" to "Incoming", "out" to "Outgoing", "other" to "Other")
                    .forEach { (value, label) ->
                        FilterChip(
                            selected = direction == value,
                            onClick = { direction = value },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            shape = RoundedCornerShape(50),
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
                            item(key = "day:$day") {
                                HistoryDateHeader(
                                    DateFormat.getDateInstance(DateFormat.MEDIUM)
                                        .format(Date(first.timestamp))
                                )
                            }
                            previousDay = day
                        }
                        val groupKey = recordingGroupKey(first)
                        item(key = "group:$groupKey") {
                            val contactNumber = group.firstOrNull {
                                dialablePhoneNumber(it.number) != null
                            }?.number.orEmpty()
                            val profile by produceState<ContactProfile?>(
                                initialValue = null, key1 = contactNumber,
                            ) {
                                value = withContext(Dispatchers.IO) {
                                    contacts.lookup(contactNumber)
                                }
                            }
                            CallHistoryRow(
                                title = first.title,
                                profile = profile,
                                detail = DateFormat.getTimeInstance(DateFormat.SHORT)
                                    .format(Date(first.timestamp)) + " · ${group.size} recording" +
                                    if (group.size == 1) "" else "s",
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
                                        showAllExpanded = false
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
                                    showAllExpanded = false
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
                            items(if (showAllExpanded) group else group.take(4),
                                key = { file -> "recording:${file.uri}" }) { file ->
                                val id = file.uri.toString()
                                var detailsVisible by remember(file.uri) { mutableStateOf(false) }
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
                                        onDetails = { detailsVisible = !detailsVisible },
                                    )
                                    if (detailsVisible) {
                                        RecordingFileDetails(file)
                                    }
                                    if (selected.isEmpty() && active?.uri == file.uri) {
                                        key(file.uri) {
                                            RecordingPlayer(
                                                recording = file,
                                                playback = playback,
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
                        if (expanded == groupKey && group.size > 4) {
                            item(key = "more:$groupKey") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 68.dp, end = 16.dp),
                                    horizontalArrangement = Arrangement.Start,
                                ) {
                                    TextButton(
                                        onClick = {
                                            if (showAllExpanded) {
                                                historyListState.requestScrollToItem(headerIndex(groupKey))
                                            }
                                            showAllExpanded = !showAllExpanded
                                        },
                                    ) {
                                        Text(if (showAllExpanded) "Show fewer"
                                            else "View remaining ${group.size - 4} recordings",
                                            style = MaterialTheme.typography.labelMedium)
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            painterResource(
                                                if (showAllExpanded) R.drawable.ic_manager_chevron_up
                                                else R.drawable.ic_manager_chevron_down
                                            ),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
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
                            val result = withContext(Dispatchers.IO) { repository.delete(files, recordings) }
                            val deleted = result.deleted
                            if (deleted == files.size && result.metadataFailures == 0) {
                                selected.clear()
                                operationMessage = "$deleted recording(s) deleted"
                            } else {
                                operationMessage = "$deleted of ${files.size} audio files deleted; " +
                                    "${result.metadataFailures} metadata files could not be removed."
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
    playback: RecordingPlaybackController,
    onClose: () -> Unit,
    onShare: () -> Unit,
) {
    // Only the visual controls are inside the lazy row. The MediaPlayer itself
    // is screen-scoped and cannot be recycled by LazyColumn.
    val uri = recording.uri
    val isCurrent = playback.fileUri == uri
    val duration = if (isCurrent) playback.durationMs.coerceAtLeast(1) else 1
    val played = if (isCurrent) playback.positionMs else 0
    val ready = isCurrent && playback.ready
    var dragFraction by remember(uri) { mutableStateOf<Float?>(null) }
    val progress = dragFraction ?: played.toFloat() / duration
    val playedColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f)

    Column(
        Modifier.fillMaxWidth()
            .padding(start = 68.dp, end = 16.dp, bottom = 6.dp, top = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        playback.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(millisLabel((progress * duration).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Canvas(
                Modifier.weight(1f).height(32.dp)
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            (progress * duration).coerceIn(0f, duration.toFloat()),
                            0f..duration.toFloat(),
                        )
                    }
                    .pointerInput(uri, duration, ready) {
                        detectTapGestures { offset ->
                            if (ready) playback.seek(
                                (offset.x / size.width.coerceAtLeast(1) * duration).toInt()
                            )
                        }
                    }
                    .pointerInput(uri, duration, ready) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                if (ready) dragFraction =
                                    (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                if (ready) dragFraction =
                                    (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                dragFraction?.let { playback.seek((it * duration).toInt()) }
                                dragFraction = null
                            },
                            onDragCancel = { dragFraction = null },
                        )
                    },
            ) {
                val y = size.height / 2f
                val x = size.width * progress.coerceIn(0f, 1f)
                drawLine(trackColor, Offset(0f, y), Offset(size.width, y),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                drawLine(playedColor, Offset(0f, y), Offset(x, y),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(playedColor, radius = 5.dp.toPx(), center = Offset(
                    x.coerceIn(5.dp.toPx(), (size.width - 5.dp.toPx()).coerceAtLeast(5.dp.toPx())), y))
            }
            Text(millisLabel(duration), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = { playback.skip(-10) }, enabled = ready,
                modifier = Modifier.size(38.dp)) { Text("-10", style = MaterialTheme.typography.labelSmall) }
            IconButton(onClick = { playback.toggle() }, enabled = ready,
                modifier = Modifier.size(38.dp)) {
                Icon(painterResource(if (playback.playing) R.drawable.ic_manager_pause
                    else R.drawable.ic_manager_play),
                    contentDescription = if (playback.playing) "Pause" else "Play",
                    modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { playback.skip(10) }, enabled = ready,
                modifier = Modifier.size(38.dp)) { Text("+10", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = {
                val index = speeds.indexOf(playback.speed).takeIf { it >= 0 } ?: 1
                playback.setSpeed(speeds[(index + 1) % speeds.size])
            }, modifier = Modifier.height(38.dp)) {
                Text("${playback.speed}×", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onShare, modifier = Modifier.size(38.dp)) {
                Icon(painterResource(R.drawable.ic_manager_share),
                    contentDescription = "Share recording", modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
                Icon(painterResource(R.drawable.ic_manager_close),
                    contentDescription = "Close player", modifier = Modifier.size(19.dp))
            }
        }
    }
}

private fun millisLabel(value: Int): String {
    val seconds = value.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(Locale.getDefault(), seconds / 60, seconds % 60)
}
