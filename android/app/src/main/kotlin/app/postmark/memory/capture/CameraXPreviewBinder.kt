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

    fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        imageCapture: ImageCapture,
        onBound: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        future.addListener(
            {
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
                    onBound()
                } catch (failure: Exception) {
                    Log.w(TAG, "camera binding failed")
                    onError("Camera unavailable")
                }
            },
            mainExecutor,
        )
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
