package dev.hryshyn.remanence.ui.create

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.crypto.ManifestVideo
import dev.hryshyn.remanence.ui.collage.SongBand
import dev.hryshyn.remanence.ui.hold.HoldColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The video square in the photo step (ADR-021, the owner's look of 23
 * November): it takes one photo's place. "Record a video" opens the front
 * camera in a square viewfinder; tap to start, tap to stop, at most 30 s; the
 * first frame joins the photos. Never a file from the gallery.
 */
@Composable
internal fun VideoDraft(
    video: RecordedVideo?,
    onVideo: (RecordedVideo?) -> Unit,
    photosFull: Boolean,
    enabled: Boolean,
) {
    val context = LocalContext.current
    var shooting by remember { mutableStateOf(false) }
    var failure by rememberSaveable { mutableStateOf<Int?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) shooting = true else failure = R.string.video_denied
    }
    val open = {
        failure = null
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            shooting = true
        } else {
            permission.launch(needed)
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("video_draft")) {
        when {
            shooting -> VideoCamera(
                onVideo = { taken ->
                    shooting = false
                    onVideo(taken)
                },
                onFailure = { reason ->
                    shooting = false
                    failure = reason
                },
                onCancel = { shooting = false },
            )
            video != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(SongBand.voiceLength(video.durationMs), style = MaterialTheme.typography.bodyMedium,
                    color = HoldColors.Ink, modifier = Modifier.testTag("video_length"))
                OutlinedButton(onClick = open, enabled = enabled, modifier = Modifier.testTag("video_rerecord")) {
                    Text(stringResource(R.string.video_rerecord))
                }
                TextButton(onClick = { onVideo(null) }, enabled = enabled, modifier = Modifier.testTag("video_delete")) {
                    Text(stringResource(R.string.video_delete))
                }
            }
            else -> OutlinedButton(onClick = open, enabled = enabled && !photosFull, modifier = Modifier.testTag("video_record")) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(RecordRed))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.video_record))
            }
        }
        if (!shooting) {
            Spacer(Modifier.height(6.dp))
            val hint = failure ?: if (photosFull && video == null) R.string.video_photos_full else R.string.video_hint
            Text(
                stringResource(hint),
                color = if (failure != null) MaterialTheme.colorScheme.error else HoldColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(if (failure != null) "video_failure" else "video_hint"),
            )
        }
    }
}

@Composable
private fun VideoCamera(onVideo: (RecordedVideo) -> Unit, onFailure: (Int) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VideoSquareRecorder(context) }
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var ready by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }

    fun stop() {
        if (!recording) return
        recording = false
        measuring = true
        scope.launch {
            try {
                onVideo(recorder.finish())
            } catch (_: VideoSquareRecorder.VideoTooLarge) {
                onFailure(R.string.video_too_large)
            } catch (_: VideoSquareRecorder.VideoTooShort) {
                onFailure(R.string.video_too_short)
            } catch (_: Exception) {
                onFailure(R.string.video_failed)
            } finally {
                measuring = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            recorder.open(owner, view.surfaceProvider, VideoSquareRecorder.rotationOf(context))
            ready = true
        } catch (_: Exception) {
            onFailure(R.string.video_failed)
        }
    }
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.elapsedMs
            delay(250)
        }
    }
    // Leaving the step drops a take in progress and frees the camera.
    DisposableEffect(Unit) { onDispose { recorder.close() } }

    Column(Modifier.fillMaxWidth().testTag("video_camera"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(.82f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E2130))) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) { viewfinder(if (recording) RecordRed else Color.White) }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (recording) {
                Button(onClick = ::stop, modifier = Modifier.testTag("video_stop")) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(HoldColors.OnAccent))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.video_stop))
                }
            } else {
                Button(
                    onClick = {
                        try {
                            recorder.record(onLimit = { stop() })
                            elapsed = 0L
                            recording = true
                        } catch (_: Exception) {
                            onFailure(R.string.video_failed)
                        }
                    },
                    enabled = ready && !measuring,
                    modifier = Modifier.testTag("video_start"),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(RecordRed))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.video_start))
                }
            }
            Text(
                stringResource(R.string.voice_elapsed, clock(elapsed), SongBand.voiceLength(ManifestVideo.MAX_MS)),
                style = MaterialTheme.typography.bodyMedium, color = HoldColors.Ink, modifier = Modifier.testTag("video_elapsed"),
            )
            if (!recording && !measuring) {
                TextButton(onClick = onCancel, modifier = Modifier.testTag("video_cancel")) {
                    Text(stringResource(R.string.video_cancel))
                }
            }
        }
    }
}

private val RecordRed = Color(0xFFFF3B30)

/** Whole seconds elapsed, from 0:00 (the band's length rounds up to at least 0:01). */
private fun clock(ms: Long): String {
    val seconds = (ms / 1000).toInt()
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** The viewfinder corners the card draws over the video square. */
internal fun DrawScope.viewfinder(color: Color) {
    val stroke = 2.2.dp.toPx()
    val inset = size.width * .07f
    val arm = size.width * .12f
    listOf(
        Offset(inset, inset) to Offset(1f, 1f),
        Offset(size.width - inset, inset) to Offset(-1f, 1f),
        Offset(size.width - inset, size.height - inset) to Offset(-1f, -1f),
        Offset(inset, size.height - inset) to Offset(1f, -1f),
    ).forEach { (at, direction) ->
        val path = Path().apply {
            moveTo(at.x, at.y + direction.y * arm)
            lineTo(at.x, at.y)
            lineTo(at.x + direction.x * arm, at.y)
        }
        drawPath(path, Color(0x55000000), style = Stroke(stroke + 1.5f, cap = StrokeCap.Square))
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Square))
    }
}
