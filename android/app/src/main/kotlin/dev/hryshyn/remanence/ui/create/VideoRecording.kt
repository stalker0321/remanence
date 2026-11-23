package dev.hryshyn.remanence.ui.create

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Rational
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.hryshyn.remanence.core.crypto.ManifestVideo
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.create.CapsuleVideoSquare
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * A recorded video square (ADR-021) held in memory for the draft: the H.264 +
 * AAC MP4, its length and square side. Its first frame is a square JPEG in the
 * app's cache that joins the photos as [posterPickerId]; it plays in that
 * photo's tile. [wipe] zeroes the film and deletes the frame.
 */
class RecordedVideo(mp4: ByteArray, val durationMs: Int, val size: Int, private val posterFile: File) {
    private val held = mp4

    /** The first frame as a photo of the draft (a file URI the photo readers open). */
    val posterPickerId: String = Uri.fromFile(posterFile).toString()

    /** Identity of the film for the layout proofs and the freeze. */
    val sha256Hex: String = MessageDigest.getInstance("SHA-256").digest(mp4).joinToString("") { "%02x".format(it) }

    /** An owned copy for the card, over the sealed content id of its first frame. */
    fun square(posterContentId: String): CapsuleVideoSquare = CapsuleVideoSquare(held.copyOf(), durationMs, size, posterContentId)

    /** An owned copy for the player. */
    fun film(): ByteArray = held.copyOf()

    fun wipe() {
        held.fill(0)
        posterFile.delete()
    }

    override fun toString(): String = "RecordedVideo(<redacted>, durationMs=$durationMs, size=$size)"

    companion object {
        /** The largest MP4 one VIDEO artifact carries. */
        val MAX_BYTES: Long = ProtocolV1Limits.ENCRYPTED_VIDEO_MAX_CIPHERTEXT_BYTES - ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES

        const val SIDE = 480
    }
}

/**
 * One take from the front camera (ADR-021 §4): a CameraX preview and a video
 * capture that share a 1:1 view port, so both show and record the middle
 * square; mirrored like a selfie; SD quality at about 1.2 Mbit/s with AAC,
 * stopping itself just before 30 s. [finish] reads the MP4 into memory,
 * deletes the file and takes its first frame as a [RecordedVideo.SIDE]
 * square JPEG.
 */
class VideoSquareRecorder(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var file: File? = null
    private var startedAt = 0L
    private var finished: ((Result<Unit>) -> Unit)? = null
    private var outcome: Result<Unit>? = null

    /** Opens the front camera into [surface] through [preview]. */
    suspend fun open(owner: LifecycleOwner, preview: Preview.SurfaceProvider, rotation: Int) {
        val cameraProvider = suspendCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    continuation.resume(future.get())
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                }
            }, ContextCompat.getMainExecutor(context))
        }
        provider = cameraProvider
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
            .setTargetVideoEncodingBitRate(VIDEO_BITS)
            .build()
        val video = VideoCapture.Builder(recorder)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .build()
        capture = video
        val shown = Preview.Builder().build().also { it.surfaceProvider = preview }
        val group = UseCaseGroup.Builder()
            .setViewPort(ViewPort.Builder(Rational(1, 1), rotation).build())
            .addUseCase(shown)
            .addUseCase(video)
            .build()
        cameraProvider.unbindAll()
        // The front camera, as a selfie; a device without one records with the back camera.
        val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        cameraProvider.bindToLifecycle(owner, selector, group)
    }

    @SuppressLint("MissingPermission")
    fun record(onLimit: () -> Unit) {
        val video = capture ?: throw IllegalStateException("camera is not open")
        val target = File(context.cacheDir, "video-${UUID.randomUUID()}.mp4")
        file = target
        outcome = null
        val options = FileOutputOptions.Builder(target)
            .setDurationLimitMillis((ManifestVideo.MAX_MS - 250).toLong())
            .setFileSizeLimit(RecordedVideo.MAX_BYTES)
            .build()
        recording = video.output.prepareRecording(context, options)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    val limited = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                        event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                    val result = if (!event.hasError() || limited) Result.success(Unit) else Result.failure(IllegalStateException("recording failed: ${event.error}"))
                    recording = null
                    val waiting = finished
                    if (waiting != null) {
                        finished = null
                        waiting(result)
                    } else {
                        outcome = result
                        if (limited) onLimit()
                    }
                }
            }
        startedAt = System.currentTimeMillis()
    }

    val elapsedMs: Long get() = if (recording == null) 0L else System.currentTimeMillis() - startedAt

    /** Stops the take and measures it; the MP4 file is deleted either way. */
    suspend fun finish(): RecordedVideo {
        val target = file ?: throw IllegalStateException("not recording")
        val done = outcome ?: suspendCoroutine { continuation ->
            finished = { continuation.resume(it) }
            recording?.stop() ?: continuation.resume(Result.failure(IllegalStateException("not recording")))
        }
        file = null
        try {
            done.getOrThrow()
            if (!target.exists() || target.length() < 8) throw IllegalStateException("empty recording")
            if (target.length() > RecordedVideo.MAX_BYTES) throw VideoTooLarge()
            val retriever = MediaMetadataRetriever()
            var side = 0
            val (durationMs, frame) = try {
                retriever.setDataSource(target.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
                    ?: (System.currentTimeMillis() - startedAt).toInt()
                // The 1:1 view port crops the recording itself (720x720 on the emulator).
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                side = minOf(width, height).coerceIn(ManifestVideo.MIN_SIZE, ManifestVideo.MAX_SIZE)
                duration to (retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IllegalStateException("no first frame"))
            } finally {
                retriever.release()
            }
            if (durationMs < MIN_MS) throw VideoTooShort()
            val poster = File(context.cacheDir, "video-first-frame-${UUID.randomUUID()}.jpg")
            squareJpeg(frame, poster)
            val bytes = target.readBytes()
            return RecordedVideo(bytes, durationMs.coerceIn(1, ManifestVideo.MAX_MS), side, poster)
        } finally {
            target.delete()
        }
    }

    fun close() {
        recording?.close()
        recording = null
        file?.delete()
        file = null
        provider?.unbindAll()
        provider = null
        capture = null
    }

    /** The middle square of [frame] at [RecordedVideo.SIDE], as the card's photo. */
    private fun squareJpeg(frame: Bitmap, target: File) {
        val side = minOf(frame.width, frame.height)
        val square = Bitmap.createBitmap(RecordedVideo.SIDE, RecordedVideo.SIDE, Bitmap.Config.ARGB_8888)
        Canvas(square).drawBitmap(
            frame,
            Rect((frame.width - side) / 2, (frame.height - side) / 2, (frame.width + side) / 2, (frame.height + side) / 2),
            Rect(0, 0, RecordedVideo.SIDE, RecordedVideo.SIDE),
            null,
        )
        FileOutputStream(target).use { square.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        square.recycle()
        frame.recycle()
    }

    class VideoTooLarge : Exception("video is too large")

    class VideoTooShort : Exception("video is too short")

    companion object {
        const val VIDEO_BITS = 1_200_000
        const val MIN_MS = 1_000

        /** The display rotation the view port is cut for. */
        fun rotationOf(context: Context): Int =
            (context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }
}
