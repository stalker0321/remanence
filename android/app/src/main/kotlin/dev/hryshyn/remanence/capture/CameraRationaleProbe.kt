package dev.hryshyn.remanence.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.app.ActivityCompat

/**
 * FIX-REVIEW-05: THE one reader of the real OS ask-again signal for the
 * camera. Capture surfaces live inside the single ComponentActivity, located
 * through any ContextWrapper chain; ActivityCompat decides whether the system
 * would show the permission dialog again after the just-received denial.
 * A hypothetical host without an Activity conservatively reports ask-again
 * possible so a user is never locked out by an unprovable permanent state.
 */
fun cameraAskAgainPossible(context: Context): Boolean {
    var current: Context = context
    while (current is ContextWrapper) {
        if (current is Activity) {
            return ActivityCompat.shouldShowRequestPermissionRationale(
                current,
                Manifest.permission.CAMERA,
            )
        }
        current = current.baseContext
    }
    return true
}
