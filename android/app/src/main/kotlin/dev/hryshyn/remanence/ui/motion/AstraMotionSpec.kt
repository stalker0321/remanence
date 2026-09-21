package dev.hryshyn.remanence.ui.motion

/**
 * Astra fidelity motion tokens for scan/capture/result surfaces.
 *
 * Authoritative source: the design study first implementation —
 * `/home/vodkolyan/Remanence-design/design/motion/motion-original.mjs`
 * with the accepted continuous-Carry revision (`motion.mjs`,
 * `design/research/motion.md`, `design/motion/lab.mjs`).
 *
 * This object is pure Kotlin (no Compose dependency) so the token values
 * and the reduced-motion policy are pinned by plain JVM unit tests.
 *
 * Deliberately OUT of scope here, exactly as in the study:
 * service waits (held / 700 ms / 3000 ms in `motion-original.mjs:16-19`)
 * are not motion and are never derived from these tokens.
 */
object AstraMotionSpec {

    /** Routine context change, continuous Carry (`motion.mjs`, `model.mjs:7`). */
    const val ROUTINE_CONTEXT_MS = 340

    /** Image handoff range, continuous Carry (`model.mjs:7`). */
    const val IMAGE_TRAVEL_MIN_MS = 420

    /**
     * Image handoff used by Compose presence transitions: the midpoint of
     * the authored 420–480 ms range. The range bounds stay pinned by tests.
     */
    const val IMAGE_TRAVEL_MS = 450

    /** Image handoff range upper bound. */
    const val IMAGE_TRAVEL_MAX_MS = 480

    /** Physical-to-digital opening, one overlapping movement (`motion.mjs:92`). */
    const val OPENING_MS = 680

    /**
     * Reduced-motion replacement: a single short crossfade, no travel, no
     * scaling, no animated wait loops (`motion-original.mjs:57`,
     * `research/motion.md:57`).
     */
    const val REDUCED_CROSSFADE_MS = 80

    /** `gentle` default easing (`motion-original.mjs:2`). cubic-bezier(.2,.75,.25,1). */
    val EASING_GENTLE = floatArrayOf(0.2f, 0.75f, 0.25f, 1.0f)

    /** Continuous-Carry travel easing (`motion.mjs:5`). cubic-bezier(.22,.64,.2,1). */
    val EASING_TRAVEL = floatArrayOf(0.22f, 0.64f, 0.2f, 1.0f)

    /** Continuous-Carry dissolve easing (`motion.mjs:6`). cubic-bezier(.32,0,.28,1). */
    val EASING_DISSOLVE = floatArrayOf(0.32f, 0.0f, 0.28f, 1.0f)

    /**
     * Focus selection depth: meaningful selections scale .987 -> 1
     * (`motion-original.mjs:72-73`).
     */
    const val FOCUS_SELECT_SCALE_FROM = 0.987f

    /**
     * Resolved motion regime.
     *
     * FULL: authored durations and easings. REDUCED_SHORT: the 80 ms
     * crossfade with no travel/scale/pulse. INSTANT: snap, zero duration —
     * for animator-duration-scale 0, where even the crossfade is skipped.
     */
    enum class Resolved {
        FULL,
        REDUCED_SHORT,
        INSTANT,
    }

    /**
     * Maps the system animator-duration scale to a regime.
     *
     * The platform "Remove animations" accessibility setting drives the
     * animator scale to 0, so scale 0 is the system reduced-motion signal
     * that resolves to INSTANT; a shortened (0, 1) scale resolves to the
     * short 80 ms replacement; 1+ is full authored motion.
     */
    fun resolve(animatorDurationScale: Float): Resolved = when {
        animatorDurationScale <= 0f -> Resolved.INSTANT
        animatorDurationScale < 1f -> Resolved.REDUCED_SHORT
        else -> Resolved.FULL
    }

    /** Routine state-transition duration for a regime. */
    fun routineMs(resolved: Resolved): Int = when (resolved) {
        Resolved.FULL -> ROUTINE_CONTEXT_MS
        Resolved.REDUCED_SHORT -> REDUCED_CROSSFADE_MS
        Resolved.INSTANT -> 0
    }

    /** Postcard/image presence duration for a regime. */
    fun imageTravelMs(resolved: Resolved): Int = when (resolved) {
        Resolved.FULL -> IMAGE_TRAVEL_MS
        Resolved.REDUCED_SHORT -> REDUCED_CROSSFADE_MS
        Resolved.INSTANT -> 0
    }

    /** Opening duration for a regime. */
    fun openingMs(resolved: Resolved): Int = when (resolved) {
        Resolved.FULL -> OPENING_MS
        Resolved.REDUCED_SHORT -> REDUCED_CROSSFADE_MS
        Resolved.INSTANT -> 0
    }
}
