package dev.hryshyn.remanence.capture

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PointF
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CameraXStillCameraAdapterProductionPathTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry
    }

    private class NoopTimeoutScheduler : FocusTimeoutScheduler {
        override fun schedule(delayMillis: Long, task: () -> Unit): FocusCancellation =
            FocusCancellation {}
    }

    private class RecordingCameraControl(
        private val events: MutableList<String>,
    ) : CameraControl {
        private var result: SettableFuture<FocusMeteringResult> = SettableFuture.create()
        var cancelCalls = 0
            private set

        override fun enableTorch(torch: Boolean): ListenableFuture<Void> = pendingVoid()

        override fun startFocusAndMetering(action: FocusMeteringAction): ListenableFuture<FocusMeteringResult> {
            events += "focus-started"
            result = SettableFuture.create()
            return result
        }

        override fun cancelFocusAndMetering(): ListenableFuture<Void> {
            cancelCalls += 1
            return pendingVoid()
        }

        override fun setZoomRatio(ratio: Float): ListenableFuture<Void> = pendingVoid()

        override fun setLinearZoom(linearZoom: Float): ListenableFuture<Void> = pendingVoid()

        override fun setExposureCompensationIndex(index: Int): ListenableFuture<Int> =
            Futures.immediateFuture(index)

        fun complete(successful: Boolean) {
            events += "focus-completed"
            result.set(FocusMeteringResult.create(successful))
        }

        private fun pendingVoid(): ListenableFuture<Void> = SettableFuture.create()
    }

    private class IdentityMeteringPointFactory : MeteringPointFactory() {
        override fun convertPoint(x: Float, y: Float): PointF = PointF(x, y)
    }

    private class RecordingStillCapture(
        private val events: MutableList<String>,
    ) : StillCaptureInvoker {
        private var callback: ImageCapture.OnImageCapturedCallback? = null
        var takePictureCalls = 0
            private set

        override fun takePicture(executor: Executor, callback: ImageCapture.OnImageCapturedCallback) {
            events += "takePicture"
            takePictureCalls += 1
            this.callback = callback
        }

        fun deliver(image: ImageProxy) {
            checkNotNull(callback).onCaptureSuccess(image)
        }

        fun fail() {
            checkNotNull(callback).onError(
                ImageCaptureException(ImageCapture.ERROR_CAPTURE_FAILED, "test failure", null),
            )
        }
    }

    private class CountingImageProxy(
        private val imageFormat: Int,
        private val payload: ByteArray,
    ) : ImageProxy {
        private val plane = object : ImageProxy.PlaneProxy {
            override fun getRowStride(): Int = payload.size
            override fun getPixelStride(): Int = 1
            override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(payload)
        }

        var closeCalls = 0
            private set

        override fun getCropRect(): Rect = Rect(0, 0, 2, 2)
        override fun setCropRect(rect: Rect?) = Unit
        override fun getFormat(): Int = imageFormat
        override fun getHeight(): Int = 2
        override fun getWidth(): Int = 2
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(plane)
        override fun getImageInfo(): ImageInfo = error("unused in still callback test")
        override fun getImage(): Image? = null
        override fun close() {
            closeCalls += 1
        }
    }

    private data class Harness(
        val adapter: CameraXStillCameraAdapter,
        val owner: TestLifecycleOwner,
        val focus: RecordingCameraControl,
        val stillCapture: RecordingStillCapture,
        val finishCalls: AtomicInteger,
        val events: MutableList<String>,
    )

    private fun harness(): Harness {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = TestLifecycleOwner()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val events = mutableListOf<String>()
        val focus = RecordingCameraControl(events)
        val stillCapture = RecordingStillCapture(events)
        val finishCalls = AtomicInteger()
        val timeoutScheduler = NoopTimeoutScheduler()

        val adapter = CameraXStillCameraAdapter(
            context = context,
            lifecycleOwner = owner,
            bindingFactory = CameraXBindingFactory { factoryContext, factoryOwner, previewView, imageCapture, onBound, onError ->
                val binding = CameraXBinding(
                    future = SettableFuture.create<ProcessCameraProvider>(),
                    context = factoryContext,
                    previewView = previewView,
                    lifecycleOwner = factoryOwner,
                    imageCapture = imageCapture,
                    onBound = {},
                    onError = onError,
                    focusStarter = CameraXFocusStarter { onFocused, focusError ->
                        val focusDriver = CameraXCenterFocusDriver(
                            cameraControl = focus,
                            meteringPointFactory = IdentityMeteringPointFactory(),
                            width = 1000,
                            height = 600,
                            callbackExecutor = Executor { it.run() },
                        )
                        BoundedCenterFocus(
                            driver = focusDriver,
                            timeoutScheduler = timeoutScheduler,
                            timeoutMillis = 10_000L,
                        ).start(onFocused, focusError)
                    },
                    stillCaptureInvoker = stillCapture,
                    onCaptureFinishedForTest = { finishCalls.incrementAndGet() },
                )
                binding.start()
                onBound()
                binding
            },
        )

        composeRule.setContent {
            adapter.preview(Modifier.size(320.dp))
        }
        composeRule.waitForIdle()

        adapter.bind(onReady = {}, onError = { error("unexpected bind failure: $it") })
        return Harness(adapter, owner, focus, stillCapture, finishCalls, events)
    }

    @Test
    fun adapterCaptureStitchesFocusCompletionBeforeTakePictureAndDelivery() {
        val harness = harness()
        val delivered = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()

        harness.adapter.captureStill(
            onDelivered = { bytes ->
                harness.events += "delivered"
                delivered += bytes
            },
            onError = { errors += it },
        )

        assertEquals(listOf("focus-started"), eventsFor(harness))
        assertEquals(0, harness.stillCapture.takePictureCalls)

        harness.focus.complete(successful = true)
        assertEquals(listOf("focus-started", "focus-completed", "takePicture"), eventsFor(harness))
        assertEquals(1, harness.stillCapture.takePictureCalls)

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)
        val image = CountingImageProxy(ImageFormat.JPEG, jpeg)
        harness.stillCapture.deliver(image)

        assertEquals(listOf("focus-started", "focus-completed", "takePicture", "delivered"), eventsFor(harness))
        assertEquals(1, delivered.size)
        assertArrayEquals(jpeg, delivered.single())
        assertTrue(errors.isEmpty())
        assertEquals(1, image.closeCalls)
        assertEquals(1, harness.finishCalls.get())
        harness.adapter.release()
    }

    @Test
    fun successfulErrorAndInvalidJpegCallbacksCloseAndFinishExactlyOnce() {
        val harness = harness()
        val delivered = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()
        val capture = harness.adapter

        capture.captureStill({ delivered += it }, { errors += it })
        harness.focus.complete(successful = true)
        val goodImage = CountingImageProxy(
            imageFormat = ImageFormat.JPEG,
            payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x02),
        )
        harness.stillCapture.deliver(goodImage)
        assertEquals(1, goodImage.closeCalls)
        assertEquals(1, harness.finishCalls.get())

        capture.captureStill({ delivered += it }, { errors += it })
        harness.focus.complete(successful = true)
        harness.stillCapture.fail()
        assertEquals(2, harness.finishCalls.get())
        assertEquals(listOf("Capture failed"), errors)

        capture.captureStill({ delivered += it }, { errors += it })
        harness.focus.complete(successful = true)
        val invalidImage = CountingImageProxy(ImageFormat.YUV_420_888, byteArrayOf(1, 2, 3))
        harness.stillCapture.deliver(invalidImage)
        assertEquals(1, invalidImage.closeCalls)
        assertEquals(3, harness.finishCalls.get())
        assertEquals(listOf("Capture failed", "Camera delivered an invalid JPEG still"), errors)
        assertEquals(1, delivered.size)
        capture.release()
    }

    @Test
    fun releaseCancelsPendingFocusAndLateFocusCannotTakePicture() {
        val harness = harness()
        val delivered = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()

        harness.adapter.captureStill({ delivered += it }, { errors += it })
        harness.adapter.release()
        harness.focus.complete(successful = true)

        assertEquals(1, harness.focus.cancelCalls)
        assertEquals(0, harness.stillCapture.takePictureCalls)
        assertTrue(delivered.isEmpty())
        assertTrue(errors.isEmpty())
        assertEquals(0, harness.finishCalls.get())
    }

    @Test
    fun lifecycleDestroyCancelsPendingFocusAndLateFocusIsInert() {
        val harness = harness()
        val delivered = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()

        harness.adapter.captureStill({ delivered += it }, { errors += it })
        harness.owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        harness.focus.complete(successful = true)

        assertEquals(1, harness.focus.cancelCalls)
        assertEquals(0, harness.stillCapture.takePictureCalls)
        assertTrue(delivered.isEmpty())
        assertTrue(errors.isEmpty())
        assertEquals(0, harness.finishCalls.get())
    }

    @Test
    fun adapterReleaseMakesLateCaptureCallbackInert() {
        val harness = harness()
        val delivered = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()

        harness.adapter.captureStill({ delivered += it }, { errors += it })
        harness.focus.complete(successful = true)
        assertEquals(1, harness.stillCapture.takePictureCalls)

        harness.adapter.release()
        val image = CountingImageProxy(
            imageFormat = ImageFormat.JPEG,
            payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x04),
        )
        harness.stillCapture.deliver(image)

        assertTrue(delivered.isEmpty())
        assertTrue(errors.isEmpty())
        assertEquals(1, image.closeCalls)
        assertEquals(1, harness.finishCalls.get())
    }

    private fun eventsFor(harness: Harness): List<String> =
        harness.events.toList()
}
