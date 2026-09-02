package dev.hryshyn.remanence.capture

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text

/**
 * FIX-STATE-08: fake camera driver for Compose/JVM transition tests. It never
 * touches hardware; tests fire [emitReady], [deliverFrame], and [failAttempt]
 * to excite EXACTLY the production callbacks the real CameraX adapter would.
 * The synthetic focus operation settles synchronously inside [captureStill],
 * before a later still delivery is allowed.
 */
class FakeStillCameraAdapter : StillCameraAdapter {

    var bindCalls = 0
        private set
    var releaseCalls = 0
        private set
    var captureCalls = 0
        private set

    private var onReady: (() -> Unit)? = null
    private var onBindError: ((String) -> Unit)? = null
    private var onDelivered: ((ByteArray) -> Unit)? = null
    private var onCaptureError: ((String) -> Unit)? = null
    private var focusSettled = false

    override val preview: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            "fake preview",
            modifier = modifier.testTag("fake_camera_preview"),
        )
    }

    override fun bind(onReady: () -> Unit, onError: (String) -> Unit) {
        if (releaseCalls > 0) return
        bindCalls += 1
        this.onReady = onReady
        this.onBindError = onError
    }

    override fun captureStill(onDelivered: (ByteArray) -> Unit, onError: (String) -> Unit) {
        if (releaseCalls > 0) return
        captureCalls += 1
        this.onDelivered = onDelivered
        this.onCaptureError = onError
        // The real adapter waits for focus completion before exposing the
        // ImageCapture callback. The fake completes that hidden operation
        // synchronously so tests can drive only still delivery.
        focusSettled = true
    }

    override fun release() {
        releaseCalls += 1
        focusSettled = false
        onDelivered = null
        onCaptureError = null
    }

    /** Simulates the async preview binding completing. */
    fun emitReady() {
        onReady?.invoke()
    }

    /** Simulates the camera delivering the one still. */
    fun deliverFrame(jpegBytes: ByteArray) {
        check(focusSettled) { "still delivery requires focus completion" }
        val callback = checkNotNull(onDelivered) { "still delivery without capture" }
        focusSettled = false
        onDelivered = null
        onCaptureError = null
        callback(jpegBytes)
    }

    /** Simulates a takePicture failure. */
    fun failAttempt(message: String) {
        check(focusSettled) { "capture failure requires focus completion" }
        val callback = checkNotNull(onCaptureError) { "capture failure without capture" }
        focusSettled = false
        onDelivered = null
        onCaptureError = null
        callback(message)
    }
}
