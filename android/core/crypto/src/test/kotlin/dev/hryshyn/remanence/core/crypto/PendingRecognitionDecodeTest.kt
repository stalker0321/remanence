package dev.hryshyn.remanence.core.crypto

import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.protocol.v1.ChooserHint
import dev.hryshyn.remanence.protocol.v1.RecognitionManifest
import java.security.GeneralSecurityException
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * T186 M14d-scan: pending recognition decode enforces the exact v1
 * framed-plaintext guards (version, capsule, required canonical hint,
 * timestamp, place bounds, fingerprint bounds, canonical re-encode).
 * Anything else fails closed — never a partial fingerprint.
 */
class PendingRecognitionDecodeTest {

    private val capsule = "11111111-2222-3333-4444-555555555555"

    private fun uuidBytes(raw: String): ByteArray {
        val uuid = UUID.fromString(raw)
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private val front = ByteArray(64) { it.toByte() }

    private fun validPlaintext(): ByteArray =
        RecognitionManifestCodec().buildPlaintext(
            CapsuleId(UUID.fromString(capsule)),
            "mykola",
            1_798_765_432L,
            "home",
            front,
        )

    private fun rawManifest(
        version: Int = RecognitionManifestCodec.FORMAT_VERSION,
        capsuleId: ByteArray = uuidBytes(capsule),
        hint: ChooserHint? = ChooserHint.newBuilder()
            .setSenderHandleSnapshot("mykola")
            .setCreatedAtEpochSeconds(1_798_765_432L)
            .setPlaceLabel("home")
            .build(),
        frontBytes: ByteArray = front,
    ): ByteArray {
        val builder = RecognitionManifest.newBuilder()
            .setManifestVersion(version)
            .setCapsuleId(ByteString.copyFrom(capsuleId))
            .setFrontFingerprint(ByteString.copyFrom(frontBytes))
        if (hint != null) builder.setChooserHint(hint)
        return builder.build().toByteArray()
    }

    private fun assertRejected(plaintext: ByteArray) {
        try {
            PendingRecognitionDecode.frontFingerprint(plaintext, uuidBytes(capsule))
            fail("expected GeneralSecurityException")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun validManifestDecodesFingerprintCopy() {
        val decoded = PendingRecognitionDecode.frontFingerprint(validPlaintext(), uuidBytes(capsule))
        assertArrayEquals(front, decoded)
    }

    @Test
    fun malformedManifestsRejected() {
        // Not a proto at all.
        assertRejected(ByteArray(10) { it.toByte() })
        // Wrong version.
        assertRejected(rawManifest(version = 99))
        // Wrong capsule.
        assertRejected(
            rawManifest(capsuleId = uuidBytes("99999999-2222-3333-4444-555555555555")),
        )
        // Missing chooser hint.
        assertRejected(rawManifest(hint = null))
        // Non-canonical sender handle.
        assertRejected(
            rawManifest(
                hint = ChooserHint.newBuilder()
                    .setSenderHandleSnapshot("Mykola!")
                    .setCreatedAtEpochSeconds(1_798_765_432L)
                    .build(),
            ),
        )
        // Negative created instant.
        assertRejected(
            rawManifest(
                hint = ChooserHint.newBuilder()
                    .setSenderHandleSnapshot("mykola")
                    .setCreatedAtEpochSeconds(-5L)
                    .build(),
            ),
        )
        // Empty place label.
        assertRejected(
            rawManifest(
                hint = ChooserHint.newBuilder()
                    .setSenderHandleSnapshot("mykola")
                    .setCreatedAtEpochSeconds(1_798_765_432L)
                    .setPlaceLabel("")
                    .build(),
            ),
        )
        // Overlong place label.
        assertRejected(
            rawManifest(
                hint = ChooserHint.newBuilder()
                    .setSenderHandleSnapshot("mykola")
                    .setCreatedAtEpochSeconds(1_798_765_432L)
                    .setPlaceLabel("x".repeat(121))
                    .build(),
            ),
        )
        // Empty fingerprint.
        assertRejected(rawManifest(frontBytes = ByteArray(0)))
        // Oversize fingerprint.
        assertRejected(rawManifest(frontBytes = ByteArray(1_024 * 1_024 + 1)))
        // Non-canonical encoding: valid fields plus an unknown field.
        // The re-encode guard must reject the trailing bytes.
        val canonical = validPlaintext()
        val nonCanonical = canonical + byteArrayOf(0x98.toByte(), 0x06, 0x01)
        assertRejected(nonCanonical)
    }
}
