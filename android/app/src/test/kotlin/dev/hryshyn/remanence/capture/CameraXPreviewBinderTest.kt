package dev.hryshyn.remanence.capture

import android.view.Surface
import android.view.View
import androidx.camera.core.ImageCapture
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraXPreviewBinderTest {

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry
    }

    private class RecordingProvider : CameraProviderPort {
        val groups = mutableListOf<UseCaseGroup>()
        val unbound = mutableListOf<List<UseCase>>()

        override fun bindToLifecycle(lifecycleOwner: LifecycleOwner, group: UseCaseGroup) {
            groups += group
        }

        override fun unbind(vararg useCases: UseCase) {
            unbound += useCases.toList()
        }

        override fun unbindAll() = Unit
    }

    private fun measuredView(width: Int, height: Int): PreviewView =
        PreviewView(ApplicationProvider.getApplicationContext()).also { view ->
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
        }

    @Test
    fun measuredFillCenterViewPortAndRotationAreSharedByBothUseCases() {
        val view = measuredView(1080, 1920)
        CameraXPreviewBinder.configurePreviewView(view)
        val capture = CameraXPreviewBinder.createImageCapture()
        val rotation = Surface.ROTATION_90
        val viewPort = requireNotNull(view.getViewPort(rotation))
        val useCases = CameraXPreviewBinder.createUseCaseSet(view, capture, rotation, viewPort)
        val groupViewPort = requireNotNull(useCases.group.viewPort)

        assertEquals(PreviewView.ScaleType.FILL_CENTER, view.scaleType)
        assertEquals(ViewPort.FILL_CENTER, groupViewPort.scaleType)
        assertSame(viewPort, groupViewPort)
        assertEquals(rotation, groupViewPort.rotation)
        assertEquals(rotation, useCases.preview.targetRotation)
        assertEquals(rotation, capture.targetRotation)
        assertEquals(listOf(useCases.preview, capture), useCases.group.useCases)
    }

    @Test
    fun bindingWaitsForMeasuredViewAndRebindsOnRotationAndLifecycleRestart() {
        val view = PreviewView(ApplicationProvider.getApplicationContext())
        CameraXPreviewBinder.configurePreviewView(view)
        val owner = TestLifecycleOwner()
        val provider = RecordingProvider()
        val capture = CameraXPreviewBinder.createImageCapture()
        var rotation: Int? = Surface.ROTATION_0
        var viewPort: ViewPort? = null
        var boundCallbacks = 0
        val controller = CameraXBindingController(
            previewView = view,
            lifecycleOwner = owner,
            imageCapture = capture,
            onBound = { boundCallbacks++ },
            onError = { errorMessage -> throw AssertionError("unexpected camera error: $errorMessage") },
            rotationProvider = { rotation },
            viewPortProvider = { viewPort },
        )

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        controller.attach(provider)
        assertTrue(provider.groups.isEmpty())

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        controller.onLifecycleStart()
        assertTrue(provider.groups.isEmpty())

        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 1920)
        viewPort = requireNotNull(view.getViewPort(Surface.ROTATION_0))
        controller.refresh()
        assertEquals(1, provider.groups.size)
        assertEquals(1, boundCallbacks)
        val first = provider.groups.single()
        assertEquals(Surface.ROTATION_0, requireNotNull(first.viewPort).rotation)

        rotation = Surface.ROTATION_90
        viewPort = view.getViewPort(Surface.ROTATION_90)
        controller.refresh()

        assertEquals(2, provider.groups.size)
        assertEquals(1, provider.unbound.size)
        assertSame(first.getUseCases()[0], provider.unbound[0][0])
        assertSame(capture, provider.unbound[0][1])
        assertEquals(Surface.ROTATION_90, requireNotNull(provider.groups.last().viewPort).rotation)
        assertEquals(Surface.ROTATION_90, capture.targetRotation)
        assertEquals(1, boundCallbacks)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        controller.onLifecycleStop()
        assertEquals(2, provider.unbound.size)
        assertEquals(2, provider.groups.size)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        controller.onLifecycleStart()
        assertEquals(3, provider.groups.size)
        assertEquals(Surface.ROTATION_90, requireNotNull(provider.groups.last().viewPort).rotation)
        assertFalse(provider.groups.last() === first)
        assertEquals(1, boundCallbacks)

        controller.release()
        controller.refresh()
        assertEquals(3, provider.unbound.size)
        assertEquals(3, provider.groups.size)
    }

    @Test
    fun inFlightCaptureFencesRefreshesAndReleaseUntilLatestCompletion() {
        val view = measuredView(1080, 1920)
        CameraXPreviewBinder.configurePreviewView(view)
        val owner = TestLifecycleOwner()
        val provider = RecordingProvider()
        val capture = CameraXPreviewBinder.createImageCapture()
        var rotation = Surface.ROTATION_0
        var viewPort: ViewPort = requireNotNull(view.getViewPort(rotation))
        val controller = CameraXBindingController(
            previewView = view,
            lifecycleOwner = owner,
            imageCapture = capture,
            onBound = {},
            onError = { errorMessage -> throw AssertionError("unexpected camera error: $errorMessage") },
            rotationProvider = { rotation },
            viewPortProvider = { viewPort },
        )

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        controller.attach(provider)
        controller.onLifecycleStart()
        assertEquals(1, provider.groups.size)

        controller.captureStarted()
        rotation = Surface.ROTATION_90
        viewPort = requireNotNull(view.getViewPort(rotation))
        controller.refresh()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1200, 1800)
        viewPort = requireNotNull(view.getViewPort(rotation))
        controller.refresh()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        controller.onLifecycleStop()
        assertTrue(provider.unbound.isEmpty())

        rotation = Surface.ROTATION_180
        viewPort = requireNotNull(view.getViewPort(rotation))
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        controller.onLifecycleStart()
        controller.refresh()
        assertEquals(1, provider.groups.size)
        assertTrue(provider.unbound.isEmpty())

        controller.captureFinished()
        assertEquals(2, provider.groups.size)
        assertEquals(1, provider.unbound.size)
        assertEquals(Surface.ROTATION_180, requireNotNull(provider.groups.last().viewPort).rotation)
        assertEquals(Surface.ROTATION_180, capture.targetRotation)
        controller.refresh()
        assertEquals(2, provider.groups.size)

        controller.captureStarted()
        controller.release()
        controller.release()
        assertEquals(1, provider.unbound.size)
        controller.captureFinished()
        controller.captureFinished()
        assertEquals(2, provider.unbound.size)
    }
}
