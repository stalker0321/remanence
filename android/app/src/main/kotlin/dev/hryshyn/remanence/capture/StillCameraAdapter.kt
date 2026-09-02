package dev.hryshyn.remanence.capture

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner

/**
 * FIX-STATE-08: THE testable camera driver boundary. The Compose layer owns
 * permission and composition; everything the hardware does - binding the
 * preview, delivering exactly one still, reporting failures, releasing use
 * cases - travels through this port. JVM/Robolectric tests inject a fake
 * adapter that excites the SAME production callbacks the real CameraX
 * adapter would, so transition tests exercise production wiring instead of
 * calling ViewModel methods directly.
 */
interface StillCameraAdapter {

    /** Renders the live preview surface inside the capture surface. */
    val preview: @Composable (Modifier) -> Unit

    /**
     * Binds preview + still-capture use cases. [onReady] fires once the
     * preview is live; [onError] reports an unrecoverable setup failure.
     */
    fun bind(onReady: () -> Unit, onError: (String) -> Unit)

    /**
     * Focuses/meters once, then takes exactly one still. Raw JPEG bytes travel
     * only through [onDelivered]; nothing is stored by the adapter.
     */
    fun captureStill(onDelivered: (ByteArray) -> Unit, onError: (String) -> Unit)

    /**
     * FIX-STATE-04: releases every bound use case. After release, pending or
     * later bind/capture callbacks must be inert - never delivered anywhere.
     */
    fun release()
}

/** Internal factory used only to stitch the adapter to a real binding in JVM tests. */
internal fun interface CameraXBindingFactory {
    fun create(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        imageCapture: ImageCapture,
        onBound: () -> Unit,
        onError: (String) -> Unit,
    ): CameraXBinding
}

/**
 * Production adapter over [CameraXPreviewBinder]. The host composes [preview]
 * FIRST (which creates the PreviewView), then calls [bind]. One instance
 * serves ONE binding cycle: after [release] every late CameraX callback is
 * dropped instead of reaching a dead capture attempt.
 */
class CameraXStillCameraAdapter(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : StillCameraAdapter {

    private val imageCapture: ImageCapture = CameraXPreviewBinder.createImageCapture()

    private var bindingFactory: CameraXBindingFactory = CameraXBindingFactory {
        factoryContext,
        factoryLifecycleOwner,
        factoryPreviewView,
        factoryImageCapture,
        factoryOnBound,
        factoryOnError,
        ->
        CameraXPreviewBinder.bind(
            context = factoryContext,
            lifecycleOwner = factoryLifecycleOwner,
            previewView = factoryPreviewView,
            imageCapture = factoryImageCapture,
            onBound = factoryOnBound,
            onError = factoryOnError,
        )
    }

    internal constructor(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        bindingFactory: CameraXBindingFactory,
    ) : this(context, lifecycleOwner) {
        this.bindingFactory = bindingFactory
    }

    @Volatile
    private var released = false

    @Volatile
    private var previewView: PreviewView? = null

    private var binding: CameraXBinding? = null

    override val preview: @Composable (Modifier) -> Unit = { modifier ->
        AndroidView(
            factory = { ctx -> PreviewView(ctx).also { previewView = it } },
            modifier = modifier,
        )
    }

    override fun bind(onReady: () -> Unit, onError: (String) -> Unit) {
        if (released) return
        val view = requireNotNull(previewView) { "preview must be composed before bind()" }
        binding = bindingFactory.create(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = view,
            imageCapture = imageCapture,
            onBound = { if (!released) onReady() },
            onError = { reason -> if (!released) onError(reason) },
        )
    }

    override fun captureStill(onDelivered: (ByteArray) -> Unit, onError: (String) -> Unit) {
        if (released) return
        val activeBinding = binding ?: return
        if (!activeBinding.beginCapture()) return
        try {
            activeBinding.captureOneStill(
                onDelivered = { bytes -> if (!released) onDelivered(bytes) },
                onError = { reason -> if (!released) onError(reason) },
            )
        } catch (failure: Throwable) {
            activeBinding.finishCapture()
            throw failure
        }
    }

    override fun release() {
        if (released) return
        released = true
        binding?.release()
        binding = null
    }
}
