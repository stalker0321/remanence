package dev.hryshyn.remanence.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.graphics.PointF
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.MeteringPointFactory
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.QualityReason
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CameraFocusDiagnosticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class ManualTimeoutScheduler : FocusTimeoutScheduler {
        private var task: (() -> Unit)? = null
        var cancelled = false
            private set

        override fun schedule(delayMillis: Long, task: () -> Unit): FocusCancellation {
            this.task = task
            return FocusCancellation {
                cancelled = true
                this.task = null
            }
        }

        fun fire() = checkNotNull(task).invoke()
    }

    private class RecordingFocusDriver : CenterFocusDriver {
        var result: ((Boolean) -> Unit)? = null
            private set
        var failure: (() -> Unit)? = null
            private set
        var cancelCalls = 0
            private set

        override fun start(onResult: (Boolean) -> Unit, onFailure: () -> Unit): FocusCancellation {
            result = onResult
            failure = onFailure
            return FocusCancellation { cancelCalls++ }
        }

        fun complete(successful: Boolean) = checkNotNull(result).invoke(successful)

        fun fail() = checkNotNull(failure).invoke()
    }

    private class RecordingCameraControl : CameraControl {
        var action: FocusMeteringAction? = null
            private set
        var cancelCalls = 0
            private set
        val result: com.google.common.util.concurrent.SettableFuture<FocusMeteringResult> =
            com.google.common.util.concurrent.SettableFuture.create()

        override fun enableTorch(torch: Boolean): ListenableFuture<Void> = pendingVoid()

        override fun startFocusAndMetering(action: FocusMeteringAction): ListenableFuture<FocusMeteringResult> {
            this.action = action
            return result
        }

        override fun cancelFocusAndMetering(): ListenableFuture<Void> {
            cancelCalls++
            return pendingVoid()
        }

        override fun setZoomRatio(ratio: Float): ListenableFuture<Void> = pendingVoid()

        override fun setLinearZoom(linearZoom: Float): ListenableFuture<Void> = pendingVoid()

        override fun setExposureCompensationIndex(index: Int): ListenableFuture<Int> =
            Futures.immediateFuture(index)

        private fun pendingVoid(): ListenableFuture<Void> =
            com.google.common.util.concurrent.SettableFuture.create()
    }

    private class RecordingMeteringPointFactory : MeteringPointFactory() {
        var point: PointF? = null
            private set

        override fun convertPoint(x: Float, y: Float): PointF = PointF(x, y).also { point = it }
    }

    @Test
    fun cameraFocusDriverMetersCenterForAfAndAe() {
        val control = RecordingCameraControl()
        val factory = RecordingMeteringPointFactory()
        val driver = CameraXCenterFocusDriver(
            cameraControl = control,
            meteringPointFactory = factory,
            width = 1000,
            height = 600,
            callbackExecutor = Executor { it.run() },
        )
        var focused = false

        driver.start(onResult = { focused = it }, onFailure = { error("focus failed") })

        assertEquals(500f, checkNotNull(factory.point).x)
        assertEquals(300f, checkNotNull(factory.point).y)
        assertEquals(1, checkNotNull(control.action).meteringPointsAf.size)
        assertEquals(1, checkNotNull(control.action).meteringPointsAe.size)
        assertFalse(focused)

        control.result.set(FocusMeteringResult.create(true))
        assertTrue(focused)
    }

    @Test
    fun backStillStartsOnlyAfterCenterFocusCompletes() {
        val events = mutableListOf<String>()
        val driver = RecordingFocusDriver()
        val scheduler = ManualTimeoutScheduler()
        val focus = BoundedCenterFocus(driver, scheduler, timeoutMillis = 100L)

        // BACK and FRONT share this capture adapter; focus is intentionally
        // side-independent and always meters the center.
        focus.start(
            onFocused = { events += "still" },
            onError = { events += "error" },
        )
        events += "focus-started"

        assertEquals(listOf("focus-started"), events)
        driver.complete(successful = true)
        assertEquals(listOf("focus-started", "still"), events)
    }

    @Test
    fun focusTimeoutReportsHonestFailureWithoutStartingStill() {
        val driver = RecordingFocusDriver()
        val scheduler = ManualTimeoutScheduler()
        val errors = mutableListOf<String>()
        var stills = 0
        BoundedCenterFocus(driver, scheduler, timeoutMillis = 100L).start(
            onFocused = { stills++ },
            onError = { errors += it },
        )

        scheduler.fire()

        assertEquals(0, stills)
        assertEquals(listOf("Camera focus timed out; hold steady and try again."), errors)
        assertEquals(1, driver.cancelCalls)
    }

    @Test
    fun focusFailureReportsHonestFailureWithoutStartingStill() {
        val driver = RecordingFocusDriver()
        val scheduler = ManualTimeoutScheduler()
        val errors = mutableListOf<String>()
        var stills = 0
        BoundedCenterFocus(driver, scheduler, timeoutMillis = 100L).start(
            onFocused = { stills++ },
            onError = { errors += it },
        )

        driver.fail()

        assertEquals(0, stills)
        assertEquals(listOf("Camera focus is unavailable; capture was not taken."), errors)
        assertTrue(scheduler.cancelled)
    }

    @Test
    fun lifecycleCancellationIsSilentAndLateFocusCannotStartStill() {
        val driver = RecordingFocusDriver()
        val scheduler = ManualTimeoutScheduler()
        val events = mutableListOf<String>()
        val cancelled = BoundedCenterFocus(driver, scheduler, timeoutMillis = 100L).start(
            onFocused = { events += "still" },
            onError = { events += "error" },
        )

        cancelled.cancel()
        driver.complete(successful = true)

        assertTrue(events.isEmpty())
        assertEquals(1, driver.cancelCalls)
        assertTrue(scheduler.cancelled)
    }

    @Test
    fun rejectionPanelShowsExactRedactedDiagnosticInDebug() {
        val diagnostic = CaptureDiagnostic(
            side = FingerprintSide.BACK,
            stage = CaptureDiagnosticStage.QUALITY,
            laplacianThreshold = 55.0,
            laplacianVariance = 60.031223,
            nearBlackFraction = 0.0103,
            clippedWhiteFraction = 0.0014,
            largestGlareFraction = 0.0020,
            usedGuideFallback = true,
            warpedWidth = 1600,
            warpedHeight = 1067,
            orbKeypoints = 763,
            orbDescriptors = 763,
        )
        val expected =
            "DEBUG capture: side=BACK stage=QUALITY laplacian=60.0312 threshold=55.0000 " +
                "darkness=0.0103 clippedWhite=0.0014 glare=0.0020 cropFallback=true " +
                "warp=1600x1067 orb=763 descriptors=763"

        composeRule.setContent {
            MaterialTheme {
                QualityRejectionPanel(
                    reasons = setOf(QualityReason.TOO_BLURRY),
                    diagnostic = diagnostic,
                    onRecapture = {},
                )
            }
        }

        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }
}
