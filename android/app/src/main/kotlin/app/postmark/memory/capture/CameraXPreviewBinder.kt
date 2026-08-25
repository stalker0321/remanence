package app.postmark.memory.capture

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.lifecycle.LifecycleOwner

/**
 * FIX-STATE-04: explicit handle over one camera binding so hosts can release
 * the use cases deterministically on dispose or step transition. Release
 * after the future completed unbinds everything; release before completion
 * marks the binding dead so the pending listener becomes a no-op.
 */
class CameraXBinding internal constructor(private val future: java.util.concurrent.Future<ProcessCameraProvider>) {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var providerRef: ProcessCameraProvider? = null

    internal fun attach(provider: ProcessCameraProvider) {
        if (released.get()) {
            // Dispose won the race against the async bind: never go live.
            provider.unbindAll()
            return
        }
        providerRef = provider
    }

    internal fun isReleased(): Boolean = released.get()

    /** Idempotently unbinds every use case of this binding. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        providerRef?.unbindAll()
    }
}

/**
 * Real CameraX wiring for the one-still shell: binds Preview + ImageCapture
 * to the app lifecycle and delivers exactly one in-memory JPEG through the
 * callback. No file is written and no frame stream is analyzed continuously
 * (docs/recognition.md section 3: CameraX ImageCapture only).
 */
object CameraXPreviewBinder {

    private const val TAG = "PostmarkCapture"

    fun createImageCapture(): ImageCapture =
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

    /**
     * FIX-STATE-04: binds Preview + ImageCapture and returns a [CameraXBinding]
     * whose [CameraXBinding.release] explicitly frees the use cases on dispose;
     * late bind/capture callbacks after release are inert.
     */
    fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        imageCapture: ImageCapture,
        onBound: () -> Unit,
        onError: (String) -> Unit,
    ): CameraXBinding {
        val future = ProcessCameraProvider.getInstance(context)
        val binding = CameraXBinding(future)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        future.addListener(
            {
                if (binding.isReleased()) return@addListener
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                    binding.attach(provider)
                    onBound()
                } catch (failure: Exception) {
                    Log.w(TAG, "camera binding failed")
                    onError("Camera unavailable")
                }
            },
            mainExecutor,
        )
        return binding
    }

    /**
     * Takes exactly one still; the JPEG bytes are handed to [onDelivered] and
     * never stored by this component.
     */
    fun captureOneStill(
        context: Context,
        imageCapture: ImageCapture,
        onDelivered: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        onDelivered(bytes)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "still capture failed")
                    onError("Capture failed")
                }
            },
        )
    }
}
