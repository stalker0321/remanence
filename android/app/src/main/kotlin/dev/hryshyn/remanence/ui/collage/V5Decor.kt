package dev.hryshyn.remanence.ui.collage

/**
 * Frozen V5 shared-layer rendering facts (T154).
 *
 * Traced rule: no fill/geometry rule exists in frozen code/CSS beyond what
 * is listed here. Anything without a traced rule is flagged in `result.md`,
 * never invented silently.
 */
object V5Decor {

    /**
     * Provisional kind-to-palette-role map (explicitly provisional: R1).
     * `plan.decor` carries kind + region only; no fill rule exists in
     * frozen code/CSS. Geometry is exact; fills follow this map pending
     * specimen grounding.
     *
     * - `carrier` → `surface` background + `edge` border
     *   (`styles.css`: `.styled .carrier`).
     * - `acid-ribbon` → `sound` background
     *   (`styles.css`: `.styled .acid-ribbon`).
     * - `print-plane` → `surface` background
     *   (`styles.css`: `.styled .print-plane`).
     */
    enum class FillRole { SURFACE, SOUND, EDGE }

    data class DecorFill(val background: FillRole, val border: FillRole?)

    fun fillFor(kind: String): DecorFill = when (kind) {
        "carrier" -> DecorFill(FillRole.SURFACE, FillRole.EDGE)
        "acid-ribbon" -> DecorFill(FillRole.SOUND, null)
        else -> DecorFill(FillRole.SURFACE, null)
    }

    /**
     * Exact CSS-traced transparent music bands: grammar 5 `blue-letter`
     * and grammar 27 `porcelain` render the music band transparent with
     * the `ink` foreground and a top border (`styles.css`
     * `.styled.g5[data-style=blue-letter] .music`,
     * `.styled.g27[data-style=porcelain] .music`). Every other
     * style/grammar pair renders the `sound` background.
     */
    fun isTransparentMusicBand(grammarId: String, styleId: String): Boolean =
        (grammarId == "5" || grammarId == "g5") && styleId == "blue-letter" ||
            (grammarId == "27" || grammarId == "g27") && styleId == "porcelain"

    /**
     * Exact port of `crop.js` `maskPoint` for the `round` corner radius:
     * `r = .12` of each axis. The renderer clips `round` photo slots to a
     * rounded rect with `cornerRadiusX = .12 * width`,
     * `cornerRadiusY = .12 * height`; `ellipse` clips to the inscribed
     * ellipse; `rect` fills.
     */
    const val ROUND_CORNER_FRACTION = 0.12

    /**
     * Frozen DOM artwork gate (`grammars.js` `musicNode`): a cover image
     * renders only for these modes when artwork is present. `text`,
     * `tile-text`, `sleeve-empty`, and `spine` are text-only bands even
     * with artwork (spine never carries an image in the frozen DOM).
     * Single-sourced from the model matrix (F4); do not copy.
     */
    val COVER_IMAGE_MODES: Set<String>
        get() = dev.hryshyn.remanence.core.model.GeneratorExpression.MUSIC_COVER_MODES_V4

    /**
     * Exact frozen cover-artwork box per (grammar, mode), in canvas units
     * (`v4.css`; 360-wide reference so CSS px transfer 1:1). `width = null`
     * means FILL (g11 tile-art spans the region width). `fit` mirrors
     * `object-fit` (default `contain`; g11 tile-art is `cover`).
     *
     * Cascade (later stylesheet rules win ties; compact beats same-weight
     * mode rules because it sorts later in `v4.css`):
     * - region height < 65 ⇒ `music-compact` 36×36
     *   (`grammars.js:182` adds the class; `.capsule .music-compact
     *   .artwork{width:36px;height:36px}`).
     * - default `.artwork{width:40px;height:40px}` (all thumbnails).
     * - `.music-inline .artwork{39px}`; g23 inline is 37×37
     *   (`.g23 .music .artwork` outranks by specificity).
     * - g11 tile-art: full region width × 60, cover
     *   (`.g11 .music .artwork{width:100%;height:60px;object-fit:cover}`).
     * - g24 tile-art: 50×50 (`.g24 .music-tile-art .artwork`).
     * - g20 sleeve: 120×120 (V4 `.g20 .music-sleeve .artwork`).
     * - g24 pulse: 57×57 (`.g24 .music-pulse .artwork`).
     * - spine/text/tile-text/sleeve-empty: NO cover (frozen DOM gate).
     */
    enum class CoverFit { CONTAIN, COVER }

    data class CoverSpec(val width: Int?, val height: Int, val fit: CoverFit)

    fun coverSpec(grammar: String, mode: String, regionWidth: Int, regionHeight: Int): CoverSpec? {
        val g = grammar.removePrefix("g")
        if (mode == "spine" || mode == "text" || mode == "tile-text" || mode == "sleeve-empty") return null
        if (regionHeight < 65) return CoverSpec(36, 36, CoverFit.CONTAIN)
        return when {
            g == "11" && mode == "tile-art" -> CoverSpec(null, 60, CoverFit.COVER)
            g == "24" && mode == "tile-art" -> CoverSpec(50, 50, CoverFit.CONTAIN)
            g == "20" && mode == "sleeve" -> CoverSpec(120, 120, CoverFit.CONTAIN)
            g == "24" && mode == "pulse" -> CoverSpec(57, 57, CoverFit.CONTAIN)
            mode == "inline" && g == "23" -> CoverSpec(37, 37, CoverFit.CONTAIN)
            mode == "inline" -> CoverSpec(39, 39, CoverFit.CONTAIN)
            else -> CoverSpec(40, 40, CoverFit.CONTAIN)
        }
    }

    /**
     * Exact frozen band layout per (grammar, mode): flex `column` only for
     * the tile modes (`.g11 .music{flex-direction:column}`,
     * `.g24 .music-tile-art,.g24 .music-tile-text{...column...}`); every
     * other band is a row. `gap`/`padTop`/`padSide`/`padBottom` are the
     * exact paddings (base `.music{padding:7px 4px}` with per-mode
     * overrides; g11 tile keeps the V3 `padding:12px` sides with the
     * tile `padding-top:10px`).
     *
     * `verticalText` is true only for the spine: `.g6
     * .music-spine .music-copy{writing-mode:vertical-rl}` (label 21px
     * nowrap). Pulse has no vertical rule anywhere and stays horizontal
     * (interface R2 corrected accordingly).
     */
    data class BandLayout(
        val column: Boolean,
        val gap: Int,
        val padTop: Int,
        val padSide: Int,
        val padBottom: Int,
        val verticalText: Boolean,
    )

    fun bandLayout(grammar: String, mode: String): BandLayout {        val g = grammar.removePrefix("g")
        return when {
            mode == "spine" -> BandLayout(
                column = false, gap = 10, padTop = 8, padSide = 3, padBottom = 8,
                verticalText = true,
            )
            g == "11" && mode == "tile-art" -> BandLayout(
                column = true, gap = 9, padTop = 10, padSide = 12, padBottom = 12,
                verticalText = false,
            )
            g == "24" && (mode == "tile-art" || mode == "tile-text") -> BandLayout(
                column = true, gap = 5, padTop = 7, padSide = 7, padBottom = 7,
                verticalText = false,
            )
            mode == "sleeve" || mode == "sleeve-empty" -> BandLayout(
                column = false, gap = 14, padTop = 9, padSide = 0, padBottom = 9,
                verticalText = false,
            )
            mode == "pulse" -> BandLayout(
                column = false, gap = 10, padTop = 9, padSide = 9, padBottom = 9,
                verticalText = false,
            )
            mode == "inline" -> BandLayout(
                column = false, gap = 8, padTop = 4, padSide = 0, padBottom = 4,
                verticalText = false,
            )
            mode == "thumbnail" && g == "6" -> BandLayout(
                column = false, gap = 9, padTop = 5, padSide = 8, padBottom = 5,
                verticalText = false,
            )
            else -> BandLayout(
                column = false, gap = 9, padTop = 7, padSide = 4, padBottom = 7,
                verticalText = false,
            )
        }
    }

    /**
     * Exact frozen `round` photo radii in px, ordered
     * [topLeft, topRight, bottomRight, bottomLeft] × [x, y]:
     * g11 interlock uses the asymmetric
     * `.g11 .shape-round{border-radius:4px 26px 4px 4px}` (V3 override,
     * still final — no V4 rule supersedes it); every other grammar uses
     * the base `.shape-round{border-radius:12%}` (== `crop.js`
     * `maskPoint` r=.12, so the clip always matches the safety mask).
     */
    fun roundRadiiPx(grammar: String, width: Int, height: Int): FloatArray {
        if (grammar.removePrefix("g") == "11") {
            return floatArrayOf(4f, 4f, 26f, 26f, 4f, 4f, 4f, 4f)
        }
        val rx = ROUND_CORNER_FRACTION.toFloat() * width
        val ry = ROUND_CORNER_FRACTION.toFloat() * height
        return floatArrayOf(rx, ry, rx, ry, rx, ry, rx, ry)
    }

    /** Exact frozen print-plane shadow (`styles.css` `.styled .print-plane`). */
    const val DECOR_SHADOW_COLOR = 0x30151f23
    const val DECOR_SHADOW_RADIUS_PX = 8f
    const val DECOR_SHADOW_DX_PX = 2f
    const val DECOR_SHADOW_DY_PX = 4f

    /**
     * Print-plane shadow switch: shadowed everywhere EXCEPT g25 on an
     * `edge`-surface style (`.styled.g25.surface-edge
     * .print-plane{box-shadow:none;border:1px solid var(--s-edge)}`),
     * which renders the 1px edge border instead.
     */
    fun printPlaneShadow(grammar: String, surfaceMode: String): Boolean =
        !(grammar.removePrefix("g") == "25" && surfaceMode == "edge")

    /**
     * Carrier border width: 2px on `edge`-surface styles
     * (`.styled.surface-edge .carrier{...border-width:2px}`), else 1px.
     * (Fill/border colors stay the R1 surface/edge roles; the V3
     * hardcoded `#fff9e6`/`#f3f6e8` carrier rule is superseded by the
     * `styles.css` palette-roles section on the styled path, same
     * doctrine as R1.)
     */
    fun carrierBorderWidthPx(surfaceMode: String): Int =
        if (surfaceMode == "edge") 2 else 1

    /**
     * Exact cover+text subdivision of a sealed music region for a verified
     * cover mode, shared by prepare (text measurement) and Canvas (pixels)
     * so both use one frozen-derived geometry. Row bands put the cover at
     * the leading edge, vertically centered (`align-items:center`); column
     * bands (tile modes) put it at the top, left-aligned
     * (`justify-content:flex-start`, `align-items:start`). The copy takes
     * the remainder after cover + gap. Returns null when the remainder
     * collapses (prepare rejects fail-closed; frozen would flag overflow).
     */
    data class CoverPlacement(
        val cover: CollageRenderer.RectI,
        val text: CollageRenderer.RectI,
        val fit: CoverFit,
    )

    fun placeCover(
        regionX: Int,
        regionY: Int,
        regionWidth: Int,
        regionHeight: Int,
        grammar: String,
        mode: String,
    ): CoverPlacement? {
        val spec = coverSpec(grammar, mode, regionWidth, regionHeight) ?: return null
        val layout = bandLayout(grammar, mode)
        return if (layout.column) {
            val coverW = spec.width ?: regionWidth
            val coverX = regionX
            val coverY = regionY + layout.padTop
            val textY = coverY + spec.height + layout.gap
            val textH = regionY + regionHeight - layout.padBottom - textY
            if (coverW < 1 || textH < 1) return null
            CoverPlacement(
                cover = CollageRenderer.RectI(coverX, coverY, coverW, spec.height),
                text = CollageRenderer.RectI(
                    regionX + layout.padSide, textY,
                    regionWidth - 2 * layout.padSide, textH,
                ),
                fit = spec.fit,
            )
        } else {
            val coverW = spec.width ?: return null
            val coverX = regionX
            val coverY = regionY + (regionHeight - spec.height) / 2
            val textX = coverX + coverW + layout.gap
            val textW = regionX + regionWidth - textX
            val textH = regionHeight - layout.padTop - layout.padBottom
            if (textW < 1 || textH < 1) return null
            CoverPlacement(
                cover = CollageRenderer.RectI(coverX, coverY, coverW, spec.height),
                text = CollageRenderer.RectI(
                    textX, regionY + layout.padTop, textW, textH,
                ),
                fit = spec.fit,
            )
        }
    }

    /**
     * Letter-spacing cascade (archive-verified, read before changing):
     * `v4.css` sets `.provenance{-.04em}`, `.provenance-sender{-.025em}`
     * plus per-grammar sender/brand overrides — BUT on the styled product
     * path `expression.js:33` seals `letterSpacing = brand ? style.tracking
     * : 0` onto the plan mark and `:76` applies it INLINE, which overrides
     * every stylesheet rule. The CSS values govern ONLY the unstyled
     * baseline/control viewer (styleId `v4`), which the product never
     * seals (StyleRegistry excludes it; prepare/admission reject it).
     * Effective product rule, implemented in prepare: brand =
     * style.tracking, sender = 0. Reference CSS values: sender base
     * -.025em (g21 -.045em, g22 -.04em, g26 -.035em); brand base -.04em
     * (g16 -.065em, g17 -.035em, g19 -.07em; g16/g19 spliced inactive).
     */
    const val SENDER_CSS_TRACKING_EM = -0.025
}
