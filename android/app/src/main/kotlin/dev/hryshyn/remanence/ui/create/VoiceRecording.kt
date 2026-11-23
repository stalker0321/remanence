package dev.hryshyn.remanence.ui.create

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import dev.hryshyn.remanence.core.crypto.ManifestVoice
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.create.CapsuleVoiceNote
import dev.hryshyn.remanence.ui.collage.SongBand
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/**
 * A recorded voice note (ADR-021) held in memory for the draft: the AAC/MP4,
 * its length and the [ManifestVoice.PEAKS] amplitudes the band draws. Nothing
 * stays on disk; [wipe] zeroes the sound when it is replaced or dropped.
 */
class RecordedVoice(mp4: ByteArray, val durationMs: Int, peaks: IntArray) {
    private val held = mp4
    val peaks: IntArray = peaks.copyOf()

    /** Identity of the sound for the layout proofs and the freeze. */
    val sha256Hex: String = MessageDigest.getInstance("SHA-256").digest(mp4).joinToString("") { "%02x".format(it) }

    /** One stable instance per recording, so the layouts prepare once. */
    val words = SongBand.VoiceWords(this.peaks, durationMs)

    /** An owned copy for the card. */
    fun note(): CapsuleVoiceNote = CapsuleVoiceNote(held.copyOf(), durationMs, peaks.copyOf())

    /** An owned copy for the player. */
    fun sound(): ByteArray = held.copyOf()

    fun wipe() = held.fill(0)

    override fun toString(): String = "RecordedVoice(<redacted>, durationMs=$durationMs)"

    companion object {
        /** The largest MP4 one MEDIA artifact carries. */
        val MAX_BYTES: Long = ProtocolV1Limits.ENCRYPTED_MEDIA_MAX_CIPHERTEXT_BYTES - ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES
    }
}

/**
 * The loudest level of each of [ManifestVoice.PEAKS] equal slices of the
 * levels sampled while recording, scaled so the loudest slice is 255; silence
 * stays flat at 0. The site measures the decoded samples the same way.
 */
internal fun voicePeaks(levels: IntArray): IntArray {
    val slices = IntArray(ManifestVoice.PEAKS) { slice ->
        val start = levels.size * slice / ManifestVoice.PEAKS
        val end = maxOf(start + 1, levels.size * (slice + 1) / ManifestVoice.PEAKS).coerceAtMost(levels.size)
        (start until end).maxOfOrNull { levels[it] } ?: 0
    }
    val top = slices.maxOrNull() ?: 0
    if (top < SILENCE) return IntArray(ManifestVoice.PEAKS)
    return IntArray(ManifestVoice.PEAKS) { (255.0 * slices[it] / top).roundToInt().coerceIn(0, 255) }
}

private const val SILENCE = 50

/**
 * One recording from the microphone into the app's cache: AAC in MP4 at
 * 48 kbit/s, mono, stopping itself at a minute ([onLimit]). The level is
 * sampled every [SAMPLE_MS] for the peaks; [finish] reads the file into memory
 * and deletes it, [cancel] deletes it unread.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private val levels = ArrayList<Int>()
    private var startedAt = 0L

    fun start(onLimit: () -> Unit) {
        val target = File(context.cacheDir, "voice-${UUID.randomUUID()}.m4a")
        file = target
        levels.clear()
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = created
        try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(44_100)
            created.setAudioEncodingBitRate(48_000)
            created.setMaxDuration(ManifestVoice.MAX_MS - 250)
            created.setMaxFileSize(RecordedVoice.MAX_BYTES)
            created.setOutputFile(target.absolutePath)
            created.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) onLimit()
            }
            created.prepare()
            created.start()
            startedAt = System.currentTimeMillis()
        } catch (failure: Exception) {
            cancel()
            throw failure
        }
    }

    /** Samples the current level; call every [SAMPLE_MS] while recording. */
    fun sample(): Int {
        val level = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        levels += level
        return level
    }

    val elapsedMs: Long get() = if (recorder == null) 0L else System.currentTimeMillis() - startedAt

    /** Stops, reads the MP4 into memory, deletes the file and measures it. */
    fun finish(): RecordedVoice {
        val current = recorder ?: throw IllegalStateException("not recording")
        val target = requireNotNull(file)
        try {
            runCatching { current.stop() }
        } finally {
            current.release()
            recorder = null
        }
        try {
            if (!target.exists() || target.length() < 8) throw IllegalStateException("empty recording")
            if (target.length() > RecordedVoice.MAX_BYTES) throw VoiceTooLarge()
            val durationMs = MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(target.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
                } finally {
                    retriever.release()
                }
            } ?: elapsedFallback()
            val bytes = target.readBytes()
            return RecordedVoice(bytes, durationMs.coerceIn(1, ManifestVoice.MAX_MS), voicePeaks(levels.toIntArray()))
        } finally {
            target.delete()
            file = null
        }
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
        file = null
        levels.clear()
    }

    private fun elapsedFallback(): Int = (System.currentTimeMillis() - startedAt).toInt()

    class VoiceTooLarge : Exception("voice note is too large")

    companion object {
        const val SAMPLE_MS = 50L
    }
}
