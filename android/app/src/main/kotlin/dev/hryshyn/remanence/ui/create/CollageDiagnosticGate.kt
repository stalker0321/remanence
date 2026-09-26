package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.ui.collage.AndroidCollageCropSource

/**
 * Diagnostic-only gate for the non-production collage analysis mount. The
 * factory builds an [AndroidCollageCropSource] bound to the session holder
 * (test/probe-provided runners; never production), or returns `null` to keep
 * the chooser absent. `CreateViewModel` takes this as an optional
 * constructor parameter defaulting to `null`: the default release gate OFF
 * constructs/mounts nothing, exposes no chooser, and populates no choices.
 * Gate ON is only host-testable diagnostic context.
 */
fun interface CollageDiagnosticGate {
    fun create(holder: RetainedOriginalHolder): AndroidCollageCropSource?
}
