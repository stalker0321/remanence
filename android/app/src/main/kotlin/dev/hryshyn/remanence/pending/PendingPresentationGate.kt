package dev.hryshyn.remanence.pending

/**
 * T186: presentation gate for retirement enqueue (T184 owner override).
 *
 * Retirement is created ONLY after genuine supported presentation: the live
 * memory-only grant still resolves for the current owner AND the capsule
 * route reached [Ready] with a supported (non-Unsupported) presentation.
 * Lookup / proof / claim / download / admission alone never enqueues.
 * Unsupported content never enqueues: there is nothing to complete.
 */
object PendingPresentationGate {

    enum class PresentationKind { SUPPORTED, UNSUPPORTED }

    /**
     * @param grantLive true only when the authority still resolves the exact
     * grant id for the current owner (grant + account/session fenced).
     * @param routeReady true only when the route published Ready.
     */
    fun shouldEnqueueRetirement(
        featureEnabled: Boolean,
        grantLive: Boolean,
        routeReady: Boolean,
        kind: PresentationKind,
    ): Boolean = featureEnabled && grantLive && routeReady && kind == PresentationKind.SUPPORTED
}
