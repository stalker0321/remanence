package dev.hryshyn.remanence.core.model

/**
 * The recipient binding used by the M2 capsule protocol.  Pending-email
 * targets are deliberately not represented until a later protocol version
 * defines their cryptographic binding.
 */
sealed interface RecipientTarget {
    data class ExistingUser(
        val userId: UserId,
        val keyBundleId: KeyBundleId,
    ) : RecipientTarget
}
