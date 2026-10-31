package dev.hryshyn.remanence.ui.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Size
import dev.hryshyn.remanence.capture.CapturedPostcard
import dev.hryshyn.remanence.core.recognition.PointD
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostcardPainterTest {
    @Test
    fun coveringPhotoThatRoundsShortOfTheStageStillDraws() {
        // 1080x1440 scaled to cover 626x825 lands 6e-5 px short of the width.
        val photo = Bitmap.createBitmap(1080, 1440, Bitmap.Config.ARGB_8888)
        val postcard = CapturedPostcard(
            photo,
            listOf(PointD(140.0, 360.0), PointD(940.0, 360.0), PointD(940.0, 1000.0), PointD(140.0, 1000.0)),
        )
        val canvas = Canvas(Bitmap.createBitmap(626, 825, Bitmap.Config.ARGB_8888))
        val painter = PostcardPainter()
        for (lift in listOf(0f, 0.5f, 1f)) {
            painter.draw(canvas, Size(626f, 825f), postcard, lift, density = 2.75f)
        }
    }
}
