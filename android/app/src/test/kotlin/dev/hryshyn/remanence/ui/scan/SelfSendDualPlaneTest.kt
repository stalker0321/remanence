package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.create.CapsulePublisher
import dev.hryshyn.remanence.create.CapsulePublishRequest
import dev.hryshyn.remanence.identity.DirectorySenderKeyStore
import dev.hryshyn.remanence.index.SenderIndexBundleSenderVerification
import dev.hryshyn.remanence.index.SenderIndexBundleStageRequest
import dev.hryshyn.remanence.index.SenderIndexBundleStageResult
import dev.hryshyn.remanence.index.SenderIndexBundleStager
import dev.hryshyn.remanence.index.SenderIndexBundleReader
import dev.hryshyn.remanence.sync.CurrentRecipientEncryptionIdentity
import dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture
import dev.hryshyn.remanence.test.DeterministicSiftMatcher
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.IncomingPresentationPreparation
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.wiring.KekBoundSecretSealer
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.PresentationAcceptanceGate
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.BlobCacheEntity
import dev.hryshyn.remanence.core.data.db.BlobCacheState
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleEntity
import dev.hryshyn.remanence.core.data.db.IncomingEnvelopeEntity
import dev.hryshyn.remanence.core.data.db.LocalMaterialTransitionResult
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.EncryptedFingerprintStore
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.IndexedCandidate
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.ScanGrantManager
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority

/**
 * Self-send dual-plane regression: after incoming sync the same capsule
 * legitimately lives in BOTH the outbox (sender) and incoming (recipient)
 * planes under one owner. The scan index must resolve that exact shape to
 * INCOMING so the capsule opens through incoming preparation; every other
 * dual shape stays ambiguous and fail-closed, and an unread incoming bundle
 * is never masked by the sender's copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelfSendDualPlaneTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var database: RemanenceLocalDatabase
    private lateinit var filesRoot: File
    private lateinit var roots: AccountScopedFileRoots

    private val identity = AccountIdentityGenerator().generate()
    private val capsuleUuid = UUID.fromString("6d111111-2222-4333-8444-555555555555")
    private val userUuid = UUID.fromString("6d222222-3333-4444-8555-666666666666")
    private val bundleUuid = UUID.fromString("6d333333-4444-4555-8666-777777777777")
    private val foreignUserUuid = UUID.fromString("6d444444-5555-4666-8777-888888888888")
    private val foreignBundleUuid = UUID.fromString("6d555555-6666-4777-8888-999999999999")
    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-self-dual-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    private val createdAtEpochSeconds = 1_700_000_000L
    private val senderHandle = "mykola"
    private val selfNote = "self note"

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesRoot = File(context.filesDir, "self-dual-plane").apply { mkdirs() }
        roots = AccountScopedFileRoots(filesRoot)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        filesRoot.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Pure routing units.
    // ------------------------------------------------------------------

    @Test
    fun selfRoutedDualResolvesIncoming() {
        val resolved = resolvePresentationSources(
            candidateIds = listOf(capsuleUuid),
            incomingSources = mapOf(capsuleUuid to CapsulePresentationSource.INCOMING),
            roomSources = mapOf(capsuleUuid to CapsulePresentationSource.OUTBOX),
            selfRoutedCapsuleIds = setOf(capsuleUuid),
        )
        assertEquals(CapsulePresentationSource.INCOMING, resolved[capsuleUuid])
    }

    @Test
    fun dualWithoutSelfProofStaysRejected() {
        val resolved = resolvePresentationSources(
            candidateIds = listOf(capsuleUuid),
            incomingSources = mapOf(capsuleUuid to CapsulePresentationSource.INCOMING),
            roomSources = mapOf(capsuleUuid to CapsulePresentationSource.OUTBOX),
        )
        assertTrue(resolved.isEmpty())

        val other = UUID.fromString("6d666666-7777-4888-8999-aaaaaaaaaaaa")
        val resolvedOther = resolvePresentationSources(
            candidateIds = listOf(capsuleUuid),
            incomingSources = mapOf(capsuleUuid to CapsulePresentationSource.INCOMING),
            roomSources = mapOf(capsuleUuid to CapsulePresentationSource.OUTBOX),
            selfRoutedCapsuleIds = setOf(other),
        )
        assertTrue(resolvedOther.isEmpty())
    }

    @Test
    fun singlePlaneBindingsAreUnchanged() {
        assertEquals(
            CapsulePresentationSource.INCOMING,
            resolvePresentationSources(
                candidateIds = listOf(capsuleUuid),
                incomingSources = mapOf(capsuleUuid to CapsulePresentationSource.INCOMING),
                roomSources = emptyMap(),
                selfRoutedCapsuleIds = setOf(capsuleUuid),
            )[capsuleUuid],
        )
        assertEquals(
            CapsulePresentationSource.OUTBOX,
            resolvePresentationSources(
                candidateIds = listOf(capsuleUuid),
                incomingSources = emptyMap(),
                roomSources = mapOf(capsuleUuid to CapsulePresentationSource.OUTBOX),
                selfRoutedCapsuleIds = setOf(capsuleUuid),
            )[capsuleUuid],
        )
    }

    @Test
    fun proofIdBoundCapsAtIncomingPageMax() {
        // The bound is the protocol incoming page max, shared by every DAO
        // page on the proof path: no oversized batch input can be built.
        assertEquals(ProtocolV1Limits.INCOMING_PAGE_MAX, MAX_SELF_PROOF_DUAL_IDS)
        val overflowing = (0..MAX_SELF_PROOF_DUAL_IDS).map { index ->
            UUID.nameUUIDFromBytes("self-dual-bound-$index".toByteArray())
        }
        assertEquals(MAX_SELF_PROOF_DUAL_IDS + 1, overflowing.size)
        assertTrue(
            "an oversized claimed set must yield no proof input at all",
            boundDualProofIds(overflowing).isEmpty(),
        )
        val fitting = overflowing.dropLast(1)
        assertEquals(fitting, boundDualProofIds(fitting))
        assertEquals(emptyList<UUID>(), boundDualProofIds(emptyList()))
    }

    // ------------------------------------------------------------------
    // Index-level integration through the real provider + real outbox DAO.
    // ------------------------------------------------------------------

    @Test
    fun selfDualIndexResolvesIncoming() = runBlocking {
        stageSelfOutboxWithSenderBaseline()
        seedIncomingCapsuleRow()
        stageSelfSenderIndexBundle()

        val vm = scanViewModelWithRealIncoming()
        try {
            val merged = vm.buildCandidateIndexForTests()
            assertTrue(merged.candidates.any { it.capsuleId == capsuleUuid })
            assertEquals(
                "self-routed dual must prefer the recipient plane",
                CapsulePresentationSource.INCOMING,
                merged.presentationSources[capsuleUuid],
            )
        } finally {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun nonSelfDualIndexStaysRejected() = runBlocking {
        stageCrossOutboxWithSenderBaseline()
        val vm = scanViewModelWithOverrideIncoming()
        try {
            val merged = vm.buildCandidateIndexForTests()
            assertTrue(merged.candidates.any { it.capsuleId == capsuleUuid })
            assertTrue(
                "non-self dual membership must stay ambiguous",
                merged.presentationSources.isEmpty(),
            )
        } finally {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun unreadIncomingBundleIsNeverMaskedBySenderCopy() = runBlocking {
        // Self outbox row plus a real sender baseline, but the incoming
        // bundle file was never staged: the membership fact exists without
        // readable incoming material, so no source may be published and no
        // grant may issue from the sender's copy.
        stageSelfOutboxWithSenderBaseline()
        seedIncomingCapsuleRow()

        val presentationGrants = PresentationGrantAuthority(ScanGrantManager({ 1_000L }))
        val vm = scanViewModelWithRealIncoming(presentationGrants)
        try {
            val merged = vm.buildCandidateIndexForTests()
            assertTrue(merged.candidates.any { it.capsuleId == capsuleUuid })
            assertTrue(
                "an unread incoming bundle must not fall back to the outbox copy",
                merged.presentationSources.isEmpty(),
            )

            readyCameras(vm)
            assertTrue(vm.beginFrontCapture())
            vm.deliverFrontJpeg("jpeg-front".toByteArray())
            withTimeout(10_000) { vm.matchState.first { it !is ScanMatchUiState.Matching } }
            assertNull(
                "unreadable incoming must issue no grant",
                presentationGrants.activeForTests(),
            )
            assertEquals(ScanTerminalState.Idle, vm.terminal.value)
        } finally {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            presentationGrants.clearAll()
        }
    }

    @Test
    fun incomingSenderMismatchRejectsSelfProof() = runBlocking {
        // The outbox plane claims self, but the incoming plane routes the
        // same capsule from a FOREIGN sender: disagreeing planes never prove
        // self, even with a perfectly readable incoming bundle.
        stageSelfOutboxWithSenderBaseline()
        seedIncomingCapsuleRow(senderUserId = foreignUserUuid.toString())
        stageSelfSenderIndexBundle()

        val vm = scanViewModelWithRealIncoming()
        try {
            val merged = vm.buildCandidateIndexForTests()
            assertTrue(merged.candidates.any { it.capsuleId == capsuleUuid })
            assertTrue(
                "an incoming sender that disagrees with the outbox self claim must stay ambiguous",
                merged.presentationSources.isEmpty(),
            )
        } finally {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun incomingRecipientOwnerMismatchRejectsSelfProof() = runBlocking {
        // The outbox plane claims self, but the incoming row is not addressed
        // to the current owner: no INCOMING binding may be published.
        stageSelfOutboxWithSenderBaseline()
        seedIncomingCapsuleRow(recipientUserId = foreignUserUuid.toString())
        stageSelfSenderIndexBundle()

        val vm = scanViewModelWithRealIncoming()
        try {
            val merged = vm.buildCandidateIndexForTests()
            assertTrue(merged.candidates.any { it.capsuleId == capsuleUuid })
            assertTrue(
                "an incoming row not addressed to the current owner must stay ambiguous",
                merged.presentationSources.isEmpty(),
            )
        } finally {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun missingIncomingRowRejectsSelfProof() = runBlocking {
        // A valid incoming-shaped candidate with no durable incoming row at
        // all (e.g. a stale provider view): the batch side of the proof is
        // absent, so even a clean self outbox row cannot resolve.
        stageSelfOutboxWithSenderBaseline()
        val vm = scanViewModelWithOverrideIncoming()
        try {
            val merged = vm.buildCandidateIndexForTests()
            assertTrue(merged.candidates.any { it.capsuleId == capsuleUuid })
            assertTrue(
                "a missing incoming row must stay ambiguous despite a self outbox row",
                merged.presentationSources.isEmpty(),
            )
        } finally {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun corruptOutboxRowWithValidSelfIncomingRejectsProof() = runBlocking {
        // The incoming plane is perfect (owned row, readable bundle), but the
        // outbox routing material is malformed: strict parsing fails closed
        // and no source is published.
        stageSelfOutboxWithSenderBaseline()
        tamperOutboxSenderUserId("not-a-uuid")
        seedIncomingCapsuleRow()
        stageSelfSenderIndexBundle()

        val vm = scanViewModelWithRealIncoming()
        try {
            val merged = vm.buildCandidateIndexForTests()
            assertTrue(merged.candidates.any { it.capsuleId == capsuleUuid })
            assertTrue(
                "a corrupt outbox row must fail closed even with valid incoming material",
                merged.presentationSources.isEmpty(),
            )
        } finally {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun foreignSenderOutboxWithSelfIncomingRejectsProof() = runBlocking {
        // The outbox row is addressed to the owner but sent by a FOREIGN
        // account, while the incoming plane claims self: the outbox binding
        // disagrees, so no source may be published.
        stageSelfOutboxWithSenderBaseline()
        tamperOutboxSenderUserId(foreignUserUuid.toString())
        seedIncomingCapsuleRow()
        stageSelfSenderIndexBundle()

        val vm = scanViewModelWithRealIncoming()
        try {
            val merged = vm.buildCandidateIndexForTests()
            assertTrue(merged.candidates.any { it.capsuleId == capsuleUuid })
            assertTrue(
                "a foreign-sender outbox row must stay ambiguous despite self incoming material",
                merged.presentationSources.isEmpty(),
            )
        } finally {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun oversizedClaimedSetResolvesNothingAndGrantsNothing() = runBlocking {
        // INCOMING_PAGE_MAX + 1 incoming-claimed IDs with one otherwise
        // perfectly valid self dual among them: the bound empties the proof
        // input, so no batch DAO can run oversized and the dual stays
        // sourceless (in particular no OUTBOX fallback for the valid self
        // dual), while ordinary single-plane INCOMING bindings are
        // unaffected. A full scan then issues no grant.
        stageSelfOutboxWithSenderBaseline()
        seedIncomingCapsuleRow()
        stageSelfSenderIndexBundle()
        val filler = (0 until MAX_SELF_PROOF_DUAL_IDS).map { index ->
            UUID.nameUUIDFromBytes("self-dual-overflow-$index".toByteArray())
        }
        check(capsuleUuid !in filler)
        val allIds = listOf(capsuleUuid) + filler
        check(allIds.size == MAX_SELF_PROOF_DUAL_IDS + 1)

        val presentationGrants = PresentationGrantAuthority(ScanGrantManager({ 1_000L }))
        val front = CanonicalSiftFingerprintFixture.bytes(11)
        val vm = ScanViewModel(
            persistence = store(),
            database = database,
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            identityProvider = { identitySnapshot() },
            trustedSenderKeys = ownTrustedSenderKeys(),
            presentationGrants = presentationGrants,
            candidateIndexProvider = {
                ScanCandidateIndex(
                    candidates = allIds.map { id ->
                        IndexedCandidate(
                            capsuleId = id,
                            front = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.parse(
                                front.copyOf(),
                            ),
                            recipientPreferred = false,
                        )
                    },
                    presentationSources = allIds.associateWith { CapsulePresentationSource.INCOMING },
                )
            },
            incomingPresentationPreparation = null,
            frontProcessor = FixedProcessor(front),
            matcher = DeterministicSiftMatcher.port(),
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
        try {
            val merged = vm.buildCandidateIndexForTests()
            try {
                assertEquals(
                    "all oversized claimed IDs must merge as candidates",
                    MAX_SELF_PROOF_DUAL_IDS + 1,
                    merged.candidates.size,
                )
                assertNull(
                    "the valid self dual must stay sourceless on overflow, with no OUTBOX fallback",
                    merged.presentationSources[capsuleUuid],
                )
                assertTrue(
                    "ordinary single-plane INCOMING bindings must survive proof overflow",
                    filler.all { merged.presentationSources[it] == CapsulePresentationSource.INCOMING },
                )
            } finally {
                merged.wipeFingerprints()
            }

            readyCameras(vm)
            assertTrue(vm.beginFrontCapture())
            vm.deliverFrontJpeg("jpeg-front".toByteArray())
            withTimeout(10_000) { vm.matchState.first { it !is ScanMatchUiState.Matching } }
            assertNull(
                "an oversized claimed set must issue no grant",
                presentationGrants.activeForTests(),
            )
            assertEquals(ScanTerminalState.Idle, vm.terminal.value)
        } finally {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            presentationGrants.clearAll()
        }
    }

    // ------------------------------------------------------------------
    // Full scan grant/open path for a self-send with both planes present.
    // ------------------------------------------------------------------

    @Test
    fun selfSendWithBothPlanesGrantsThroughIncomingAndOpensMaterial() = runBlocking {
        val photoBytes = (0 until 3).map { "self-dual-photo-$it".toByteArray() }
        stageSelfOutboxWithSenderBaseline(photoBytes = photoBytes)
        seedIncomingStateFromStagedOutbox()
        stageSelfSenderIndexBundle()

        val grantsManager = ScanGrantManager({ 1_000L })
        val presentationGrants = PresentationGrantAuthority(grantsManager)
        val provider = realIncomingProvider()
        val preparation = realPreparation()
        val front = CanonicalSiftFingerprintFixture.bytes(11)
        val scanVm = ScanViewModel(
            persistence = store(),
            database = database,
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            identityProvider = { identitySnapshot() },
            trustedSenderKeys = ownTrustedSenderKeys(),
            presentationGrants = presentationGrants,
            candidateIndexProvider = { provider.load(it) },
            incomingPresentationPreparation = preparation,
            frontProcessor = FixedProcessor(front),
            matcher = DeterministicSiftMatcher.port(),
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )

        try {
            readyCameras(scanVm)
            assertTrue(scanVm.beginFrontCapture())
            scanVm.deliverFrontJpeg("jpeg-front".toByteArray())

            val granted = withTimeout(10_000) {
                scanVm.terminal
                    .filterIsInstance<ScanTerminalState.Granted>()
                    .first()
            }
            assertEquals(capsuleUuid.toString(), granted.capsuleId)
            val binding = presentationGrants.activeForTests()
            assertEquals(CapsulePresentationSource.INCOMING, binding?.source)
            val prepared = requireNotNull(binding?.incomingPresentation)
            assertEquals(3, prepared.photoCount)
            assertEquals(selfNote, prepared.noteText())
            val photo = prepared.loadPhoto(0)
            try {
                assertTrue(photo.contentEquals(photoBytes[0]))
            } finally {
                photo.fill(0)
            }
        } finally {
            scanVm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            presentationGrants.clearAll()
        }
    }

    // ------------------------------------------------------------------
    // Fixture helpers.
    // ------------------------------------------------------------------

    private fun store() = EncryptedFingerprintStore(
        roots,
        KekBoundSecretSealer(SoftwareKekBoundary(), KekBoundSecretSealer.FINGERPRINT_SEALING_ALIAS),
        database.recognitionFingerprintDao(),
        ownerUserIdProvider = { userUuid.toString() },
    )

    private fun bundleSealer() =
        KekBoundSecretSealer(SoftwareKekBoundary(), "self-dual-plane-test")

    private fun realIncomingProvider(): IncomingSenderIndexCandidateProvider {
        val sealer = bundleSealer()
        return IncomingSenderIndexCandidateProvider(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            senderIndexBundleReader = SenderIndexBundleReader(roots, sealer),
            currentOwner = { UserId(userUuid) },
        )
    }

    private fun realPreparation(): IncomingPresentationPreparation {
        val sealer = bundleSealer()
        return IncomingPresentationPreparation(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            incomingEnvelopeDao = database.incomingEnvelopeDao(),
            blobCacheDao = database.blobCacheDao(),
            recipientTombstoneDao = database.recipientTombstoneDao(),
            roots = roots,
            senderIndexBundleReader = SenderIndexBundleReader(roots, sealer),
            currentRecipientIdentity = {
                CurrentRecipientEncryptionIdentity(
                    ownerUserId = UserId(userUuid),
                    activeKeyBundleId = KeyBundleId(bundleUuid),
                    encryptionPrivateKeyset = identity.encryptionPrivateHandle,
                    sessionLease = SessionRequestLease(UserId(userUuid), 1L),
                )
            },
            firstOpenClaim = { _, _, _ -> 1_700_000_000_002L },
            acceptanceGate = PresentationAcceptanceGate(),
            envelopeCryptor = RecipientEnvelopeCryptor(),
        )
    }

    private fun identitySnapshot() = SenderIdentitySnapshot(
        userId = userUuid.toString(),
        handle = senderHandle,
        activeKeyBundleId = bundleUuid.toString(),
        encryptionPrivateHandle = identity.encryptionPrivateHandle,
        signingPrivateHandle = identity.signingPrivateHandle,
    )

    private fun ownTrustedSenderKeys() = DirectorySenderKeyStore(
        directoryFetch = { error("self-send must resolve through the own account") },
        ownAccount = {
            DirectorySenderKeyStore.OwnAccount(
                userId = UserId(userUuid),
                activeKeyBundleId = KeyBundleId(bundleUuid),
                publicSigningExportB64Url =
                    com.google.crypto.tink.subtle.Base64.urlSafeEncode(identity.signingPublicKeyset),
            )
        },
    )

    private class FixedProcessor(private val serializedBytes: ByteArray) : StillProcessor {
        private val profile = RecognitionProfile.postcardSiftRootSiftV1()

        override fun process(jpegBytes: ByteArray): ProcessedStill {
            val delivered = serializedBytes.copyOf()
            return ProcessedStill.Accepted(profile.profileId, delivered)
        }
    }

    private fun readyCameras(vm: ScanViewModel) {
        vm.frontAttempt.onPermissionResult(granted = true, canAskAgain = false)
        vm.frontAttempt.onPreviewBound()
    }

    private fun scanViewModelWithRealIncoming(
        presentationGrants: PresentationGrantAuthority = PresentationGrantAuthority(ScanGrantManager({ 1_000L })),
    ): ScanViewModel {
        val provider = realIncomingProvider()
        return ScanViewModel(
            persistence = store(),
            database = database,
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            identityProvider = { identitySnapshot() },
            trustedSenderKeys = ownTrustedSenderKeys(),
            presentationGrants = presentationGrants,
            candidateIndexProvider = { provider.load(it) },
            incomingPresentationPreparation = null,
            frontProcessor = FixedProcessor(CanonicalSiftFingerprintFixture.bytes(11)),
            matcher = DeterministicSiftMatcher.port(),
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
    }

    private fun scanViewModelWithOverrideIncoming(): ScanViewModel =
        ScanViewModel(
            persistence = store(),
            database = database,
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            identityProvider = { identitySnapshot() },
            trustedSenderKeys = ownTrustedSenderKeys(),
            presentationGrants = PresentationGrantAuthority(ScanGrantManager({ 1_000L })),
            candidateIndexProvider = {
                ScanCandidateIndex(
                    candidates = listOf(
                        IndexedCandidate(
                            capsuleId = capsuleUuid,
                            front = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.parse(
                                CanonicalSiftFingerprintFixture.bytes(11),
                            ),
                            recipientPreferred = false,
                        ),
                    ),
                    presentationSources = mapOf(
                        capsuleUuid to CapsulePresentationSource.INCOMING,
                    ),
                )
            },
            incomingPresentationPreparation = null,
            frontProcessor = FixedProcessor(CanonicalSiftFingerprintFixture.bytes(11)),
            matcher = DeterministicSiftMatcher.port(),
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )

    private suspend fun stageSelfOutboxWithSenderBaseline(
        photoBytes: List<ByteArray> = (0 until 3).map { "self-dual-photo-$it".toByteArray() },
    ) {
        val front = CanonicalSiftFingerprintFixture.bytes(11)
        store().persist(
            capsuleUuid.toString(), FingerprintOrigin.SENDER,
            RecognitionProfile.postcardSiftRootSiftV1().profileId, front,
        )
        val prepared = CapsulePublisher(testWrapper, testAlias).publish(
            CapsulePublishRequest(
                capsuleId = CapsuleId(capsuleUuid),
                senderUserId = UserId(userUuid),
                recipientUserId = UserId(userUuid),
                senderKeyBundleId = KeyBundleId(bundleUuid),
                recipientKeyBundleId = KeyBundleId(bundleUuid),
                ownerUserId = userUuid.toString(),
                senderHandleSnapshot = senderHandle,
                createdAtEpochSeconds = createdAtEpochSeconds,
                photoJpegs = photoBytes.map { it.copyOf() },
                photoWidthsPx = listOf(800, 800, 800),
                photoHeightsPx = listOf(600, 600, 600),
                noteUtf8 = selfNote,
                frontFingerprintBytes = front,
                signingKeyset = identity.signingPrivateHandle,
                recipientEncryptionPublicKeyset =
                    TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.encryptionPublicKeyset),
            ),
        )
        CapsuleOutboxStager(database, roots, SenderRetryMaterialStore(roots)).stage(prepared)
        check(database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleUuid.toString(), userUuid.toString()) != null)
        check(database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleUuid.toString(), userUuid.toString()).size == 5)
    }

    private suspend fun stageCrossOutboxWithSenderBaseline() {
        val foreignIdentity = AccountIdentityGenerator().generate()
        val front = CanonicalSiftFingerprintFixture.bytes(11)
        store().persist(
            capsuleUuid.toString(), FingerprintOrigin.SENDER,
            RecognitionProfile.postcardSiftRootSiftV1().profileId, front,
        )
        val prepared = CapsulePublisher(testWrapper, testAlias).publish(
            CapsulePublishRequest(
                capsuleId = CapsuleId(capsuleUuid),
                senderUserId = UserId(userUuid),
                recipientUserId = UserId(foreignUserUuid),
                senderKeyBundleId = KeyBundleId(bundleUuid),
                recipientKeyBundleId = KeyBundleId(foreignBundleUuid),
                ownerUserId = userUuid.toString(),
                senderHandleSnapshot = senderHandle,
                createdAtEpochSeconds = createdAtEpochSeconds,
                photoJpegs = (0 until 3).map { "cross-photo-$it".toByteArray() },
                photoWidthsPx = listOf(800, 800, 800),
                photoHeightsPx = listOf(600, 600, 600),
                noteUtf8 = null,
                frontFingerprintBytes = front,
                signingKeyset = identity.signingPrivateHandle,
                recipientEncryptionPublicKeyset =
                    TinkProtoKeysetFormat.parseKeysetWithoutSecret(foreignIdentity.encryptionPublicKeyset),
            ),
        )
        CapsuleOutboxStager(database, roots, SenderRetryMaterialStore(roots)).stage(prepared)
    }

    private suspend fun seedIncomingCapsuleRow(
        senderUserId: String = userUuid.toString(),
        recipientUserId: String = userUuid.toString(),
    ) {
        database.incomingCapsuleDao().upsertAllForOwner(
            userUuid.toString(),
            listOf(
                IncomingCapsuleEntity(
                    capsuleId = capsuleUuid.toString(),
                    ownerUserId = userUuid.toString(),
                    senderUserId = senderUserId,
                    recipientUserId = recipientUserId,
                    senderSigningKeyBundleId = bundleUuid.toString(),
                    recipientEncryptionKeyBundleId = bundleUuid.toString(),
                    protocolVersion = ProtocolV1Limits.PROTOCOL_VERSION,
                    serverStatus = "READY",
                    readyAtEpochMs = 1_700_000_000_000,
                    signedStatementBytes = byteArrayOf(1, 2, 3),
                    materialState = LocalMaterialState.DISCOVERED,
                ),
            ),
        )
        check(
            database.incomingCapsuleDao().transitionMaterialStateForOwner(
                userUuid.toString(), capsuleUuid.toString(), LocalMaterialState.INDEX_CACHED,
            ) is LocalMaterialTransitionResult.Accepted,
        )
    }

    /**
     * Storage-level tamper of the test's own outbox row, mirroring
     * CapsuleRoutingCorruptionTest: strict insert refuses update-by-collision,
     * so replace via scoped delete + insert.
     */
    private suspend fun tamperOutboxSenderUserId(rawSenderUserId: String) {
        val row = database.outboxCapsuleDao().getByCapsuleIdAndOwner(
            capsuleUuid.toString(), userUuid.toString(),
        ) ?: error("staged outbox row is missing")
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM outbox_capsule WHERE capsule_id = ? AND owner_user_id = ?",
            arrayOf(row.capsuleId, userUuid.toString()),
        )
        database.outboxCapsuleDao().insertOrAbort(
            userUuid.toString(),
            row.copy(senderUserId = rawSenderUserId),
        )
    }

    private suspend fun stageSelfSenderIndexBundle() {
        val sealer = bundleSealer()
        val result = SenderIndexBundleStager(roots, sealer).stage(
            SenderIndexBundleStageRequest(
                authenticatedOwnerUserId = UserId(userUuid),
                ownerUserId = UserId(userUuid),
                capsuleId = CapsuleId(capsuleUuid),
                verifiedRecognition = RecognitionManifestContent(
                    manifestVersion = RecognitionManifestCodec.FORMAT_VERSION,
                    capsuleIdRaw = CapsuleId(capsuleUuid).toProtoBytes().toByteArray(),
                    senderHandleSnapshot = senderHandle,
                    createdAtEpochSeconds = createdAtEpochSeconds,
                    placeLabel = null,
                    frontFingerprint = CanonicalSiftFingerprintFixture.bytes(11),
                ),
                senderVerification = SenderIndexBundleSenderVerification.fromTrusted(
                    UserId(userUuid),
                    KeyBundleId(bundleUuid),
                    TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.signingPublicKeyset),
                ),
            ),
        )
        check(result is SenderIndexBundleStageResult.Staged)
    }

    /**
     * Mirrors the staged outbox ciphertext into the incoming plane so the
     * full preparation gate verifies against byte-identical material, exactly
     * as a synced self-send would arrive from the server.
     */
    private suspend fun seedIncomingStateFromStagedOutbox() {
        val outboxRow = database.outboxCapsuleDao().getByCapsuleIdAndOwner(
            capsuleUuid.toString(), userUuid.toString(),
        ) ?: error("staged outbox row is missing")
        val statementBytes = File(requireNotNull(outboxRow.publishStatementPath)).readBytes()
        val signatureBytes = File(requireNotNull(outboxRow.publishStatementSignaturePath)).readBytes()
        val envelopeBytes = File(requireNotNull(outboxRow.envelopePath)).readBytes()

        database.incomingCapsuleDao().upsertAllForOwner(
            userUuid.toString(),
            listOf(
                IncomingCapsuleEntity(
                    capsuleId = capsuleUuid.toString(),
                    ownerUserId = userUuid.toString(),
                    senderUserId = userUuid.toString(),
                    recipientUserId = userUuid.toString(),
                    senderSigningKeyBundleId = bundleUuid.toString(),
                    recipientEncryptionKeyBundleId = bundleUuid.toString(),
                    protocolVersion = ProtocolV1Limits.PROTOCOL_VERSION,
                    serverStatus = "READY",
                    readyAtEpochMs = 1_700_000_000_000,
                    signedStatementBytes = statementBytes,
                    signedStatementSha256 = sha256(statementBytes),
                    publishSignatureBytes = signatureBytes,
                    materialState = LocalMaterialState.DISCOVERED,
                ),
            ),
        )
        check(
            database.incomingCapsuleDao().transitionMaterialStateForOwner(
                userUuid.toString(), capsuleUuid.toString(), LocalMaterialState.INDEX_CACHED,
            ) is LocalMaterialTransitionResult.Accepted,
        )
        check(
            database.incomingCapsuleDao().transitionMaterialStateForOwner(
                userUuid.toString(), capsuleUuid.toString(), LocalMaterialState.MATERIAL_CACHED,
            ) is LocalMaterialTransitionResult.Accepted,
        )
        database.incomingEnvelopeDao().upsertForOwner(
            userUuid.toString(),
            IncomingEnvelopeEntity(
                capsuleId = capsuleUuid.toString(),
                ownerUserId = userUuid.toString(),
                recipientKeyBundleId = bundleUuid.toString(),
                hpkeCiphertext = envelopeBytes,
                transportSha256 = sha256(envelopeBytes),
                receivedAtEpochMs = 1_700_000_000_001,
            ),
        )
        val outboxBlobs = database.outboxBlobDao().getAllByCapsuleIdAndOwner(
            capsuleUuid.toString(), userUuid.toString(),
        )
        check(outboxBlobs.size == 5)
        outboxBlobs.forEach { outboxBlob ->
            val ciphertext = File(outboxBlob.localCiphertextPath).readBytes()
            val blobId = BlobId.parseRest(outboxBlob.blobId)
            val kind = CapsuleArtifactKind.valueOf(outboxBlob.kind)
            val destination = roots.incomingCiphertextPath(
                UserId(userUuid), CapsuleId(capsuleUuid), blobId,
            ).toFile()
            check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
            destination.writeBytes(ciphertext)
            database.blobCacheDao().upsertForOwner(
                userUuid.toString(),
                BlobCacheEntity(
                    blobId = outboxBlob.blobId,
                    ownerUserId = userUuid.toString(),
                    capsuleId = capsuleUuid.toString(),
                    kind = kind.name,
                    ordinal = if (kind == CapsuleArtifactKind.PHOTO) outboxBlob.ordinal else null,
                    expectedSizeBytes = ciphertext.size.toLong(),
                    expectedSha256 = sha256(ciphertext),
                    localPath = destination.path,
                    cacheState = BlobCacheState.CACHED,
                ),
            )
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
