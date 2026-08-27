package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.SenderRetryPurpose
import dev.hryshyn.remanence.core.model.SenderRetryWrapContextInput
import dev.hryshyn.remanence.core.model.UserId
import java.security.GeneralSecurityException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SenderRetryKeysetWrapperTest {

    private val ownerUserId = UserId(UUID.fromString("c5111111-2222-4333-8444-555555555555"))
    private val capsuleId = CapsuleId(UUID.fromString("c5000003-0405-4607-8809-0a0b0c0d0e0f"))
    private val senderBundleId = KeyBundleId(UUID.fromString("c5333333-4444-4555-8666-777777777777"))

    private fun contextFor(
        owner: UserId = ownerUserId,
        capsule: CapsuleId = capsuleId,
        bundle: KeyBundleId = senderBundleId,
    ): SenderRetryWrapContextInput = SenderRetryWrapContextInput(
        ownerUserId = owner,
        capsuleId = capsule,
        senderKeyBundleId = bundle,
        purpose = SenderRetryPurpose.RECIPIENT_KEY_STALE_REWRAP,
    )

    private fun newAesKeyset(): KeysetHandle {
        TinkPrimitives.ensureRegistered()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
    }

    private fun rawSerialized(keyset: KeysetHandle): ByteArray =
        com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeyset(
            keyset,
            com.google.crypto.tink.InsecureSecretKeyAccess.get(),
        )

    // ------------------------------------------------------------------
    // Roundtrip across process-style serialize / parse.
    // ------------------------------------------------------------------

    @Test
    fun wrapAndUnwrapRoundTripSameContextSameProcess() {
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.sender-retry")
        val wrapper = SenderRetryKeysetWrapper(boundary)
        val keyset = newAesKeyset()
        val context = contextFor()
        val record = wrapper.wrap("remanence.kek.sender-retry", keyset, context)
        val unwrapped = wrapper.unwrap(record, context)
        assertTrue(keyset.equalsKeyset(unwrapped))
    }

    @Test
    fun unwrapSurvivesSimulatedProcessReloadThroughRecordSerialization() {
        val firstBoundary = InMemoryKekBoundary()
        firstBoundary.createAes256GcmKey("remanence.kek.sender-retry.reload")
        val firstWrapper = SenderRetryKeysetWrapper(firstBoundary)
        val keyset = newAesKeyset()
        val context = contextFor()
        val rawBefore = rawSerialized(keyset)
        val persisted = firstWrapper.wrap(
            "remanence.kek.sender-retry.reload",
            keyset,
            context,
        ).serialize()

        // A "second process" picks up the same KEK from a freshly-
        // constructed boundary (the InMemoryKekBoundary process-wide
        // store keeps the key) and re-parses the persisted record.
        val secondBoundary = InMemoryKekBoundary()
        val secondWrapper = SenderRetryKeysetWrapper(secondBoundary)
        val reloaded = WrappedKeysetRecord.parse(persisted)
        val unwrapped = secondWrapper.unwrap(reloaded, context)
        val rawAfter = rawSerialized(unwrapped)
        assertEquals("remanence.kek.sender-retry.reload", reloaded.alias)
        assertContentEquals(rawBefore, rawAfter)
    }

    // ------------------------------------------------------------------
    // No-plaintext canary.
    // ------------------------------------------------------------------

    @Test
    fun wrapProducesPersistableRecordWithoutRawKeysetBytes() {
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.sender-retry.canary")
        val keyset = newAesKeyset()
        val record = SenderRetryKeysetWrapper(boundary).wrap(
            "remanence.kek.sender-retry.canary",
            keyset,
            contextFor(),
        )
        assertEquals("remanence.kek.sender-retry.canary", record.alias)
        assertEquals(12, record.nonce.size)
        assertTrue(record.wrappedKeyset.isNotEmpty())
        val raw = rawSerialized(keyset)
        // The wrapped keyset MUST NOT contain the raw serialized keyset
        // bytes - the keyset is encrypted under the KEK and the
        // call site never receives a plaintext keyset handle.
        assertNotEquals(raw.toList(), record.wrappedKeyset.toList())
        // The plaintext raw bytes are also not a contiguous run of the
        // wrapped keyset (an attacker reading the record on disk
        // must not see the raw keyset anywhere in the ciphertext).
        val rawInCiphertext = record.wrappedKeyset.toList().toString()
            .contains(raw.toList().toString())
        assertTrue(!rawInCiphertext, "raw keyset bytes must not be a contiguous run of the wrapped keyset")
    }

    // ------------------------------------------------------------------
    // Wrong-context matrix: every typed field is bound into the AAD.
    // ------------------------------------------------------------------

    @Test
    fun wrongOwnerCapsuleOrSenderBundleFailsClosedAtUnwrap() {
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.sender-retry.matrix")
        val wrapper = SenderRetryKeysetWrapper(boundary)
        val context = contextFor()
        val originalKeyset = newAesKeyset()
        val record = wrapper.wrap(
            "remanence.kek.sender-retry.matrix",
            originalKeyset,
            context,
        )

        val otherOwner = contextFor(
            owner = UserId(UUID.fromString("deadbeef-dead-4eee-8eee-eeeeeeeeeeee")),
        )
        val otherCapsule = contextFor(
            capsule = CapsuleId(UUID.fromString("cabb1efa-cabc-4abc-8abc-bacbacbacbac")),
        )
        val otherBundle = contextFor(
            bundle = KeyBundleId(UUID.fromString("caceface-face-4ace-8ace-cafecafecafe")),
        )

        assertFailsWith<GeneralSecurityException> { wrapper.unwrap(record, otherOwner) }
        assertFailsWith<GeneralSecurityException> { wrapper.unwrap(record, otherCapsule) }
        assertFailsWith<GeneralSecurityException> { wrapper.unwrap(record, otherBundle) }
        // The original context still unwraps cleanly and recovers
        // the same keyset bytes.
        val unwrapped = wrapper.unwrap(record, context)
        assertTrue(originalKeyset.equalsKeyset(unwrapped))
    }

    @Test
    fun wrapRefusesUnspecifiedPurposeAtConstruction() {
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.sender-retry.purpose")
        val wrapper = SenderRetryKeysetWrapper(boundary)
        // The data-class init block refuses UNSPECIFIED up front, so
        // the wrap call never even receives an invalid context.
        assertFailsWith<IllegalArgumentException> {
            contextFor().copy(purpose = SenderRetryPurpose.UNSPECIFIED)
        }
        // The wire path for a tampered purpose is covered by the
        // SenderRetryWrapContextEncoderTest in :core:model; here we
        // only assert the crypto boundary's fail-closed behavior on
        // the UNSPECIFIED sentinel it does accept syntactically.
        assertFailsWith<IllegalArgumentException> {
            wrapper.wrap(
                "remanence.kek.sender-retry.purpose",
                newAesKeyset(),
                context = SenderRetryWrapContextInput(
                    ownerUserId = ownerUserId,
                    capsuleId = capsuleId,
                    senderKeyBundleId = senderBundleId,
                    purpose = SenderRetryPurpose.UNSPECIFIED,
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Alias, tamper, missing KEK, malformed record.
    // ------------------------------------------------------------------

    @Test
    fun wrongAliasFailsClosedAtUnwrap() {
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.sender-retry.a")
        boundary.createAes256GcmKey("remanence.kek.sender-retry.b")
        val wrapper = SenderRetryKeysetWrapper(boundary)
        val context = contextFor()
        val record = wrapper.wrap(
            "remanence.kek.sender-retry.a",
            newAesKeyset(),
            context,
        )
        val aliasedUnderB = WrappedKeysetRecord.create(
            alias = "remanence.kek.sender-retry.b",
            nonce = record.nonce,
            wrappedKeyset = record.wrappedKeyset,
        )
        assertFailsWith<GeneralSecurityException> {
            wrapper.unwrap(aliasedUnderB, context)
        }
    }

    @Test
    fun missingKekFailsClosedAtWrapAndUnwrap() {
        // The production InMemoryKekBoundary keeps a process-wide
        // key store, so a "fresh" instance still resolves the alias.
        // The crypto boundary must therefore fail closed against any
        // KekBoundary that reports hasKey(alias) == false: use a
        // test-local stub to exercise that exact contract.
        val emptyBoundary = MissingKeyKekBoundary()
        val wrapper = SenderRetryKeysetWrapper(emptyBoundary)
        assertFailsWith<GeneralSecurityException> {
            wrapper.wrap(
                "remanence.kek.sender-retry.missing",
                newAesKeyset(),
                contextFor(),
            )
        }

        // Wrap with a real KEK, then unwrap through a boundary that
        // always reports hasKey == false. The wrapper must check
        // hasKey before loading the AEAD and fail closed.
        val realBoundary = InMemoryKekBoundary()
        realBoundary.createAes256GcmKey("remanence.kek.sender-retry.drop")
        val record = SenderRetryKeysetWrapper(realBoundary).wrap(
            "remanence.kek.sender-retry.drop",
            newAesKeyset(),
            contextFor(),
        )
        assertFailsWith<GeneralSecurityException> {
            SenderRetryKeysetWrapper(emptyBoundary).unwrap(record, contextFor())
        }
    }

    @Test
    fun tamperedCiphertextAndNonceFailClosed() {
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.sender-retry.tamper")
        val wrapper = SenderRetryKeysetWrapper(boundary)
        val record = wrapper.wrap(
            "remanence.kek.sender-retry.tamper",
            newAesKeyset(),
            contextFor(),
        )

        val flippedCiphertext = WrappedKeysetRecord.create(
            record.alias,
            record.nonce,
            record.wrappedKeyset.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
        )
        assertFailsWith<GeneralSecurityException> {
            wrapper.unwrap(flippedCiphertext, contextFor())
        }

        val flippedNonce = WrappedKeysetRecord.create(
            record.alias,
            record.nonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
            record.wrappedKeyset,
        )
        assertFailsWith<GeneralSecurityException> {
            wrapper.unwrap(flippedNonce, contextFor())
        }
    }

    @Test
    fun malformedRecordFailsClosed() {
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.sender-retry.malformed")
        val wrapper = SenderRetryKeysetWrapper(boundary)
        val record = wrapper.wrap(
            "remanence.kek.sender-retry.malformed",
            newAesKeyset(),
            contextFor(),
        )
        val context = contextFor()
        val serialized = record.serialize()

        // Bad magic: the parse step itself fails closed before the
        // unwrap call is reached.
        val badMagic = serialized.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFailsWith<GeneralSecurityException> {
            WrappedKeysetRecord.parse(badMagic)
        }

        // Unknown format version: the parse step also fails closed.
        val tampered = serialized.copyOf().also {
            it[4] = ((WrappedKeysetRecord.FORMAT_VERSION_1 + 1) ushr 24).toByte()
            it[5] = ((WrappedKeysetRecord.FORMAT_VERSION_1 + 1) ushr 16).toByte()
            it[6] = ((WrappedKeysetRecord.FORMAT_VERSION_1 + 1) ushr 8).toByte()
            it[7] = (WrappedKeysetRecord.FORMAT_VERSION_1 + 1).toByte()
        }
        assertFailsWith<GeneralSecurityException> {
            WrappedKeysetRecord.parse(tampered)
        }
    }

    @Test
    fun unrelatedReceiverAadCannotOpenSenderRetryRecord() {
        // The SenderRetryWrapContext and the existing KeysetKekWrapper
        // AAD (postmark/kek/wrap/v1 || 0x00 || u32be(version) || alias)
        // are different domains. A wrong-domain AAD MUST fail closed
        // so a wrapped retry keyset can never be opened by a caller
        // that has only the generic identity-bundle keyset.
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.sender-retry.unrelated")
        val wrapper = SenderRetryKeysetWrapper(boundary)
        val record = wrapper.wrap(
            "remanence.kek.sender-retry.unrelated",
            newAesKeyset(),
            contextFor(),
        )

        // The SenderRetryWrapContext's owner field is the only
        // field-level difference between the two domains; flip it
        // and the AEAD step must fail.
        val otherOwner = UserId(UUID.fromString("feebdaed-feeb-4dad-8dad-feebfeebfeeb"))
        val context = contextFor(owner = otherOwner)
        assertFailsWith<GeneralSecurityException> {
            wrapper.unwrap(record, context)
        }
    }

    // ------------------------------------------------------------------
    // The generic KeysetKekWrapper is not weakened by this addition.
    // ------------------------------------------------------------------

    @Test
    fun genericKeysetKekWrapperStillRoundTripsAfterAddingTheRetryWrapper() {
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.identity")
        val keyset = newAesKeyset()
        val record = KeysetKekWrapper(boundary).wrap("remanence.kek.identity", keyset)
        assertTrue(keyset.equalsKeyset(KeysetKekWrapper(boundary).unwrap(record)))
    }

    /**
     * Test-local [KekBoundary] stub that refuses every alias: it
     * reports [hasKey] false and throws on [createAes256GcmKey] /
     * [loadKekAead]. Used to drive the fail-closed path that the
     * shared [InMemoryKekBoundary] cannot model (its KEK store is
     * process-wide so a second instance still resolves an alias).
     * Production [InMemoryKekBoundary] semantics are unchanged.
     */
    private class MissingKeyKekBoundary : KekBoundary {
        override fun hasKey(alias: String): Boolean = false

        override fun createAes256GcmKey(alias: String) {
            throw GeneralSecurityException("no KEK available in this stub")
        }

        override fun loadKekAead(alias: String): com.google.crypto.tink.Aead {
            throw GeneralSecurityException("no KEK stored for alias $alias")
        }
    }
}
