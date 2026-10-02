package dev.hryshyn.remanence.ui.create

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.ContentManifestCodec
import dev.hryshyn.remanence.core.crypto.ExpressionReceiverAdmission
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorStaging
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.create.CapsuleExpressionArtifact
import dev.hryshyn.remanence.create.CapsulePublishRequest
import dev.hryshyn.remanence.create.CapsulePublisher
import dev.hryshyn.remanence.create.GeneratorCreateBridge
import dev.hryshyn.remanence.create.GeneratorSourceBinding
import dev.hryshyn.remanence.create.NormalizedPhotoDto
import dev.hryshyn.remanence.create.PhotoNormalizerPort
import dev.hryshyn.remanence.create.PhotoSource
import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture
import dev.hryshyn.remanence.ui.collage.BundledCollageRenderCapability
import dev.hryshyn.remanence.ui.collage.CollageCandidate
import dev.hryshyn.remanence.ui.collage.CollageCandidateProvider
import dev.hryshyn.remanence.ui.collage.CollageCropSource
import dev.hryshyn.remanence.ui.collage.CollageProviderResult
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Local candidate → bridge → publisher → recipient → exact Scene proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrozenV5PublishVerticalSliceTest {

    private val capsuleUuid = UUID.fromString("7b111111-2222-4333-8444-555555555555")
    private val userUuid = UUID.fromString("7b222222-3333-4444-8555-666666666666")
    private val bundleUuid = UUID.fromString("7b333333-4444-4555-8666-777777777777")
    private val trackUuid = UUID.fromString("7b444444-5555-4666-8777-888888888888")
    private val otherTrackUuid = UUID.fromString("7b555555-6666-4777-8888-999999999999")
    private val identity = AccountIdentityGenerator().generate()
    private val kek = SoftwareKekBoundary()
    private val alias = "test-v5-${UUID.randomUUID()}"

    private val originals = listOf(
        ByteArray(64) { (it + 1).toByte() },
        ByteArray(64) { (it + 2).toByte() },
        ByteArray(64) { (it + 3).toByte() },
    )

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val snapshot = CapsuleTrackSnapshotV1.parse(
        trackId = trackUuid.toString(),
        title = "Frozen Song",
        artistDisplay = "Exact Artist",
        version = null,
        durationMs = 180_000,
    )

    private fun input(music: GeneratorExpression.MusicRef? = GeneratorExpression.MusicRef(trackUuid.toString(), null)) =
        GeneratorExpression.GeneratorInput(
            ownerId = userUuid.toString(),
            epoch = 7L,
            photos = originals.mapIndexed { index, bytes ->
                val hash = sha(bytes)
                GeneratorExpression.PhotoRef(
                    contentId = CapsulePhotoIdentity.contentIdFor(hash),
                    ordinal = index,
                    widthPx = 1000,
                    heightPx = 800,
                    contentHash = hash,
                )
            },
            note = "one exact note",
            music = music,
        )

    private val measurer = CollageRenderer.TextMeasurer { request ->
        CollageRenderer.TextMeasurement(
            width = (request.text.length * 4).coerceIn(1, request.maxWidth),
            height = request.line.coerceIn(1, request.maxHeight),
            hasVisualOverflow = false,
        )
    }

    private val crops = CollageCropSource { requests ->
        requests.mapIndexed { index, request ->
            request.contentId to GeneratorExpression.CropWindow(index * 100, 0, 5_000, 10_000)
        }.toMap()
    }

    @Before
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        kek.createAes256GcmKey(alias)
    }

    private suspend fun candidate(
        sourceInput: GeneratorExpression.GeneratorInput = input(),
        track: CapsuleTrackSnapshotV1? = snapshot,
    ): CollageCandidate {
        val result = CollageCandidateProvider(crops, measurer)
            .candidates(sourceInput, sourceInput.note, track)
        assertTrue("expected Candidates, got $result", result is CollageProviderResult.Candidates)
        // T151: every grammar-5 branch is enumerated; use the legacy equal
        // branch for the publish-slice fixtures.
        return (result as CollageProviderResult.Candidates).candidates
            .single { it.styleId == "press" && it.branchId == "editorial-1+2-equal-1" }
    }

    private suspend fun frozen(
        sourceInput: GeneratorExpression.GeneratorInput = input(),
        track: CapsuleTrackSnapshotV1? = snapshot,
    ): CollageFrozenSelection {
        val candidate = candidate(sourceInput, track)
        return requireNotNull(buildFrozenSelection(candidate, candidate.expression.input, capsuleUuid.toString()))
    }

    @Test
    fun frozenGateRejectsMissingStaleTamperedAndTrackInconsistentValues() = runTest {
        val frozen = frozen()
        fun refused(
            selection: CollageFrozenSelection? = frozen,
            preread: GeneratorExpression.GeneratorInput = input(),
            track: CapsuleTrackSnapshotV1? = snapshot,
        ) = acceptFrozenForPublish(selection, preread, track, capsuleUuid.toString()) is
            FrozenPublishAcceptance.Refused

        assertTrue(refused(selection = null))
        assertTrue(refused(preread = input().copy(note = "one exact note ")))
        assertTrue(refused(preread = input().copy(photos = input().photos.reversed())))
        assertTrue(refused(preread = input().copy(photos = input().photos.mapIndexed { index, photo ->
            if (index == 1) photo.copy(widthPx = photo.widthPx + 1) else photo
        })))
        assertTrue(refused(selection = frozen.copy(candidateId = "ber1-tampered")))
        assertTrue(refused(selection = frozen.copy(projectedHash = "BEXPR02-tampered")))
        assertTrue(refused(selection = frozen.copy(inputHash = "GENEX03-tampered")))
        assertTrue(refused(track = null))
        assertTrue(refused(track = CapsuleTrackSnapshotV1.parse(
            otherTrackUuid.toString(), "Other", "Artist", null, null,
        )))

        val photoOnly = frozen(input(music = null), track = null)
        assertTrue(
            acceptFrozenForPublish(photoOnly, input(music = null), snapshot, capsuleUuid.toString()) is
                FrozenPublishAcceptance.Refused,
        )
        val artworkInput = input().copy(music = GeneratorExpression.MusicRef(trackUuid.toString(), "artwork"))
        val artworkFrozen = frozen.copy(expression = frozen.expression.copy(input = artworkInput))
        val artworkRefusal = acceptFrozenForPublish(
            artworkFrozen, artworkInput, snapshot, capsuleUuid.toString(),
        )
        assertEquals(
            FrozenPublishAcceptance.Refused("music artwork is not supported"),
            artworkRefusal,
        )
    }

    @Test
    fun exactFrozenV5RoundTripsThroughRealBridgePublisherAdmissionAndRenderer() = runTest {
        val frozen = frozen()
        val accepted = acceptFrozenForPublish(frozen, input(), snapshot, capsuleUuid.toString())
        assertTrue(accepted is FrozenPublishAcceptance.Accepted)

        val store = MemoryStore()
        val bridge = GeneratorCreateBridge.Bridge(
            GeneratorStaging.Manager(store, nowMillis = { 1_000L }),
            GeneratorSourceBinding.SourceBinder(FakeNormalizer(), FixedDecoder()),
        )
        val begun = requireNotNull(
            bridge.begin(
                UserId(userUuid),
                7L,
                capsuleUuid.toString(),
                frozen.expression.input,
                GeneratorStaging.SenderSnapshot(userUuid.toString(), "sender"),
            ),
        )
        val bound = originals.mapIndexed { index, bytes ->
            bridge.bindSlot(
                begun.context,
                begun.sessionId,
                index,
                PhotoSource { ByteArrayInputStream(bytes) },
            ) as GeneratorCreateBridge.SlotResult.Bound
        }
        val handoff = (bridge.freeze(begun.context, begun.sessionId) as
            GeneratorCreateBridge.FreezeResult.Frozen).handoff
        assertEquals(frozen.expression.input, handoff.input)
        assertEquals(frozen.inputHash, handoff.inputHash)

        val prepared = CapsulePublisher(SenderRetryKeysetWrapper(kek), alias).publish(
            CapsulePublishRequest(
                capsuleId = CapsuleId(capsuleUuid),
                senderUserId = UserId(userUuid),
                recipientUserId = UserId(userUuid),
                senderKeyBundleId = KeyBundleId(bundleUuid),
                recipientKeyBundleId = KeyBundleId(bundleUuid),
                ownerUserId = userUuid.toString(),
                senderHandleSnapshot = "sender",
                createdAtEpochSeconds = 1_700_000_000L,
                photoJpegs = bound.map { it.normalized.bytes },
                photoWidthsPx = bound.map { it.normalized.widthPx },
                photoHeightsPx = bound.map { it.normalized.heightPx },
                noteUtf8 = frozen.expression.input.note,
                frontFingerprintBytes = CanonicalSiftFingerprintFixture.bytes(seed = 8),
                signingKeyset = identity.signingPrivateHandle,
                recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    identity.encryptionPublicKeyset,
                ),
                expression = CapsuleExpressionArtifact(
                    frozen.candidateId,
                    frozen.expression.input.photos.map { it.contentId },
                    frozen.expression,
                ),
                trackSnapshot = snapshot,
            ),
        )

        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            RecipientEnvelopeContextInput(
                CapsuleId(capsuleUuid), UserId(userUuid), UserId(userUuid), KeyBundleId(bundleUuid),
            ),
            prepared.envelopeCiphertext,
        )
        val capsuleKeyset = TinkProtoKeysetFormat.parseKeyset(
            RecipientEnvelopePlaintext.parseFrom(opened).capsuleAeadKeyset.toByteArray(),
            InsecureSecretKeyAccess.get(),
        )
        val contentArtifact = prepared.artifacts.single { it.kind == OutboxArtifactKind.CONTENT_MANIFEST }
        val parsed = ContentManifestCodec().decryptAndParse(
            capsuleKeyset,
            RecognitionManifestCodec.RoutingContext(
                CapsuleId(capsuleUuid), BlobId(contentArtifact.blobId), UserId(userUuid), UserId(userUuid),
            ),
            contentArtifact.ciphertext,
        )
        val admitted = ExpressionReceiverAdmission.admit(parsed, BundledCollageRenderCapability)
        assertTrue("expected Supported, got $admitted", admitted is ExpressionReceiverAdmission.Result.Supported)
        val recipientExpression = (admitted as ExpressionReceiverAdmission.Result.Supported).expression

        val sources = frozen.expression.input.photos.associate { photo ->
            photo.contentId to CollageRenderer.SourcePhoto(200, 160)
        }
        val senderScene = CollageRenderer.prepare(
            frozen.expression, frozen.expression.input.note, snapshot, sources, measurer,
        ) as CollageRenderer.SceneResult.Ready
        val recipientScene = CollageRenderer.prepare(
            recipientExpression, parsed.note, parsed.trackSnapshot, sources, measurer,
        ) as CollageRenderer.SceneResult.Ready
        assertEquals(senderScene.scene, recipientScene.scene)

        val firstPlacement = recipientExpression.placements.first()
        val crop = requireNotNull(firstPlacement.crop)
        val tamperedExpression = recipientExpression.copy(
            placements = recipientExpression.placements.toMutableList().also { placements ->
                placements[0] = firstPlacement.copy(crop = crop.copy(x = 9_999))
            },
        )
        assertTrue(
            CollageRenderer.prepare(
                tamperedExpression, parsed.note, parsed.trackSnapshot, sources, measurer,
            ) is CollageRenderer.SceneResult.Rejected,
        )
    }

    private class MemoryStore : GeneratorStaging.BlobStore {
        private val blobs = mutableMapOf<String, ByteArray>()
        override fun put(key: String, bytes: ByteArray) { blobs[key] = bytes.copyOf() }
        override fun get(key: String): ByteArray? = blobs[key]?.copyOf()
        override fun delete(key: String): Boolean = blobs.remove(key) != null
        override fun keys(): Set<String> = blobs.keys.toSet()
    }

    private class FixedDecoder : GeneratorSourceBinding.PhotoDecoderPort {
        override suspend fun decodeUpright(jpeg: ByteArray) =
            GeneratorSourceBinding.UprightPhoto(1000, 800)
    }

    private class FakeNormalizer : PhotoNormalizerPort {
        override suspend fun normalize(inputJpeg: ByteArray) =
            NormalizedPhotoDto(ByteArray(32) { 9 }, 200, 160)
    }
}
