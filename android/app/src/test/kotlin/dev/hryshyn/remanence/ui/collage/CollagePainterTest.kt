package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for the deterministic painter plan: scale/centering,
 * layer order, sealed source/destination rect use, sealed op colours, and
 * fail-closed draw-list construction (ids, image dimensions, source bounds).
 * No UI-toolkit types.
 */
class CollagePainterTest {

    private fun rect(x: Int, y: Int, w: Int, h: Int) = CollageRenderer.RectI(x, y, w, h)

    private fun photo(id: String, source: CollageRenderer.RectI, destination: CollageRenderer.RectI) =
        CollageRenderer.ScenePhoto(contentId = id, destination = destination, source = source)

    private val photos = listOf(
        photo("p0", rect(10, 20, 500, 600), rect(0, 0, 120, 213)),
        photo("p1", rect(0, 0, 1000, 1000), rect(120, 0, 120, 213)),
        photo("p2", rect(0, 0, 1000, 1000), rect(240, 0, 120, 213)),
    )

    private fun scene(
        note: CollageRenderer.SceneText? = null,
        music: CollageRenderer.SceneMusic? = null,
    ) = CollageRenderer.Scene(
        canvasWidth = 360,
        canvasHeight = 640,
        styleId = "press",
        paper = 0xFFF2F3B0.toInt(),
        ink = 0xFF191B19.toInt(),
        sound = 0xFF191B19.toInt(),
        soundInk = 0xFFF2F3B0.toInt(),
        photos = photos,
        note = note,
        music = music,
    )

    private fun imageSizes() =
        setOf("p0", "p1", "p2").associateWith { CollageRenderer.SourcePhoto(1000, 1000) }

    @Test
    fun transformScalesToFitAndCenters() {
        assertEquals(CollagePainter.Transform(2f, 0f, 0f), CollagePainter.transform(720f, 1280f, 360, 640))
        assertEquals(CollagePainter.Transform(1f, 320f, 0f), CollagePainter.transform(1000f, 640f, 360, 640))
        assertEquals(CollagePainter.Transform(1f, 0f, 180f), CollagePainter.transform(360f, 1000f, 360, 640))
    }

    @Test
    fun transformRejectsNonPositiveViewportOrCanvas() {
        assertNull(CollagePainter.transform(0f, 640f, 360, 640))
        assertNull(CollagePainter.transform(360f, 0f, 360, 640))
        assertNull(CollagePainter.transform(360f, 640f, 0, 640))
        assertNull(CollagePainter.transform(360f, 640f, 360, 0))
    }

    @Test
    fun layerOrderIsPaperPhotosNoteMusic() {
        assertEquals(
            listOf(
                CollagePainter.Layer.PAPER,
                CollagePainter.Layer.PHOTOS,
                CollagePainter.Layer.NOTE,
                CollagePainter.Layer.MUSIC,
            ),
            CollagePainter.LAYER_ORDER,
        )
    }

    @Test
    fun drawOrderIsPaperThenPhotosThenNoteThenMusic() {
        val note = CollageRenderer.SceneText(
            text = "hi", region = rect(0, 400, 360, 200),
            family = "StudySans", size = 16, weight = 400, italic = false, line = 18,
            fontResourceId = 1, color = 0xFF191B19.toInt(),
        )
        val music = CollageRenderer.SceneMusic(
            title = "505", artist = "Arctic Monkeys", text = "505\nArctic Monkeys",
            region = rect(0, 220, 360, 60),
            family = "StudySerif", size = 14, weight = 400, italic = true, line = 16,
            fontResourceId = 2, background = 0xFF191B19.toInt(), color = 0xFFF2F3B0.toInt(),
        )
        val ops = CollagePainter.drawList(scene(note = note, music = music), imageSizes())
            ?: error("expected a draw list")
        assertEquals(
            listOf(
                CollagePainter.Op.Paper::class.simpleName,
                CollagePainter.Op.Photo::class.simpleName,
                CollagePainter.Op.Photo::class.simpleName,
                CollagePainter.Op.Photo::class.simpleName,
                CollagePainter.Op.Note::class.simpleName,
                CollagePainter.Op.MusicBackground::class.simpleName,
                CollagePainter.Op.MusicText::class.simpleName,
            ),
            ops.map { it::class.simpleName },
        )
    }

    @Test
    fun textOpsCarryTheSealedColors() {
        val note = CollageRenderer.SceneText(
            text = "hi", region = rect(0, 400, 360, 200),
            family = "StudySans", size = 16, weight = 400, italic = false, line = 18,
            fontResourceId = 1, color = 0xFF010203.toInt(),
        )
        val music = CollageRenderer.SceneMusic(
            title = "505", artist = "Arctic Monkeys", text = "505\nArctic Monkeys",
            region = rect(0, 220, 360, 60),
            family = "StudySerif", size = 14, weight = 400, italic = true, line = 16,
            fontResourceId = 2, background = 0xFF040506.toInt(), color = 0xFF070809.toInt(),
        )
        val ops = CollagePainter.drawList(scene(note = note, music = music), imageSizes())
            ?: error("expected a draw list")
        val noteOp = ops.filterIsInstance<CollagePainter.Op.Note>().single()
        val musicBackground = ops.filterIsInstance<CollagePainter.Op.MusicBackground>().single()
        val musicText = ops.filterIsInstance<CollagePainter.Op.MusicText>().single()
        assertEquals(0xFF010203.toInt(), noteOp.color)
        assertEquals(0xFF040506.toInt(), musicBackground.color)
        assertEquals(0xFF070809.toInt(), musicText.color)
    }

    @Test
    fun emittedPhotoUsesSealedSourceAndDestinationRects() {
        val ops = CollagePainter.drawList(scene(), imageSizes()) ?: error("expected a draw list")
        val emitted = ops.filterIsInstance<CollagePainter.Op.Photo>()
        assertEquals(listOf("p0", "p1", "p2"), emitted.map { it.contentId })
        assertEquals(rect(10, 20, 500, 600), emitted[0].source)
        assertEquals(rect(0, 0, 120, 213), emitted[0].destination)
        assertEquals(photos.map { it.source }, emitted.map { it.source })
        assertEquals(photos.map { it.destination }, emitted.map { it.destination })
    }

    @Test
    fun paperCoversTheFullReferenceCanvas() {
        val paper = (CollagePainter.drawList(scene(), imageSizes()) ?: error("expected a draw list"))
            .filterIsInstance<CollagePainter.Op.Paper>()
            .single()
        assertEquals(rect(0, 0, 360, 640), paper.rect)
        assertEquals(0xFFF2F3B0.toInt(), paper.color)
    }

    @Test
    fun missingOrExtraImageYieldsNoDrawListAndIsNotDrawable() {
        val missing = imageSizes().filterKeys { it != "p2" }
        assertNull(CollagePainter.drawList(scene(), missing))
        assertFalse(CollagePainter.isDrawable(scene(), missing))

        val extra = imageSizes() + ("extra" to CollageRenderer.SourcePhoto(10, 10))
        assertNull(CollagePainter.drawList(scene(), extra))

        assertTrue(CollagePainter.isDrawable(scene(), imageSizes()))
    }

    @Test
    fun rejectsSourceRectOutsideTheBoundBitmap() {
        val outOfBounds = scene().copy(
            photos = listOf(photos[0].copy(source = rect(0, 0, 1001, 1000))) + photos.drop(1),
        )
        assertFalse(CollagePainter.isDrawable(outOfBounds, imageSizes()))
        assertNull(CollagePainter.drawList(outOfBounds, imageSizes()))
    }

    @Test
    fun rejectsNegativeOrEmptySourceRect() {
        val negative = scene().copy(
            photos = listOf(photos[0].copy(source = rect(-1, 0, 10, 10))) + photos.drop(1),
        )
        assertFalse(CollagePainter.isDrawable(negative, imageSizes()))
        assertNull(CollagePainter.drawList(negative, imageSizes()))

        val empty = scene().copy(
            photos = listOf(photos[0].copy(source = rect(0, 0, 0, 10))) + photos.drop(1),
        )
        assertFalse(CollagePainter.isDrawable(empty, imageSizes()))
    }

    @Test
    fun rejectsBitmapSmallerThanTheBoundSourceRect() {
        val small = setOf("p0", "p1", "p2").associateWith { CollageRenderer.SourcePhoto(4, 4) }
        assertFalse(CollagePainter.isDrawable(scene(), small))
        assertNull(CollagePainter.drawList(scene(), small))
    }

    @Test
    fun emptyPhotoSetIsNotDrawable() {
        val empty = scene().copy(photos = emptyList())
        assertFalse(CollagePainter.isDrawable(empty, emptyMap()))
        assertNull(CollagePainter.drawList(empty, emptyMap()))
    }
}
