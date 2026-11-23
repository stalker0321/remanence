package dev.hryshyn.remanence.ui.capsule

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.PowerManager
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.crypto.ManifestVideo
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.V5Decor
import kotlinx.coroutines.delay

/**
 * One sound at a time on an open card (ADR-021): the voice note and the video
 * square never sound together. Whoever starts sounding claims the card and the
 * previous owner is told to stop. The site's `soundArbiter` is the same rule.
 */
class CardSound {
    private var owner: Any? = null
    private var stop: (() -> Unit)? = null

    fun claim(claimant: Any, onStop: () -> Unit) {
        val previous = stop
        val previousOwner = owner
        owner = claimant
        stop = onStop
        if (previous != null && previousOwner !== claimant) previous()
    }

    fun release(claimant: Any) {
        if (owner === claimant) {
            owner = null
            stop = null
        }
    }
}

/** Plays the video square; [sounding] and [elapsedMs] drive its overlay. */
interface VideoPlayer {
    val sounding: Boolean
    val elapsedMs: Int

    /** True once a frame is on the surface; until then the card's poster shows. */
    val showing: Boolean

    fun attach(surface: Surface?)

    /** Plays from the start with sound, or back to the silent loop while it sounds. */
    fun press()

    fun release()
}

/**
 * The platform player over the in-memory film. It loops without sound while
 * the card is open (or rests on the poster when [still]); a press plays it
 * from the start with sound and claims the card's sound; a second press, the
 * end of the film, or the voice note starting puts it back to the silent loop.
 */
internal class MediaVideoPlayer(
    private val film: VoiceSound,
    private val card: CardSound,
    private val still: Boolean,
) : VideoPlayer {
    override var sounding by mutableStateOf(false)
        private set
    override var elapsedMs by mutableIntStateOf(0)
        private set
    override var showing by mutableStateOf(false)
        private set

    private var player: MediaPlayer? = null
    private var prepared = false

    override fun attach(surface: Surface?) {
        if (surface == null) {
            player?.setSurface(null)
            return
        }
        val existing = player
        if (existing != null) {
            existing.setSurface(surface)
            return
        }
        val created = MediaPlayer()
        player = created
        created.setDataSource(object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                film.read(position, buffer, offset, size)

            override fun getSize(): Long = film.size.toLong()

            override fun close() = Unit
        })
        created.setSurface(surface)
        created.setVolume(0f, 0f)
        created.isLooping = true
        created.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) showing = true
            false
        }
        created.setOnPreparedListener {
            prepared = true
            if (!still) it.start()
        }
        created.setOnCompletionListener { quiet() }
        created.setOnErrorListener { _, _, _ ->
            sounding = false
            showing = false
            card.release(this)
            true
        }
        created.prepareAsync()
    }

    override fun press() {
        val current = player ?: return
        if (!prepared) return
        if (sounding) {
            quiet()
            return
        }
        card.claim(this) { quiet() }
        current.isLooping = false
        current.setVolume(1f, 1f)
        current.seekTo(0)
        current.start()
        elapsedMs = 0
        sounding = true
    }

    /** Polled while it sounds, for the length and the progress line. */
    fun tick() {
        val current = player ?: return
        if (sounding && prepared) elapsedMs = current.currentPosition
    }

    private fun quiet() {
        val current = player ?: return
        card.release(this)
        sounding = false
        current.setVolume(0f, 0f)
        current.isLooping = true
        if (still) {
            if (current.isPlaying) current.pause()
            current.seekTo(0)
        } else if (!current.isPlaying) {
            current.seekTo(0)
            current.start()
        }
    }

    override fun release() {
        card.release(this)
        player?.release()
        player = null
        prepared = false
        sounding = false
        showing = false
    }
}

/** No moving pictures when the phone asks for less motion or saves power. */
internal fun preferStill(context: Context): Boolean {
    val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return scale == 0f || power?.isPowerSaveMode == true
}

/** One player per open film, released when the card leaves the screen. */
@Composable
internal fun rememberVideoPlayer(film: VoiceSound, card: CardSound): VideoPlayer {
    val context = LocalContext.current
    val player = remember(film, card) { MediaVideoPlayer(film, card, preferStill(context)) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, player.sounding) {
        while (player.sounding) {
            player.tick()
            delay(100)
        }
    }
    return player
}

/**
 * The video square over its first frame's tile (ADR-021, the owner's look of
 * 23 November): viewfinder corners, a red dot and the length, in the tile's
 * own shape. The film sits where the poster's crop puts the first frame, so
 * nothing jumps when it starts. The site's `VideoSquare` is the same rule.
 */
@Composable
internal fun VideoSquare(scene: CollageRenderer.Scene, video: ManifestVideo, posterSide: Int, player: VideoPlayer) {
    val photo = scene.photos.firstOrNull { it.contentId == video.posterContentId } ?: return
    val label = stringResource(if (player.sounding) R.string.capsule_video_mute else R.string.capsule_video_watch)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = maxWidth.value / scene.canvasWidth
        val tile = photo.destination
        val source = photo.source
        val filmWidth = tile.width * posterSide.toFloat() / source.width
        val filmHeight = tile.height * posterSide.toFloat() / source.height
        Box(
            Modifier
                .absoluteOffset((tile.x * scale).dp, (tile.y * scale).dp)
                .size((tile.width * scale).dp, (tile.height * scale).dp)
                .clip(tileShape(scene, photo))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = label,
                    onClick = player::press,
                )
                .semantics {
                    contentDescription = label
                    selected = player.sounding
                }
                .testTag("capsule_video_square"),
        ) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null

                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                surface = Surface(texture).also(player::attach)
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                player.attach(null)
                                surface?.release()
                                surface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .absoluteOffset((-source.x * tile.width.toFloat() / source.width * scale).dp, (-source.y * tile.height.toFloat() / source.height * scale).dp)
                    .requiredSize((filmWidth * scale).dp, (filmHeight * scale).dp)
                    // Until a frame is on the surface the card's own poster shows through.
                    .alpha(if (player.showing) 1f else 0f),
            )
            ViewfinderMarks(tile.width * scale, tile.height * scale, player, video.durationMs)
        }
    }
}

@Composable
private fun ViewfinderMarks(widthDp: Float, heightDp: Float, player: VideoPlayer, durationMs: Int) {
    val shade = Shadow(Color(0x73000000), Offset(0f, 1f), 3f)
    Canvas(Modifier.size(widthDp.dp, heightDp.dp)) {
        val stroke = 2.2.dp.toPx()
        val inset = size.width * .07f
        val arm = size.width * .12f
        val corners = listOf(
            Offset(inset, inset) to Offset(1f, 1f),
            Offset(size.width - inset, inset) to Offset(-1f, 1f),
            Offset(size.width - inset, size.height - inset) to Offset(-1f, -1f),
            Offset(inset, size.height - inset) to Offset(1f, -1f),
        )
        for ((at, direction) in corners) {
            val path = Path().apply {
                moveTo(at.x, at.y + direction.y * arm)
                lineTo(at.x, at.y)
                lineTo(at.x + direction.x * arm, at.y)
            }
            drawPath(path, Color(0x55000000), style = Stroke(stroke + 1.5f, cap = StrokeCap.Square))
            drawPath(path, Color.White, style = Stroke(stroke, cap = StrokeCap.Square))
        }
        drawCircle(Color(0xFFFF3B30), radius = size.width * .026f, center = Offset(size.width * .215f, size.height * .14f))
        if (player.sounding) {
            val done = (player.elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
            drawRect(Color(0xFFFF3B30), Offset(0f, size.height * .975f), Size(size.width * done, size.height * .025f))
        }
    }
    val seconds = (if (player.sounding) player.elapsedMs else durationMs) / 1000
    Text(
        text = "%d:%02d".format(seconds / 60, seconds % 60),
        color = Color.White,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = (widthDp * .075f).coerceIn(8f, 14f).sp, shadow = shade),
        modifier = Modifier.absoluteOffset((widthDp * .27f).dp, (heightDp * .14f - (widthDp * .05f).coerceIn(5f, 9f)).dp),
    )
}

/** The tile's own aperture, as the card paints the photo under it. */
private fun tileShape(scene: CollageRenderer.Scene, photo: CollageRenderer.ScenePhoto): Shape = when (photo.shape) {
    GeneratorExpression.PhotoShape.RECT -> RectangleShape
    GeneratorExpression.PhotoShape.ELLIPSE -> GenericShape { size, _ -> addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }
    GeneratorExpression.PhotoShape.ROUND -> GenericShape { size, _ ->
        val radii = V5Decor.roundRadiiPx(scene.grammarId, photo.destination.width, photo.destination.height)
        val sx = size.width / photo.destination.width
        val sy = size.height / photo.destination.height
        addRoundRect(
            RoundRect(
                left = 0f, top = 0f, right = size.width, bottom = size.height,
                topLeftCornerRadius = CornerRadius(radii[0] * sx, radii[1] * sy),
                topRightCornerRadius = CornerRadius(radii[2] * sx, radii[3] * sy),
                bottomRightCornerRadius = CornerRadius(radii[4] * sx, radii[5] * sy),
                bottomLeftCornerRadius = CornerRadius(radii[6] * sx, radii[7] * sy),
            ),
        )
    }
}
