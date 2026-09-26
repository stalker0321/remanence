package dev.hryshyn.remanence.ui.collage

import androidx.annotation.FontRes
import dev.hryshyn.remanence.R

/**
 * Deterministic V5 collage font registry.
 *
 * Maps only the six faces the bundled styles actually use (capsule-engine V5
 * freeze `remanence_capsule_engine_v5_freeze_2026-09-25`, `runtime/styles.js`,
 * grammar group 5) onto the exact Nimbus OTFs copied from the freeze's
 * `runtime/assets/fonts/` into `res/font/`. The freeze `LICENSE.txt` ships at
 * `assets/fonts/LICENSE.txt`.
 *
 * | face                              | freeze file                 |
 * | ---                               | ---                         |
 * | StudySans  400 normal             | NimbusSans-Regular.otf      |
 * | StudySans  700 normal             | NimbusSans-Bold.otf         |
 * | StudySerif 400 normal             | NimbusRoman-Regular.otf     |
 * | StudySerif 400 italic             | NimbusRoman-Italic.otf      |
 * | StudyMono  400 normal             | NimbusMonoPS-Regular.otf    |
 * | Narrow     700 normal             | NimbusSansNarrow-Bold.otf   |
 *
 * Lookup is fail-closed: any face the freeze does not ship (Gothic, Nimbus
 * Roman Bold, StudySerif/StudyMono 700, Narrow 400, non-Serif italic) returns
 * `null`, so a receiver never falls back to `hold_sans`.
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

    /** The six bundled faces, no more and no less. */
    val bundledFaces: List<BundledFace> = listOf(
        BundledFace("StudySans", 400, false, R.font.nimbus_sans_regular, "nimbus_sans_regular.otf"),
        BundledFace("StudySans", 700, false, R.font.nimbus_sans_bold, "nimbus_sans_bold.otf"),
        BundledFace("StudySerif", 400, false, R.font.nimbus_roman_regular, "nimbus_roman_regular.otf"),
        BundledFace("StudySerif", 400, true, R.font.nimbus_roman_italic, "nimbus_roman_italic.otf"),
        BundledFace("StudyMono", 400, false, R.font.nimbus_mono_ps_regular, "nimbus_mono_ps_regular.otf"),
        BundledFace("Narrow", 700, false, R.font.nimbus_sans_narrow_bold, "nimbus_sans_narrow_bold.otf"),
    )

    private val byFace: Map<Face, BundledFace> = bundledFaces.associateBy { it.face }

    /**
     * Fail-closed lookup: the `res/font` id for a bundled face, or `null` when
     * the face is not one of the six shipped faces.
     */
    @FontRes
    fun resourceFor(family: String, weight: Int, italic: Boolean): Int? =
        byFace[Face(family, weight, italic)]?.resourceId

    /** Full provenance for a bundled face, or `null` when it is not shipped. */
    fun bundledFaceFor(family: String, weight: Int, italic: Boolean): BundledFace? =
        byFace[Face(family, weight, italic)]
}
