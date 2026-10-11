package dev.hryshyn.remanence.probe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.capture.CameraXBinding
import dev.hryshyn.remanence.capture.CameraXPreviewBinder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc

/** Real CameraX virtual-scene still + new native library; not postcard accuracy. */
@RunWith(AndroidJUnit4::class)
class CameraDeviceSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun realBindingDeliversDecodableStillAndNativeSiftRunsOnIt() {
        val bound = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val bytes = AtomicReference<ByteArray?>()
        val imageCapture = ImageCapture.Builder().build()
        var binding: CameraXBinding? = null
        try {
            rule.runOnUiThread {
                val activity = rule.activity
                val preview = PreviewView(activity)
                activity.setContentView(preview)
                binding = CameraXPreviewBinder.bind(activity, activity, preview, imageCapture,
                    { bound.countDown() }, { failure.set(it); bound.countDown(); delivered.countDown() })
            }
            assertTrue("camera did not bind", bound.await(25, TimeUnit.SECONDS))
            assertNull("camera binding error", failure.get())
            rule.runOnUiThread {
                CameraXPreviewBinder.captureOneStill(rule.activity, imageCapture,
                    { bytes.set(it); delivered.countDown() },
                    { failure.set(it); delivered.countDown() })
            }
            assertTrue("camera did not deliver still", delivered.await(25, TimeUnit.SECONDS))
            assertNull("camera capture error", failure.get())
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(checkNotNull(bytes.get()), 0, bytes.get()!!.size))
            try {
                assertTrue(bitmap.width >= 320 && bitmap.height >= 240)
                val evidence = File(rule.activity.getExternalFilesDir(null), "t169-fixture-evidence").apply { mkdirs() }
                File(evidence, "virtual-scene-camera.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue(OpenCVLoader.initDebug())
                val rgba = Mat(); val gray = Mat(); val keypoints = MatOfKeyPoint(); val descriptors = Mat(); val mask = Mat()
                try {
                    Utils.bitmapToMat(bitmap, rgba)
                    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                    SIFT.create().detectAndCompute(gray, mask, keypoints, descriptors)
                    assertTrue("virtual scene should have texture", keypoints.toArray().isNotEmpty())
                    assertEquals(128, descriptors.cols())
                } finally { mask.release(); descriptors.release(); keypoints.release(); gray.release(); rgba.release() }
            } finally { bitmap.recycle() }
        } finally {
            bytes.get()?.fill(0)
            rule.runOnUiThread { binding?.release() }
        }
    }
}
