package app.postmark.memory.capture

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * STRICT camera driver for the binding-order regression proof. Unlike the
 * permissive [FakeStillCameraAdapter], [bind] FAILS LOUDLY unless this
 * adapter's [preview] has already been composed - mirroring
 * [CameraXStillCameraAdapter], whose bind() requires the hosted PreviewView
 * to exist. The permissive fake silently tolerated a bind-before-compose,
 * which is exactly why the first-camera-entry crash escaped testing.
 */
class StrictFakeStillCameraAdapter : StillCameraAdapter {

    /** Set the moment this adapter's preview joins the composition. */
    var previewComposed = false
        private set

    var bindCalls = 0
        private set

    var releaseCalls = 0
        private set

    private var onReady: (() -> Unit)? = null
    private var onBindError: ((String) -> Unit)? = null

    override val preview: @Composable (Modifier) -> Unit = { modifier ->
        previewComposed = true
        Text(
            "strict fake preview",
            modifier = modifier.testTag("strict_fake_camera_preview"),
        )
    }

    override fun bind(onReady: () -> Unit, onError: (String) -> Unit) {
        if (releaseCalls > 0) return
        check(previewComposed) {
            "bind() before preview composed - reproduces the first-camera-entry crash"
        }
        bindCalls += 1
        this.onReady = onReady
        this.onBindError = onError
    }

    override fun captureStill(onDelivered: (ByteArray) -> Unit, onError: (String) -> Unit) {
        if (releaseCalls > 0) return
        check(previewComposed) { "capture before preview composed" }
    }

    override fun release() {
        releaseCalls += 1
    }

    /** Simulates the async preview binding completing. */
    fun emitReady() {
        check(releaseCalls == 0) { "a released adapter must never deliver onReady" }
        onReady?.invoke()
    }
}
