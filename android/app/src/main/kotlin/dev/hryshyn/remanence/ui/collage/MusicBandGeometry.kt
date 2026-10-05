package dev.hryshyn.remanence.ui.collage

/** Shared, unsealed paint geometry for the frozen music play control and copy. */
internal object MusicBandGeometry {
    data class Layout(val copy: CollageRenderer.RectI, val play: CollageRenderer.RectI)

    fun layout(region: CollageRenderer.RectI, grammar: String, mode: String, artwork: Boolean): Layout? {
        val band = V5Decor.bandLayout(grammar, mode, region.height)
        val size = if (mode == "spine" || region.height < 65 || grammar.removePrefix("g") == "11") 34 else 36
        val x = region.x + band.padSide
        val y = region.y + band.padTop
        val width = region.width - 2 * band.padSide
        val height = region.height - band.padTop - band.padBottom
        if (width < size || height < size) return null
        if (mode == "spine") {
            return Layout(region, CollageRenderer.RectI(x + (width - size) / 2, y, size, size))
        }
        if (artwork) {
            val cover = V5Decor.placeCover(region.x, region.y, region.width, region.height, grammar, mode)
                ?: return null
            val g = grammar.removePrefix("g")
            val overlay = when {
                g == "11" && mode == "tile-art" -> CollageRenderer.RectI(region.x + 12, region.y + 12, size, size)
                g == "24" && mode == "tile-art" -> CollageRenderer.RectI(region.x + 61, region.y + 15, size, size)
                g == "20" && mode == "sleeve" -> CollageRenderer.RectI(region.x + 7, region.y + region.height - 13 - size, size, size)
                else -> null
            }
            if (overlay != null) {
                if (overlay.x < region.x || overlay.y < region.y ||
                    overlay.x + overlay.width > region.x + region.width ||
                    overlay.y + overlay.height > region.y + region.height) return null
                return Layout(cover.text, overlay)
            }
            val copy = cover.text.copy(x = cover.text.x + size + band.gap, width = cover.text.width - size - band.gap)
            if (copy.width < 1) return null
            return Layout(copy, CollageRenderer.RectI(cover.text.x, y + (height - size) / 2, size, size))
        }
        val copy = if (band.column) {
            CollageRenderer.RectI(x, y + size + band.gap, width, height - size - band.gap)
        } else {
            CollageRenderer.RectI(x + size + band.gap, y, width - size - band.gap, height)
        }
        if (copy.width < 1 || copy.height < 1) return null
        return Layout(copy, CollageRenderer.RectI(x, if (band.column) y else y + (height - size) / 2, size, size))
    }
}
