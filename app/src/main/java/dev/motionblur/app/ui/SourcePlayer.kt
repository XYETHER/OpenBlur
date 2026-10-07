package dev.motionblur.app.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

fun timeLabel(ms: Long): String = String.format(Locale.US, "%02d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000)

class SourcePlayback(val player: ExoPlayer) {
    var playing by mutableStateOf(false)
    var ready by mutableStateOf(false)
    var failure by mutableStateOf(false)
    var sourceStartMs = 0L
    var sourceEndMs = 1L
    val leasedPreviews = mutableSetOf<java.io.File>()
    fun sourcePosition() = (sourceStartMs + player.currentPosition).coerceIn(sourceStartMs, sourceEndMs - 1)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun rememberSourcePlayback(state: EditorState): SourcePlayback {
    val context = LocalContext.current.applicationContext
    val sourceUri = state.source!!.uri
    val playback = remember(sourceUri) {
        SourcePlayback(ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            playWhenReady = false
        })
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(playback, lifecycle) {
        val player = playback.player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playback.playing = isPlaying }
            override fun onPlaybackStateChanged(status: Int) {
                playback.ready = status == Player.STATE_READY || status == Player.STATE_ENDED
                if (status == Player.STATE_ENDED) {
                    player.pause()
                    state.position = playback.sourceEndMs - 1
                }
            }
            override fun onPlayerError(error: PlaybackException) { playback.failure = true }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { player.pause(); state.position = playback.sourcePosition(); state.saveClip() }
        }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            player.pause()
            state.saveClip()
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
            state.releasePreviewsAfterPlayback(playback.leasedPreviews)
        }
    }
    val artifact = state.previewArtifact.takeIf { state.preview }
    val processed = artifact != null && state.showProcessedPreview
    val uri = if (processed) Uri.fromFile(artifact!!.file) else sourceUri
    val start = artifact?.sourceStartMs ?: state.timeline!!.start
    val end = artifact?.sourceEndMs ?: state.timeline!!.end
    LaunchedEffect(playback, uri, start, end) {
        val player = playback.player
        if (processed && playback.leasedPreviews.add(artifact!!.file)) state.retainPreviewForPlayback(artifact.file)
        val resume = player.playWhenReady && !state.isRendering
        playback.sourceStartMs = start
        playback.sourceEndMs = end
        playback.failure = false
        playback.ready = false
        val item = MediaItem.Builder().setUri(uri).apply {
            if (!processed) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(start).setEndPositionMs(end).build())
        }.build()
        player.setMediaItem(item, (state.position - start).coerceIn(0, end - start - 1))
        player.prepare()
        player.playWhenReady = resume
    }
    LaunchedEffect(playback, state.seekSerial) {
        playback.player.pause()
        playback.player.seekTo((state.seekRequest - start).coerceIn(0, end - start - 1))
    }
    LaunchedEffect(playback, state.isRendering) { if (state.isRendering) playback.player.pause() }
    LaunchedEffect(playback) {
        while (true) {
            if (playback.player.isPlaying) state.position = playback.sourcePosition()
            delay(60)
        }
    }
    return playback
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun SourceViewport(state: EditorState, playback: SourcePlayback) {
    val clip = state.source!!
    val artifact = state.previewArtifact.takeIf { state.preview }
    val start = artifact?.sourceStartMs ?: state.timeline!!.start
    val end = artifact?.sourceEndMs ?: state.timeline!!.end
    val aspect = if (clip.width > 0 && clip.height > 0) clip.width.toFloat() / clip.height else 16f / 9f
    Column(Modifier.fillMaxWidth().clip(Studio.Card).background(Studio.Surface).border(1.dp, Studio.Line, Studio.Card)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val height = (maxWidth / aspect).coerceIn(194.dp, 250.dp)
            AndroidView(factory = { context -> PlayerView(context).apply {
                player = playback.player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            } }, update = { it.player = playback.player }, modifier = Modifier.fillMaxWidth().height(height).background(Studio.Ink))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (playback.player.playWhenReady) playback.player.pause()
                else if (!playback.failure) {
                    if (playback.player.playbackState == Player.STATE_ENDED || state.position >= end - 80) {
                        playback.player.seekTo(0); state.position = start
                    }
                    playback.player.play()
                }
            }, enabled = !state.isRendering && !playback.failure,
                modifier = Modifier.size(46.dp).background(Studio.Text, Studio.Pill).semantics {
                    contentDescription = if (playback.playing) "Pause video" else "Play video"
                }) {
                Canvas(Modifier.size(17.dp)) {
                    if (playback.playing) {
                        drawRect(Studio.Ink, Offset(size.width * .18f, size.height * .14f), Size(size.width * .23f, size.height * .72f))
                        drawRect(Studio.Ink, Offset(size.width * .59f, size.height * .14f), Size(size.width * .23f, size.height * .72f))
                    } else drawPath(Path().apply {
                        moveTo(size.width * .22f, size.height * .12f); lineTo(size.width * .22f, size.height * .88f)
                        lineTo(size.width * .86f, size.height * .5f); close()
                    }, Studio.Ink)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(timeLabel(state.position), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { playback.player.seekTo(0); state.position = start; playback.player.play() },
                enabled = !state.isRendering && !playback.failure) { Text("Replay") }
        }
        Slider(value = state.position.coerceIn(start, end - 1).toFloat(), onValueChange = { state.seek(it.toLong()) },
            valueRange = start.toFloat()..maxOf(start.toFloat() + 1f, (end - 1).toFloat()),
            modifier = Modifier.padding(horizontal = 14.dp).semantics { contentDescription = "Seek video" })
        if (artifact != null) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "Original", true to "Processed").forEach { (processed, label) ->
                FilterChip(selected = state.showProcessedPreview == processed, onClick = {
                    if (state.showProcessedPreview != processed) {
                        state.position = playback.sourcePosition()
                        state.togglePreviewResult()
                    }
                }, label = { Text(label) })
            }
        }
        if (playback.failure) Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Help("Video playback failed.", Modifier.weight(1f))
            TextButton(onClick = { playback.failure = false; playback.player.prepare() }) { Text("Retry playback") }
        } else if (!playback.ready) Help("Loading video…", Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}
