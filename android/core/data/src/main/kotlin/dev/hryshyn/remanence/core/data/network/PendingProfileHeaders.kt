package dev.hryshyn.remanence.core.data.network

import okhttp3.Request

/**
 * T186 M7c: capability profile-version header (`X-Remanence-Profile-Version`).
 *
 * Central emission point for every auth transport (password, Google,
 * passkey recovery). `core:data` cannot read the app build flag, so the
 * application container sets [profileVersion] ONCE at startup — and ONLY
 * for pending-enabled builds. Ordinary beta/default builds never set it,
 * so the header is never sent there. Unit tests set/reset it explicitly.
 */
object PendingProfileHeaders {
    const val HEADER: String = "X-Remanence-Profile-Version"

    /** Frozen v2 value negotiating nullable user summaries (T185 §2). */
    const val VERSION_2: String = "2"

    @Volatile
    var profileVersion: String? = null
        private set

    /** Called once by the application container for pending builds only. */
    fun installForPendingBuilds() {
        profileVersion = VERSION_2
    }

    fun apply(builder: Request.Builder): Request.Builder {
        val version = profileVersion
        if (!version.isNullOrBlank()) builder.header(HEADER, version)
        return builder
    }

    /** Test-only reset (production never clears after install). */
    internal fun resetForTests() {
        profileVersion = null
    }
}
