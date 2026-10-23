package dev.hryshyn.remanence.ui.capsule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.ui.collage.CollagePainter
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.hold.HoldTextButton

/** Readable outside the artwork and outside the exported content. */
@Composable
internal fun CapsuleListenAction(track: CapsuleTrackSnapshotV1?, action: (() -> Unit)?) {
    if (track == null || action == null) return
    HoldTextButton(onClick = action, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .testTag("capsule_track_open")) {
        Text(stringResource(R.string.track_open_action, track.title, track.artistDisplay),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Same admitted cue, but viewer furniture: never part of the recorded layer.
 * Geometry, colours and cover/no-cover policy come from the unchanged painter. */
@Composable
internal fun CapsuleMusicCue(scene: CollageRenderer.Scene, images: Map<String, ImageBitmap>) {
    val cue = CollagePainter.drawList(scene, images.mapValues {
        CollageRenderer.SourcePhoto(it.value.width, it.value.height)
    })?.filterIsInstance<CollagePainter.Op.MusicPlay>()?.singleOrNull() ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = minOf(maxWidth.value / scene.canvasWidth, maxHeight.value / scene.canvasHeight)
        val dx = (maxWidth.value - scene.canvasWidth * scale) / 2f
        val dy = (maxHeight.value - scene.canvasHeight * scale) / 2f
        val r = cue.region
        Canvas(Modifier.absoluteOffset((dx + r.x * scale).dp, (dy + r.y * scale).dp)
            .size((r.width * scale).dp, (r.height * scale).dp).testTag("collage_music_play_cue")) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = minOf(size.width, size.height) / 2
            cue.background?.let { drawCircle(Color(it), radius, center) }
            val triangle = Path().apply {
                moveTo(center.x - radius * .25f, center.y - radius * .43f)
                lineTo(center.x + radius * .47f, center.y)
                lineTo(center.x - radius * .25f, center.y + radius * .43f)
                close()
            }
            drawPath(triangle, Color(cue.foreground))
        }
    }
}
