package dev.hryshyn.remanence.capture

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text

/**
 * FIX-STATE-08: fake camera driver for Compose/JVM transition tests. It never
 * touches hardware; tests fire [emitReady], [deliverFrame], and [failAttempt]
 * to excite EXACTLY the production callbacks the real CameraX adapter would.
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
    }

    override fun release() {
        releaseCalls += 1
    }

    /** Simulates the async preview binding completing. */
    fun emitReady() {
        onReady?.invoke()
    }

    /** Simulates the camera delivering the one still. */
    fun deliverFrame(jpegBytes: ByteArray) {
        onDelivered?.invoke(jpegBytes)
    }

    /** Simulates a takePicture failure. */
    fun failAttempt(message: String) {
        onCaptureError?.invoke(message)
    }
}
