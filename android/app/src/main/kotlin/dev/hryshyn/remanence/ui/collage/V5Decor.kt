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
}
