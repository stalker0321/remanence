package dev.hryshyn.remanence.ui.collage

import kotlin.math.min

/**
 * Pure deterministic draw plan for an already-[CollageRenderer.Scene]-Ready
 * collage. No UI-toolkit types: integer rects, colours, and ordered [Op]s that
 * the Compose painter ([CollageCanvas]) consumes. Kept host-neutral so the
 * sender preview and the recipient presentation render the identical order.
 */
object CollagePainter {

    /** Uniform viewport scale plus centered origin, both in viewport pixels. */
    data class Transform(val scale: Float, val dx: Float, val dy: Float)

    /** Ordered drawing layers: paper, decor, then photos, then note, then music, then provenance. */
    enum class Layer { PAPER, DECOR, PHOTOS, NOTE, MUSIC, PROVENANCE }

    val LAYER_ORDER: List<Layer> = listOf(
        Layer.PAPER,
        Layer.DECOR,
        Layer.PHOTOS,
        Layer.NOTE,
        Layer.MUSIC,
        Layer.PROVENANCE,
    )

    /** One ordered draw instruction. Text ops carry the sealed layout inputs. */
    sealed interface Op {
        data class Paper(val color: Int, val rect: CollageRenderer.RectI) : Op

        /**
         * Authored decor plane (v4 only): index into
         * [CollageRenderer.Scene.decor]. The canvas resolves the sealed
         * region and fill from the scene.
         */
        data class Decor(val index: Int) : Op

        data class Photo(
            val contentId: String,
            val source: CollageRenderer.RectI,
            val destination: CollageRenderer.RectI,
            /** Aperture clip (v4 only; v3 scenes are always RECT). */
            val shape: dev.hryshyn.remanence.core.model.GeneratorExpression.PhotoShape =
                dev.hryshyn.remanence.core.model.GeneratorExpression.PhotoShape.RECT,
        ) : Op

        data class Note(
            val text: String,
            val region: CollageRenderer.RectI,
            val color: Int,
        ) : Op

        data class MusicBackground(
            val color: Int,
            val region: CollageRenderer.RectI,
            /**
             * True only for the two CSS-traced transparent bands: the
             * canvas draws no fill and a top border in [ink] instead.
             */
            val transparent: Boolean = false,
            val ink: Int = color,
        ) : Op

        data class MusicText(
            val title: String,
            val artist: String,
            val text: String,
            val region: CollageRenderer.RectI,
            val color: Int,
        ) : Op

        /**
         * Verified cover-art layer: the `sound` background behind a contained
         * image drawn from the host's verified bitmap. The bytes never enter
         * the op; only the sealed rectangle and the background colour do.
         */
        data class MusicArtwork(val region: CollageRenderer.RectI, val background: Int) : Op

        /**
         * Authored provenance mark (v4 only): index into
         * [CollageRenderer.Scene.provenance]. The canvas resolves the
         * sealed text/region/face/orientation/alignment from the scene.
         */
        data class Provenance(val index: Int) : Op
    }

    /**
     * `scale = min(vw/360, vh/640)` with the canvas centered in the viewport.
     * Returns `null` for a non-positive viewport or canvas, in which case the
     * painter draws nothing.
     */
    fun transform(
        viewportWidth: Float,
        viewportHeight: Float,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Transform? {
        if (viewportWidth <= 0f || viewportHeight <= 0f || canvasWidth <= 0 || canvasHeight <= 0) return null
        val scale = min(viewportWidth / canvasWidth, viewportHeight / canvasHeight)
        if (scale <= 0f) return null
        return Transform(
            scale = scale,
            dx = (viewportWidth - canvasWidth * scale) / 2f,
            dy = (viewportHeight - canvasHeight * scale) / 2f,
        )
    }

    /**
     * True only when every authored photo id binds to a decoded image of the
     * exact bound dimensions and every sealed source/destination rect is
     * positive and contained by that image. The painter refuses to draw
     * otherwise (no partial sheet, no silent omission, no out-of-bounds crop).
     */
    fun isDrawable(scene: CollageRenderer.Scene, images: Map<String, CollageRenderer.SourcePhoto>): Boolean {
        if (scene.photos.isEmpty()) return false
        if (scene.photos.map { it.contentId }.toSet() != images.keys) return false
        return scene.photos.all { photo ->
            val image = images.getValue(photo.contentId)
            val source = photo.source
            val destination = photo.destination
            image.width > 0 && image.height > 0 &&
                source.x >= 0 && source.y >= 0 &&
                source.width > 0 && source.height > 0 &&
                source.x.toLong() + source.width <= image.width &&
                source.y.toLong() + source.height <= image.height &&
                destination.width > 0 && destination.height > 0
        }
    }

    /**
     * Ordered, complete draw list for [scene], or `null` when not drawable.
     * Source/destination rects are the sealed [CollageRenderer.ScenePhoto]
     * values verbatim; the painter never recomputes crops. Frozen render
     * order is decor, photos, music, note, then provenance
     * (`grammars.js` `render`); decor rides behind the photos
     * (`z-index:-1`) and provenance closes the sheet.
     */
    fun drawList(scene: CollageRenderer.Scene, images: Map<String, CollageRenderer.SourcePhoto>): List<Op>? {
        if (!isDrawable(scene, images)) return null
        val ops = ArrayList<Op>(scene.photos.size + scene.decor.size + scene.provenance.size + 3)
        ops += Op.Paper(scene.paper, CollageRenderer.RectI(0, 0, scene.canvasWidth, scene.canvasHeight))
        scene.decor.forEachIndexed { index, _ -> ops += Op.Decor(index) }
        scene.photos.forEach { ops += Op.Photo(it.contentId, it.source, it.destination, it.shape) }
        scene.note?.let { ops += Op.Note(it.text, it.region, it.color) }
        scene.music?.let {
            ops += Op.MusicBackground(it.background, it.region, it.transparentBackground, it.color)
            ops += Op.MusicText(it.title, it.artist, it.text, it.region, it.color)
        }
        scene.artwork?.let {
            ops += Op.MusicArtwork(it.region, it.background)
        }
        scene.provenance.forEachIndexed { index, _ -> ops += Op.Provenance(index) }
        return ops
    }
}
