package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T154: v4 painter plan — decor rides behind photos, provenance closes the
 * sheet, shapes travel on photo ops, transparent bands travel on music ops.
 */
class CollagePainterV4Test {

    private fun rect(x: Int, y: Int, w: Int, h: Int) = CollageRenderer.RectI(x, y, w, h)

    private fun scene(): CollageRenderer.Scene {
        val photos = listOf(
            CollageRenderer.ScenePhoto("p0", rect(0, 0, 120, 213), rect(10, 20, 500, 600)),
            CollageRenderer.ScenePhoto(
                "p1", rect(120, 0, 120, 213), rect(0, 0, 1000, 1000),
                GeneratorExpression.PhotoShape.ROUND,
            ),
            CollageRenderer.ScenePhoto("p2", rect(240, 0, 120, 213), rect(0, 0, 1000, 1000)),
        )
        return CollageRenderer.Scene(
            canvasWidth = 360,
            canvasHeight = 640,
            styleId = "signal",
            paper = 0xFFF34E33.toInt(),
            ink = 0xFF291D19.toInt(),
            sound = 0xFF291D19.toInt(),
            soundInk = 0xFFFFD794.toInt(),
            photos = photos,
            note = null,
            music = CollageRenderer.SceneMusic(
                title = "505", artist = "Arctic Monkeys", text = "505\nArctic Monkeys",
                region = rect(10, 500, 340, 76),
                family = "Narrow", size = 24, weight = 700, italic = false, line = 26,
                fontResourceId = 1, background = 0xFF291D19.toInt(), color = 0xFFFFD794.toInt(),
            ),
            artwork = CollageRenderer.SceneArtwork(rect(10, 500, 340, 76), 0xFF291D19.toInt()),
            provenance = listOf(
                CollageRenderer.SceneProvenance(
                    kind = GeneratorExpression.ProvenanceKind.BRAND,
                    text = "Remanence", region = rect(12, 4, 340, 75),
                    family = "StudySerif", size = 64, weight = 400, italic = false, line = 70,
                    orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
                    align = GeneratorExpression.MarkAlign.LEFT,
                    fontResourceId = 2, color = 0xFF291D19.toInt(), tracking = -0.065,
                ),
            ),
            decor = listOf(
                CollageRenderer.SceneDecor(
                    kind = GeneratorExpression.DecorKind.CARRIER,
                    region = rect(6, 5, 349, 627),
                    background = 0xFFF34E33.toInt(), border = 0xFF291D19.toInt(),
                ),
            ),
        )
    }

    private fun imageSizes() =
        setOf("p0", "p1", "p2").associateWith { CollageRenderer.SourcePhoto(1000, 1000) }

    @Test
    fun v4DrawOrderIsPaperDecorPhotosMusicProvenance() {
        val ops = CollagePainter.drawList(scene(), imageSizes())
            ?: error("expected a draw list")
        assertEquals(
            listOf(
                "Paper", "Decor",
                "Photo", "Photo", "Photo",
                "MusicBackground", "MusicText", "MusicArtwork",
                "Provenance",
            ),
            ops.map { it::class.simpleName },
        )
        val round = ops.filterIsInstance<CollagePainter.Op.Photo>()[1]
        assertEquals(GeneratorExpression.PhotoShape.ROUND, round.shape)
        val decor = ops.filterIsInstance<CollagePainter.Op.Decor>().single()
        assertEquals(0, decor.index)
        val mark = ops.filterIsInstance<CollagePainter.Op.Provenance>().single()
        assertEquals(0, mark.index)
    }

    @Test
    fun v3SceneHasNoV4Ops() {
        val ops = CollagePainter.drawList(scene().copy(provenance = emptyList(), decor = emptyList()), imageSizes())
            ?: error("expected a draw list")
        assertEquals(
            listOf("Paper", "Photo", "Photo", "Photo", "MusicBackground", "MusicText", "MusicArtwork"),
            ops.map { it::class.simpleName },
        )
    }
}
