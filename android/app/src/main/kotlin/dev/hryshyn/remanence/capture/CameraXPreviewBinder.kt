package dev.hryshyn.remanence.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import com.google.common.util.concurrent.ListenableFuture

/**
 * FIX-STATE-04: explicit handle over one camera binding so hosts can release
 * the use cases deterministically on dispose or step transition. Release
 * after the future completed unbinds everything; release before completion
 * marks the binding dead so the pending listener becomes a no-op.
 */
internal data class CameraXUseCaseSet(
    val preview: Preview,
    val group: UseCaseGroup,
)

internal interface CameraProviderPort {
    fun bindToLifecycle(lifecycleOwner: LifecycleOwner, group: UseCaseGroup)

    fun unbind(vararg useCases: UseCase)

    fun unbindAll()
}

private class RealCameraProviderPort(
    private val provider: ProcessCameraProvider,
) : CameraProviderPort {
    override fun bindToLifecycle(lifecycleOwner: LifecycleOwner, group: UseCaseGroup) {
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
    }

    override fun unbind(vararg useCases: UseCase) {
        provider.unbind(*useCases)
    }

    override fun unbindAll() {
        provider.unbindAll()
    }
}

/**
 * Owns one active preview/capture group and rebinds it when the measured
 * viewport or display rotation changes. The providers are kept behind an
 * internal seam so lifecycle/rotation behavior is deterministic in JVM tests.
 */
internal class CameraXBindingController(
    private val previewView: PreviewView,
    private val lifecycleOwner: LifecycleOwner,
    private val imageCapture: ImageCapture,
    private val onBound: () -> Unit,
    private val onError: (String) -> Unit,
    private val rotationProvider: () -> Int? = { previewView.display?.rotation },
    private val viewPortProvider: () -> ViewPort? = { previewView.viewPort },
) {
    private data class BindingKey(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val scaleType: Int,
        val layoutDirection: Int,
    )

    private var provider: CameraProviderPort? = null
    private var active: CameraXUseCaseSet? = null
    private var activeKey: BindingKey? = null
    private var released = false
    private var reportedBound = false
    private var captureInFlight = false
    private var refreshPending = false

    internal fun attach(provider: CameraProviderPort) {
        if (released) {
            provider.unbindAll()
            return
        }
        this.provider = provider
        refresh()
    }

    internal fun onLifecycleStart() {
        refresh()
    }

    internal fun onLifecycleStop() {
        if (captureInFlight) {
            refreshPending = true
        } else {
            unbindActive()
        }
    }

    internal fun refresh() {
        if (captureInFlight) {
            refreshPending = true
            return
        }
        if (released ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            return
        }
        val provider = provider ?: return
        val rotation = rotationProvider() ?: return
        val viewPort = viewPortProvider() ?: return
        // A display event can arrive before PreviewView has rebuilt its
        // viewport. Wait for the matching measured viewport instead of
        // binding a preview and still with different crop rotations.
        if (viewPort.rotation != rotation || previewView.width <= 0 || previewView.height <= 0) return

        val key = BindingKey(
            width = previewView.width,
            height = previewView.height,
            rotation = rotation,
            scaleType = viewPort.scaleType,
            layoutDirection = viewPort.layoutDirection,
        )
        if (active != null && activeKey == key) return

        unbindActive()
        try {
            val useCases = CameraXPreviewBinder.createUseCaseSet(
                previewView = previewView,
                imageCapture = imageCapture,
                rotation = rotation,
                viewPort = viewPort,
            )
            provider.bindToLifecycle(lifecycleOwner, useCases.group)
            active = useCases
            activeKey = key
            if (!reportedBound) {
                reportedBound = true
                onBound()
            }
        } catch (_: Exception) {
            active = null
            activeKey = null
            onError("Camera unavailable")
        }
    }

    internal fun release(): Boolean {
        if (released) return !captureInFlight
        released = true
        if (captureInFlight) return false
        unbindActive()
        return true
    }

    internal fun captureStarted() {
        check(!released) { "capture started after binding release" }
        check(!captureInFlight) { "capture already in flight" }
        captureInFlight = true
    }

    internal fun captureFinished() {
        if (!captureInFlight) return
        captureInFlight = false
        if (released ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            refreshPending = false
            unbindActive()
            return
        }
        if (refreshPending) {
            refreshPending = false
            refresh()
        }
    }

    private fun unbindActive() {
        val current = active ?: return
        active = null
        activeKey = null
        runCatching { provider?.unbind(current.preview, imageCapture) }
    }
}

class CameraXBinding internal constructor(
    private val future: ListenableFuture<ProcessCameraProvider>,
    private val context: Context,
    private val previewView: PreviewView,
    private val lifecycleOwner: LifecycleOwner,
    private val imageCapture: ImageCapture,
    onBound: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val released = AtomicBoolean(false)
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val controller = CameraXBindingController(
        previewView = previewView,
        lifecycleOwner = lifecycleOwner,
        imageCapture = imageCapture,
        onBound = onBound,
        onError = onError,
    )

    @Volatile
    private var providerRef: ProcessCameraProvider? = null

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        previewView.post { controller.refresh() }
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            controller.onLifecycleStart()
        }

        override fun onStop(owner: LifecycleOwner) {
            controller.onLifecycleStop()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            release()
        }
    }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == previewView.display?.displayId) {
                previewView.post { controller.refresh() }
            }
        }
    }

    internal fun start() {
        CameraXPreviewBinder.configurePreviewView(previewView)
        previewView.addOnLayoutChangeListener(layoutListener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        future.addListener(
            {
                if (released.get()) {
                    runCatching { future.get().unbindAll() }
                    return@addListener
                }
                try {
                    attach(future.get())
                } catch (_: Exception) {
                    onError("Camera unavailable")
                }
            },
            mainExecutor,
        )
        previewView.post { controller.refresh() }
    }

    private fun attach(provider: ProcessCameraProvider) {
        if (released.get()) {
            provider.unbindAll()
            return
        }
        providerRef = provider
        controller.attach(RealCameraProviderPort(provider))
    }

    internal fun isReleased(): Boolean = released.get()

    internal fun beginCapture(): Boolean {
        if (released.get()) return false
        return runCatching {
            controller.captureStarted()
            true
        }.getOrDefault(false)
    }

    internal fun finishCapture() {
        controller.captureFinished()
    }

    /** Idempotently unbinds every use case of this binding. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        previewView.removeOnLayoutChangeListener(layoutListener)
        lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        displayManager?.unregisterDisplayListener(displayListener)
        if (controller.release()) providerRef?.unbindAll()
    }
}

/**
 * Real CameraX wiring for the one-still shell: binds Preview + ImageCapture
 * to the app lifecycle and delivers exactly one in-memory JPEG through the
 * callback. No file is written and no frame stream is analyzed continuously
 * (docs/recognition.md section 3: CameraX ImageCapture only).
 */
object CameraXPreviewBinder {

    private const val TAG = "RemanenceCapture"

    internal fun configurePreviewView(previewView: PreviewView) {
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    fun createImageCapture(): ImageCapture =
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

    internal fun createUseCaseSet(
        previewView: PreviewView,
        imageCapture: ImageCapture,
        rotation: Int,
        viewPort: ViewPort,
    ): CameraXUseCaseSet {
        require(viewPort.rotation == rotation) { "viewport/display rotation mismatch" }
        imageCapture.targetRotation = rotation
        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(imageCapture)
            .build()
        return CameraXUseCaseSet(preview, group)
    }

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
        val binding = CameraXBinding(
            future = future,
            context = context,
            previewView = previewView,
            lifecycleOwner = lifecycleOwner,
            imageCapture = imageCapture,
            onBound = onBound,
            onError = { reason ->
                Log.w(TAG, "camera binding failed")
                onError(reason)
            },
        )
        binding.start()
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
        onFinished: () -> Unit = {},
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
                        try {
                            image.close()
                        } finally {
                            onFinished()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    try {
                        Log.w(TAG, "still capture failed")
                        onError("Capture failed")
                    } finally {
                        onFinished()
                    }
                }
            },
        )
    }
}
