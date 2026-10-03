package dev.hryshyn.remanence.ui.create

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException

/**
 * Bounded picked-photo thumbnail loader for the CONTENT selected-tiles strip.
 *
 * Reads ONLY the already-picked URIs the system picker granted (no new media
 * permission), off the calling thread (callers run it on an IO dispatcher),
 * with bounded output: at most [MAX_TILE_PX] on the long edge. Every failure
 * (revoked URI, corrupt bytes, oversized decode) yields null so the tile keeps
 * its selection check without an image — a load failure never invalidates the
 * valid choice.
 *
 * Cancellation honesty: this seam performs blocking reads with no
 * cancellation handoff of its own (no signal is threaded through
 * `loadThumbnail` — cancelling post-call would be a no-op). Cooperative
 * cancellation lives with the caller: the VM epoch guard drops stale
 * results and cancels the loading job. Coroutine cancellation is never
 * swallowed here — [CancellationException] is rethrown, everything else
 * maps to null.
 *
 * Bitmap ownership: the returned [ImageBitmap] owns the pixels (do not
 * recycle beneath it); on null nothing is retained — a decoded frame whose
 * conversion fails is recycled before returning.
 */
object PickedPhotoThumbnails {

    /** Long-edge bound for tile bitmaps (small-viewport strip). */
    const val MAX_TILE_PX = 256

    fun load(resolver: ContentResolver, uriString: String): ImageBitmap? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                adoptOrRecycle(resolver.loadThumbnail(uri, Size(MAX_TILE_PX, MAX_TILE_PX), null))
            } else {
                val bitmap = decodeBounded(openStream = {
                    runCatching { resolver.openInputStream(uri) }.getOrNull()
                }) ?: return null
                adoptOrRecycle(bitmap)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun adoptOrRecycle(source: Bitmap): ImageBitmap? {
        return try {
            source.asImageBitmap()
        } catch (_: Exception) {
            runCatching { source.recycle() }
            null
        }
    }

    /**
     * Smallest power-of-two sample with `longest / sample <= [maxPx]`.
     * Pure (no Android I/O) so adversarial dimensions are JVM-pinned in
     * tests. Non-positive input yields 1; callers treat empty bounds as a
     * decode failure before sampling.
     */
    internal fun sampleSizeFor(longestPx: Int, maxPx: Int = MAX_TILE_PX): Int {
        if (longestPx <= 0) return 1
        var sample = 1
        // Ceiling division: decoders emit ceil(longest / sample) px, and
        // floor division would wrongly pass odd sizes (513 / 2 = 256 but
        // the frame is 257 px). Loop until the emitted edge fits.
        while ((longestPx + sample - 1) / sample > maxPx) sample *= 2
        return sample
    }

    /**
     * API 26–28 fallback core, seam-tested: bounds decode first, then the
     * smallest power-of-two sample that fits [maxPx], so no unbounded bitmap
     * is ever realized. The stream opener is injected (production passes the
     * resolver stream; tests pass synthetic PNG bytes).
     */
    internal fun decodeBounded(
        openStream: () -> InputStream?,
        maxPx: Int = MAX_TILE_PX,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(longest, maxPx) }
        return try {
            openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
