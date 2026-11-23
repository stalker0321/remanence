package dev.hryshyn.remanence.ui.capsule

import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.SongBand
import kotlin.math.min

/**
 * A received voice note's decrypted MP4 (ADR-021). It lives in memory only:
 * the player reads it through a [MediaDataSource], nothing is written to
 * disk, and [wipe] zeroes it when the card closes.
 */
class VoiceSound(bytes: ByteArray) {
    private val held = bytes

    internal val size: Int get() = held.size

    internal fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= held.size) return -1
        val count = min(size.toLong(), held.size - position).toInt()
        System.arraycopy(held, position.toInt(), buffer, offset, count)
        return count
    }

    fun wipe() = held.fill(0)

    override fun toString(): String = "VoiceSound(<redacted>)"
}

/** Plays and pauses one voice note; [playing] drives the band. */
interface VoicePlayer {
    val playing: Boolean

    fun toggle()

    fun release()
}

/** The platform player over the in-memory sound; prepared on the first press. */
internal class MediaVoicePlayer(private val sound: VoiceSound) : VoicePlayer {
    override var playing by mutableStateOf(false)
        private set

    private var player: MediaPlayer? = null
    private var prepared = false

    override fun toggle() {
        val current = player ?: MediaPlayer().also { created ->
            player = created
            created.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                    sound.read(position, buffer, offset, size)

                override fun getSize(): Long = sound.size.toLong()

                override fun close() = Unit
            })
            created.setOnPreparedListener {
                prepared = true
                it.start()
                playing = true
            }
            created.setOnCompletionListener {
                it.seekTo(0)
                playing = false
            }
            created.setOnErrorListener { _, _, _ ->
                playing = false
                true
            }
            created.prepareAsync()
            return
        }
        if (!prepared) return
        if (current.isPlaying) {
            current.pause()
            playing = false
        } else {
            current.start()
            playing = true
        }
    }

    override fun release() {
        player?.release()
        player = null
        playing = false
    }
}

/** One player per open sound, released when the card leaves the screen. */
@Composable
internal fun rememberVoicePlayer(
    sound: VoiceSound,
    create: (VoiceSound) -> VoicePlayer = ::MediaVoicePlayer,
): VoicePlayer {
    val player = remember(sound) { create(sound) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * The whole band is the button (ADR-021 §5), the way the whole note turns the
 * card. The card draws the play glyph; while the sound plays, a pause glyph in
 * the same form covers it. The site's `VoiceBand` is the same rule.
 */
@Composable
internal fun VoiceBand(scene: CollageRenderer.Scene, player: VoicePlayer) {
    val music = scene.music ?: return
    val band = music.band ?: return
    val label = stringResource(if (player.playing) R.string.capsule_voice_pause else R.string.capsule_voice_listen)
    val ink = Color(music.color)
    val ground = Color(music.background)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = maxWidth.value / scene.canvasWidth
        val r = music.region
        Box(
            Modifier
                .absoluteOffset((r.x * scale).dp, (r.y * scale).dp)
                .size((r.width * scale).dp, (r.height * scale).dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = label,
                    onClick = player::toggle,
                )
                .semantics {
                    contentDescription = label
                    selected = player.playing
                }
                .testTag("capsule_voice_band"),
        ) {
            if (player.playing) {
                val play = band.play
                Canvas(
                    Modifier
                        .absoluteOffset(((play.x - r.x) * scale).dp, ((play.y - r.y) * scale).dp)
                        .size((play.width * scale).dp, (play.height * scale).dp)
                        .testTag("capsule_voice_pause"),
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = min(size.width, size.height) / 2f
                    when (band.playStyle) {
                        SongBand.PlayStyle.CIRCLE -> {
                            drawCircle(ink, radius, center)
                            pauseBars(radius * .75f, ground)
                        }
                        SongBand.PlayStyle.RING -> {
                            drawCircle(ground, radius, center)
                            drawCircle(ink, radius - scale, center, style = Stroke(width = 2f * scale))
                            pauseBars(radius * .75f, ink)
                        }
                        SongBand.PlayStyle.GLYPH -> {
                            drawRect(ground)
                            pauseBars(min(size.width, size.height), ink)
                        }
                    }
                }
            }
        }
    }
}

/** Two bars in a centred square of [side] px. */
private fun DrawScope.pauseBars(side: Float, color: Color) {
    val top = size.height / 2f - side / 2f
    val bar = side * .3f
    val corner = CornerRadius(bar * .25f)
    drawRoundRect(color, Offset(size.width / 2f - side * .4f, top), Size(bar, side), corner)
    drawRoundRect(color, Offset(size.width / 2f + side * .1f, top), Size(bar, side), corner)
}
