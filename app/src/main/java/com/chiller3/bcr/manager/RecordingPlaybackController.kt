/* SPDX-License-Identifier: GPL-3.0-only */
package com.chiller3.bcr.manager

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One player for the whole manager screen. Composition/recycling of a LazyColumn
 * row never owns the MediaPlayer; the screen closes it explicitly.
 */
internal class RecordingPlaybackController(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var token = 0
    var fileUri by mutableStateOf<Uri?>(null)
        private set
    var ready by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(1)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var speed by mutableFloatStateOf(1f)
        private set

    fun open(uri: Uri?) {
        if (fileUri == uri) return
        close()
        if (uri == null) return
        fileUri = uri
        val generation = ++token
        val mp = MediaPlayer()
        mediaPlayer = mp
        try {
            mp.setDataSource(context, uri)
            mp.setOnPreparedListener { player ->
                if (generation == token && mediaPlayer === player) {
                    durationMs = player.duration.coerceAtLeast(1)
                    ready = true
                    runCatching {
                        player.playbackParams = PlaybackParams().setSpeed(speed)
                        player.start()
                        playing = true
                    }.onFailure { error = it.localizedMessage ?: "Playback failed" }
                }
            }
            mp.setOnCompletionListener {
                if (generation == token) {
                    playing = false
                    positionMs = 0
                    runCatching { mp.seekTo(0) }
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                if (generation == token) {
                    playing = false
                    ready = false
                    error = "Cannot play this recording"
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Playback failed"
            runCatching { mp.release() }
            mediaPlayer = null
        }
    }

    fun toggle() {
        val mp = mediaPlayer ?: return
        if (!ready) return
        runCatching {
            if (mp.isPlaying) {
                mp.pause()
                playing = false
            } else {
                mp.playbackParams = PlaybackParams().setSpeed(speed)
                mp.start()
                playing = true
            }
        }.onFailure { error = it.localizedMessage ?: "Playback failed" }
    }

    fun setSpeed(value: Float) {
        speed = value
        val mp = mediaPlayer ?: return
        if (ready) runCatching {
            mp.playbackParams = PlaybackParams().setSpeed(value)
        }.onFailure { error = it.localizedMessage ?: "Cannot change playback speed" }
    }

    fun seek(position: Int) {
        val millis = position.coerceIn(0, durationMs)
        positionMs = millis
        if (ready) runCatching { mediaPlayer?.seekTo(millis) }
            .onFailure { error = it.localizedMessage ?: "Cannot seek" }
    }

    fun skip(seconds: Int) = seek(positionMs + seconds * 1000)

    fun pollPosition() {
        if (ready && playing) {
            positionMs = runCatching { mediaPlayer?.currentPosition ?: positionMs }
                .getOrDefault(positionMs).coerceIn(0, durationMs)
        }
    }

    fun pause() {
        val mp = mediaPlayer ?: return
        if (ready && playing) {
            runCatching { mp.pause() }
            playing = false
        }
    }

    fun close() {
        token++
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) runCatching {
            mp.setOnPreparedListener(null)
            mp.setOnCompletionListener(null)
            mp.setOnErrorListener(null)
            mp.release()
        }
        fileUri = null
        ready = false
        playing = false
        positionMs = 0
        durationMs = 1
        error = null
    }
}
