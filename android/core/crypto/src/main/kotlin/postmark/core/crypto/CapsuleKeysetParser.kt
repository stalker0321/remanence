package postmark.core.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.proto.AesGcmKey
import com.google.crypto.tink.proto.KeyData.KeyMaterialType
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import com.google.crypto.tink.proto.OutputPrefixType
import java.security.GeneralSecurityException

/**
 * Parses the serialized capsule AEAD keyset carried inside an opened recipient
 * envelope (docs/security.md section 6.1). Protocol v1 admits exactly one
 * shape: a Tink keyset whose keys are `AES256_GCM` symmetric keys with 32-byte
 * key material, `TINK` output prefix, and `ENABLED` status — never `RAW`,
 * `CRUNCHY`, `LEGACY`, another algorithm, or an unusable keyset. Any deviation
 * fails closed before the handle is handed to artifact decryption.
 */
class CapsuleKeysetParser {

    fun parseExactAes256GcmTink(serializedKeyset: ByteArray): KeysetHandle {
        TinkPrimitives.ensureRegistered()
        if (serializedKeyset.isEmpty()) {
            throw GeneralSecurityException("capsule keyset is empty")
        }
        val keyset = try {
            Keyset.parseFrom(serializedKeyset)
        } catch (_: Exception) {
            throw GeneralSecurityException("capsule keyset is not a parseable Tink keyset")
        }
        if (keyset.keyCount == 0 || keyset.primaryKeyId == 0) {
            throw GeneralSecurityException("capsule keyset has no usable primary key")
        }
        keyset.keyList.forEach { key ->
            if (key.keyData.typeUrl != AES256_GCM_TYPE_URL ||
                key.keyData.keyMaterialType != KeyMaterialType.SYMMETRIC ||
                key.outputPrefixType != OutputPrefixType.TINK ||
                key.status != KeyStatusType.ENABLED
            ) {
                throw GeneralSecurityException("capsule keyset key is not AES256_GCM/TINK/ENABLED")
            }
            val aesGcmKey = try {
                AesGcmKey.parseFrom(key.keyData.value)
            } catch (_: Exception) {
                throw GeneralSecurityException("capsule keyset key material is not AES-GCM")
            }
            if (aesGcmKey.keyValue.size() != AES_256_KEY_BYTES) {
                throw GeneralSecurityException("capsule keyset key is not AES-256")
            }
        }
        val handle = try {
            TinkProtoKeysetFormat.parseKeyset(serializedKeyset, InsecureSecretKeyAccess.get())
        } catch (_: Exception) {
            throw GeneralSecurityException("capsule keyset is not a registered Tink AEAD keyset")
        }
        return try {
            handle.getPrimitive(Aead::class.java)
            handle
        } catch (_: Exception) {
            throw GeneralSecurityException("capsule keyset cannot produce an AEAD primitive")
        }
    }

    private companion object {
        const val AES256_GCM_TYPE_URL = "type.googleapis.com/google.crypto.tink.AesGcmKey"
        const val AES_256_KEY_BYTES = 32
    }
}
