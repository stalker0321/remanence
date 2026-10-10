package dev.hryshyn.remanence.recovery

import android.content.Context

/** Non-secret, owner+bundle-bound receipt written ONLY after actual recovery unwrap. */
class RecoveryReadinessStore(context: Context) {
    private val preferences = context.getSharedPreferences("recovery_readiness_v1", Context.MODE_PRIVATE)
    fun isReady(account: String, bundle: String): Boolean =
        preferences.getString(account, null) == bundle
    fun markVerified(account: String, bundle: String) {
        check(preferences.edit().putString(account, bundle).commit()) { "recovery receipt not persisted" }
    }
}
