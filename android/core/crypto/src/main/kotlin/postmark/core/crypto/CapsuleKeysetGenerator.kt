package postmark.core.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle

/**
 * Creates the fresh per-capsule AEAD keyset (docs/security.md section 6.1):
 * a brand-new `AES256_GCM` keyset whose primary key uses the `TINK` output
 * prefix — never `RAW`, `CRUNCHY`, or `LEGACY`. The serialized keyset is the
 * conceptual random capsule key and is never derived from any user input.
 */
class CapsuleKeysetGenerator {

    fun generate(): KeysetHandle =
        KeysetHandle.generateNew(KeyTemplates.get(AES256_GCM_TEMPLATE))

    companion object {
        const val AES256_GCM_TEMPLATE: String = "AES256_GCM"
    }
}
