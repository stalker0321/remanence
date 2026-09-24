package dev.hryshyn.remanence.ui.capsule.music

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * S3 seam for opening a streaming search URL. Production fires an
 * external `ACTION_VIEW` (browser/app chosen by the user and system);
 * tests inject a fake. Returns false when nothing could handle the URL.
 */
fun interface TrackUrlLauncher {
    fun open(url: String): Boolean
}

/**
 * Production launcher: allowlisted HTTPS only, external browser, never a
 * WebView. No `resolveActivity` pre-check (needs no manifest `<queries>`);
 * a missing handler surfaces as false and the card shows an inline
 * notice instead of crashing.
 */
internal fun Context.openTrackUrl(url: String): Boolean {
    if (!StreamingService.isAllowedUrl(url)) return false
    return try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
