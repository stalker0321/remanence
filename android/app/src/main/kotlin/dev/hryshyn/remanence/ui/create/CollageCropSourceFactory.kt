package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.ui.collage.AndroidCollageCropSource

/**
 * Production factory for the session crop source. Builds an
 * [AndroidCollageCropSource] bound to the session holder (production runners
 * supplied by the factory implementation), or returns `null` to keep the
 * chooser absent. Never a diagnostic demo path: this is the single
 * production mount point.
 */
fun interface CollageCropSourceFactory {
    fun create(holder: RetainedOriginalHolder): AndroidCollageCropSource?
}
