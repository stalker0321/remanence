package dev.hryshyn.remanence.ui.collage

import androidx.annotation.FontRes
import dev.hryshyn.remanence.R

/**
 * Deterministic V5 collage font registry.
 *
 * Maps every face the capsule-engine V5 freeze ships (authoritative `V5
 * contract` section 8: nine OTFs + `LICENSE.txt`) onto the exact Nimbus/URW
 * assets copied from the freeze `runtime/assets/fonts/` into `res/font/`.
 * The freeze `LICENSE.txt` ships at `assets/fonts/LICENSE.txt`.
 *
 * | family     | faces                | freeze file                 |
 * | ---        | ---                  | ---                         |
 * | StudySans  | 400, 700             | NimbusSans-Regular.otf, NimbusSans-Bold.otf |
 * | StudySerif | 400, 400-italic, 700 | NimbusRoman-Regular.otf, NimbusRoman-Italic.otf, NimbusRoman-Bold.otf |
 * | StudyMono  | 400                  | NimbusMonoPS-Regular.otf    |
 * | Narrow     | 700                  | NimbusSansNarrow-Bold.otf   |
 * | Gothic     | 400, 700             | URWGothic-Book.otf, URWGothic-Demi.otf |
 *
 * Lookup is fail-closed against the section 8 face-availability matrix: an
 * italic face must be `StudySerif` @400; `Narrow` is 700-only; `StudyMono` is
 * 400 and non-italic; `StudySans`/`StudySerif`/`Gothic` are 400 or 700,
 * non-italic. Anything else returns `null`, so a receiver never falls back to
 * `hold_sans` or silently substitutes a face.
 */
object FontRegistry {

    /** Registry contract version (prior contract section 8: stays 1 in slice 1). */
    const val VERSION = 1

    /** Logical face within a bundled font family. */
    data class Face(val family: String, val weight: Int, val italic: Boolean)

    /** One bundled face and the exact `res/font` asset that backs it. */
    data class BundledFace(
        val family: String,
        val weight: Int,
        val italic: Boolean,
        @FontRes val resourceId: Int,
        val assetFile: String,
    ) {
        val face: Face get() = Face(family, weight, italic)
    }

    /** The nine bundled faces, no more and no less (section 8 inventory). */
    val bundledFaces: List<BundledFace> = listOf(
        BundledFace("StudySans", 400, false, R.font.nimbus_sans_regular, "nimbus_sans_regular.otf"),
        BundledFace("StudySans", 700, false, R.font.nimbus_sans_bold, "nimbus_sans_bold.otf"),
        BundledFace("StudySerif", 400, false, R.font.nimbus_roman_regular, "nimbus_roman_regular.otf"),
        BundledFace("StudySerif", 400, true, R.font.nimbus_roman_italic, "nimbus_roman_italic.otf"),
        BundledFace("StudySerif", 700, false, R.font.nimbus_roman_bold, "nimbus_roman_bold.otf"),
        BundledFace("StudyMono", 400, false, R.font.nimbus_mono_ps_regular, "nimbus_mono_ps_regular.otf"),
        BundledFace("Narrow", 700, false, R.font.nimbus_sans_narrow_bold, "nimbus_sans_narrow_bold.otf"),
        BundledFace("Gothic", 400, false, R.font.urw_gothic_book, "urw_gothic_book.otf"),
        BundledFace("Gothic", 700, false, R.font.urw_gothic_demi, "urw_gothic_demi.otf"),
    )

    private val byFace: Map<Face, BundledFace> = bundledFaces.associateBy { it.face }

    /**
     * Fail-closed lookup: the `res/font` id for a bundled face, or `null` when
     * the face is not one of the nine shipped faces.
     */
    @FontRes
    fun resourceFor(family: String, weight: Int, italic: Boolean): Int? =
        byFace[Face(family, weight, italic)]?.resourceId

    /** Full provenance for a bundled face, or `null` when it is not shipped. */
    fun bundledFaceFor(family: String, weight: Int, italic: Boolean): BundledFace? =
        byFace[Face(family, weight, italic)]
}
