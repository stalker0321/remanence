package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.BuildConfig

/**
 * T186: compile-time + runtime gate for the experimental pending-recipient
 * journey (T184 contract).
 *
 * - Default OFF in every variant; only debug honours
 *   `-Premanence.pending.enabled=true` (see app/build.gradle.kts).
 * - Release and beta hard-wire `REMANENCE_PENDING_ENABLED=false`.
 * - The pending-enabled build uses the isolated `.pendingtest` package and
 *   the `Remanence Pending Test` label; it must never talk to the live beta
 *   backend (`/onboarding-test/` on `remanence.hryshyn.dev`).
 *
 * All pending screens, outbox work, and sender-route branches must check
 * [isEnabled] first and render/act as if the feature does not exist when
 * false. Existing beta/Google/PRF/crypto gates are unchanged.
 */
object PendingFeatureGate {

    /** Live beta host that a pending-enabled build must never contact. */
    const val LIVE_BETA_HOST = "remanence.hryshyn.dev"

    /** Live beta path prefix that a pending-enabled build must never use. */
    const val LIVE_BETA_PATH_PREFIX = "/onboarding-test/"

    fun isEnabled(): Boolean = try {
        BuildConfig.REMANENCE_PENDING_ENABLED
    } catch (_: Throwable) {
        false
    }

    /**
     * Fail-closed isolated-API validation for a pending-enabled build.
     * Returns null when the base URL is acceptable for experimental use,
     * otherwise a generic (non-secret) reason. Never throws for logging.
     *
     * Rejects: the live beta host, the live `/onboarding-test/` prefix,
     * non-HTTP(S) schemes are left to [ApiBaseUrl] parsing; blank input.
     */
    fun isolatedApiRejection(rawBaseUrl: String): String? {
        val raw = rawBaseUrl.trim()
        if (raw.isEmpty()) return "unconfigured"
        val lower = raw.lowercase()
        if (LIVE_BETA_HOST in lower) return "reserved-backend"
        // Compare path portion only; query/fragment are rejected elsewhere.
        val pathStart = lower.indexOf('/', lower.indexOf("://") + 3)
        val path = if (pathStart >= 0) lower.substring(pathStart) else "/"
        if (path.startsWith(LIVE_BETA_PATH_PREFIX.trimEnd('/')) ||
            path.startsWith(LIVE_BETA_PATH_PREFIX)
        ) {
            return "reserved-backend"
        }
        return null
    }

    /** True when [rawBaseUrl] may be used by a pending-enabled build. */
    fun isIsolatedApiAccepted(rawBaseUrl: String): Boolean =
        isolatedApiRejection(rawBaseUrl) == null
}
