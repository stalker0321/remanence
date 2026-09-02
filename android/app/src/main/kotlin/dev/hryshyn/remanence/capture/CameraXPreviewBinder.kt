package dev.hryshyn.remanence.capture

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
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
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
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

    /** Camera control for the most recently bound use-case group, if present. */
    fun cameraControl(): CameraControl? = null

    fun unbind(vararg useCases: UseCase)

    fun unbindAll()
}

private class RealCameraProviderPort(
    private val provider: ProcessCameraProvider,
) : CameraProviderPort {
    private var camera: androidx.camera.core.Camera? = null

    override fun bindToLifecycle(lifecycleOwner: LifecycleOwner, group: UseCaseGroup) {
        camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
    }

    override fun cameraControl(): CameraControl? = camera?.cameraControl

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
    private val focusTimeoutScheduler: FocusTimeoutScheduler =
        HandlerFocusTimeoutScheduler(Handler(Looper.getMainLooper())),
    private val focusCallbackExecutor: Executor = Executor { it.run() },
) {
    private data class BindingKey(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val scaleType: Int,
        val layoutDirection: Int,
    )

    private var provider: CameraProviderPort? = null
    private var activeCameraControl: CameraControl? = null
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
            activeCameraControl = provider.cameraControl()
            active = useCases
            activeKey = key
            if (!reportedBound) {
                reportedBound = true
                onBound()
            }
        } catch (_: Exception) {
            activeCameraControl = null
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

    /** Starts bounded center AF/AE for the current bound camera. */
    internal fun startCenterFocus(
        onFocused: () -> Unit,
        onError: (String) -> Unit,
    ): FocusCancellation {
        val cameraControl = activeCameraControl
        if (cameraControl == null) {
            onError("Camera focus is unavailable; capture was not taken.")
            return FocusCancellation {}
        }
        val driver = CameraXCenterFocusDriver(
            cameraControl = cameraControl,
            meteringPointFactory = previewView.meteringPointFactory,
            width = previewView.width,
            height = previewView.height,
            callbackExecutor = focusCallbackExecutor,
        )
        return BoundedCenterFocus(
            driver = driver,
            timeoutScheduler = focusTimeoutScheduler,
        ).start(onFocused, onError)
    }

    private fun unbindActive() {
        val current = active ?: return
        active = null
        activeKey = null
        activeCameraControl = null
        runCatching { provider?.unbind(current.preview, imageCapture) }
    }
}

/** Cancellation handle for one focus request or one scheduled timeout. */
internal fun interface FocusCancellation {
    fun cancel()
}

internal interface CenterFocusDriver {
    fun start(onResult: (Boolean) -> Unit, onFailure: () -> Unit): FocusCancellation
}

internal interface FocusTimeoutScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): FocusCancellation
}

internal class HandlerFocusTimeoutScheduler(
    private val handler: Handler,
) : FocusTimeoutScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): FocusCancellation {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMillis)
        return FocusCancellation { handler.removeCallbacks(runnable) }
    }
}

/**
 * Waits for center focus with a finite bound. A focus failure or timeout is
 * reported honestly and does not take a still; cancellation is silent.
 */
internal class BoundedCenterFocus(
    private val driver: CenterFocusDriver,
    private val timeoutScheduler: FocusTimeoutScheduler,
    private val timeoutMillis: Long = CENTER_FOCUS_TIMEOUT_MILLIS,
) {
    init {
        require(timeoutMillis > 0L)
    }

    fun start(onFocused: () -> Unit, onError: (String) -> Unit): FocusCancellation {
        val finished = AtomicBoolean(false)
        val focusRequest = java.util.concurrent.atomic.AtomicReference<FocusCancellation?>()
        val timeoutRequest = java.util.concurrent.atomic.AtomicReference<FocusCancellation?>()

        fun finish(action: () -> Unit) {
            if (!finished.compareAndSet(false, true)) return
            timeoutRequest.getAndSet(null)?.cancel()
            action()
        }

        val request = try {
            driver.start(
                onResult = { successful ->
                    finish {
                        if (successful) {
                            onFocused()
                        } else {
                            onError("Camera focus did not settle; hold steady and try again.")
                        }
                    }
                },
                onFailure = {
                    finish { onError("Camera focus is unavailable; capture was not taken.") }
                },
            )
        } catch (_: Exception) {
            finish { onError("Camera focus is unavailable; capture was not taken.") }
            return FocusCancellation {}
        }

        focusRequest.set(request)
        if (finished.get()) {
            // The driver may complete synchronously. In the successful case
            // onFocused() has already started takePicture(); cancelling the
            // now-completed AF/AE request here would undo that lock.
            return FocusCancellation {}
        }

        val timeout = timeoutScheduler.schedule(timeoutMillis) {
            finish { onError("Camera focus timed out; hold steady and try again.") }
            focusRequest.getAndSet(null)?.cancel()
        }
        timeoutRequest.set(timeout)
        if (finished.get()) timeout.cancel()

        return FocusCancellation {
            if (finished.compareAndSet(false, true)) {
                timeoutRequest.getAndSet(null)?.cancel()
                focusRequest.getAndSet(null)?.cancel()
            }
        }
    }

    private companion object {
        const val CENTER_FOCUS_TIMEOUT_MILLIS = 1_500L
    }
}

internal class CameraXCenterFocusDriver(
    private val cameraControl: CameraControl,
    private val meteringPointFactory: androidx.camera.core.MeteringPointFactory,
    private val width: Int,
    private val height: Int,
    private val callbackExecutor: Executor,
) : CenterFocusDriver {
    override fun start(onResult: (Boolean) -> Unit, onFailure: () -> Unit): FocusCancellation {
        if (width <= 0 || height <= 0) {
            onFailure()
            return FocusCancellation {}
        }
        val point = meteringPointFactory.createPoint(width / 2f, height / 2f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .addPoint(point, FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5L, TimeUnit.SECONDS)
            .build()
        val future: ListenableFuture<FocusMeteringResult> = try {
            cameraControl.startFocusAndMetering(action)
        } catch (_: Exception) {
            onFailure()
            return FocusCancellation {}
        }
        future.addListener(
            {
                try {
                    onResult(future.get().isFocusSuccessful)
                } catch (_: Exception) {
                    onFailure()
                }
            },
            callbackExecutor,
        )
        return FocusCancellation { runCatching { cameraControl.cancelFocusAndMetering() } }
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
        focusCallbackExecutor = mainExecutor,
    )

    @Volatile
    private var providerRef: ProcessCameraProvider? = null
    private val activeFocusRequest = java.util.concurrent.atomic.AtomicReference<FocusCancellation?>()

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

    /** Runs bounded center AF/AE, then takes exactly one JPEG still. */
    internal fun captureOneStill(
        onDelivered: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        val focusFinished = AtomicBoolean(false)
        val request = controller.startCenterFocus(
            onFocused = {
                focusFinished.set(true)
                // The successful metering request must remain in effect while
                // the still is exposed; clearing the lifecycle handle must
                // not cancel AF/AE immediately before takePicture().
                activeFocusRequest.getAndSet(null)
                if (released.get()) {
                    controller.captureFinished()
                } else {
                    try {
                        CameraXPreviewBinder.captureOneStill(
                            context = context,
                            imageCapture = imageCapture,
                            onDelivered = onDelivered,
                            onError = onError,
                            onFinished = controller::captureFinished,
                        )
                    } catch (failure: Exception) {
                        onError(failure.message ?: "Capture failed")
                        controller.captureFinished()
                    }
                }
            },
            onError = { reason ->
                focusFinished.set(true)
                activeFocusRequest.getAndSet(null)
                if (!released.get()) onError(reason)
                controller.captureFinished()
            },
        )
        if (focusFinished.get()) {
            request.cancel()
        } else {
            activeFocusRequest.set(request)
            if (released.get()) {
                activeFocusRequest.getAndSet(null)?.cancel()
                controller.captureFinished()
            }
        }
    }

    /** Idempotently unbinds every use case of this binding. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        previewView.removeOnLayoutChangeListener(layoutListener)
        lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        displayManager?.unregisterDisplayListener(displayListener)
        activeFocusRequest.getAndSet(null)?.let { focusRequest ->
            focusRequest.cancel()
            controller.captureFinished()
        }
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
            .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
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
                        val bytes = try {
                            copyValidatedJpegBytes(image)
                        } catch (_: IllegalArgumentException) {
                            onError("Camera delivered an invalid JPEG still")
                            return
                        }
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

    internal fun copyValidatedJpegBytes(image: ImageProxy): ByteArray {
        require(image.format == ImageFormat.JPEG) { "ImageProxy format is not JPEG" }
        require(image.planes.size == 1) { "JPEG ImageProxy must have one plane" }
        val buffer = image.planes.single().buffer.duplicate()
        require(buffer.remaining() >= 2) { "JPEG ImageProxy is empty" }
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        require(bytes[0].toInt() and 0xFF == 0xFF && bytes[1].toInt() and 0xFF == 0xD8) {
            "JPEG ImageProxy does not start with SOI"
        }
        return bytes
    }
}
