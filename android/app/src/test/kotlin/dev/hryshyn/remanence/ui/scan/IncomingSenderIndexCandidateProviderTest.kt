package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleEntity
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SecretSealer
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.TestSenderVerification
import dev.hryshyn.remanence.core.recognition.ExtractionQuality
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintKeypoint
import dev.hryshyn.remanence.core.recognition.PostcardFingerprint
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.index.SenderIndexBundleFileAttributes
import dev.hryshyn.remanence.index.SenderIndexBundleReader
import dev.hryshyn.remanence.index.SenderIndexBundleReaderFileSystem
import dev.hryshyn.remanence.index.SenderIndexBundleStageRequest
import dev.hryshyn.remanence.index.SenderIndexBundleStageResult
import dev.hryshyn.remanence.index.SenderIndexBundleStager
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.wiring.KekBoundSecretSealer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingSenderIndexCandidateProviderTest {

    private val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c001")
    private val otherOwner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c002")
    private lateinit var database: RemanenceLocalDatabase
    private lateinit var filesDir: File
    private lateinit var roots: AccountScopedFileRoots
    private lateinit var sealer: SecretSealer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesDir = File(
            System.getProperty("java.io.tmpdir"),
            "remanence-incoming-scan-index-${System.nanoTime()}",
        ).also { check(it.mkdirs()) }
        roots = AccountScopedFileRoots(filesDir)
        sealer = KekBoundSecretSealer(SoftwareKekBoundary(), "incoming-scan-index-test")
    }

    @After
    fun tearDown() {
        database.close()
        filesDir.deleteRecursively()
    }

    @Test
    fun realRoomAndStagedReaderProduceOnlyAcceptedOwnerSenderCandidates() = runBlocking {
        val valid = capsule("0198f0a0-0000-7000-8000-00000000c011", 30, owner)
        val missing = capsule("0198f0a0-0000-7000-8000-00000000c012", 10, owner)
        val corrupt = capsule("0198f0a0-0000-7000-8000-00000000c013", 20, owner)
        seedIndexed(valid)
        seedIndexed(missing)
        seedIndexed(corrupt)
        stage(valid.capsuleId)
        destination(owner, CapsuleId.parseRest(corrupt.capsuleId)).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val provider = IncomingSenderIndexCandidateProvider(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            senderIndexBundleReader = SenderIndexBundleReader(roots, sealer),
            currentOwner = { owner },
        )
        val index = provider.load(owner)

        assertEquals(listOf(valid.capsuleId), index.candidates.map { it.capsuleId.toString() })
        assertTrue(index.candidates.single().recipientPreferred.not())
        assertEquals("sender_c011", index.chooserHints[valid.capsuleId]?.senderHandleSnapshot)
        assertEquals("place_c011", index.chooserHints[valid.capsuleId]?.placeLabel)
        assertEquals(
            CapsulePresentationSource.INCOMING,
            index.presentationSources[UUID.fromString(valid.capsuleId)],
        )
        assertEquals(
            CapsulePresentationSource.INCOMING,
            index.presentationSources[UUID.fromString(missing.capsuleId)],
        )
        assertEquals(
            CapsulePresentationSource.INCOMING,
            index.presentationSources[UUID.fromString(corrupt.capsuleId)],
        )
        assertFalse(index.toString().contains("sender_c011"))
        assertFalse(index.toString().contains(filesDir.path))
    }

    @Test
    fun ownerSwitchBeforeCompletionDiscardsAlreadyReadCandidates() = runBlocking {
        val first = capsule("0198f0a0-0000-7000-8000-00000000c021", 1, owner)
        seedIndexed(first)
        stage(first.capsuleId)
        var ownerChecks = 0
        val provider = IncomingSenderIndexCandidateProvider(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            senderIndexBundleReader = SenderIndexBundleReader(roots, sealer),
            currentOwner = {
                ownerChecks += 1
                if (ownerChecks < 3) owner else otherOwner
            },
        )

        val index = provider.load(owner)

        assertTrue(index.candidates.isEmpty())
        assertTrue(index.chooserHints.isEmpty())
    }

    @Test
    fun readerSnapshotAndTemporaryCopiesAreWipedOnProviderSuccess() = runBlocking {
        val first = capsule("0198f0a0-0000-7000-8000-00000000c031", 1, owner)
        seedIndexed(first)
        stage(first.capsuleId)
        val wipedBytes = mutableListOf<ByteArray>()
        val wipedChars = mutableListOf<CharArray>()
        val reader = SenderIndexBundleReader(
            roots = roots,
            sealer = sealer,
            codec = dev.hryshyn.remanence.index.SenderIndexBundleCodec(),
            fileSystem = RealProviderFileSystem,
            wipe = { bytes -> wipedBytes += bytes; bytes.fill(0) },
            wipeChars = { chars -> wipedChars += chars; chars.fill('\u0000') },
        )

        IncomingSenderIndexCandidateProvider(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            senderIndexBundleReader = reader,
            currentOwner = { owner },
        ).load(owner)

        assertTrue(wipedBytes.isNotEmpty())
        assertTrue(wipedBytes.all { it.all { byte -> byte == 0.toByte() } })
        assertTrue(wipedChars.isNotEmpty())
        assertTrue(wipedChars.all { it.all { char -> char == '\u0000' } })
    }

    @Test
    fun otherOwnerRowsAreNotEnumeratedEvenWhenTheirBundleExists() = runBlocking {
        val foreign = capsule("0198f0a0-0000-7000-8000-00000000c041", 1, otherOwner)
        seedIndexed(foreign)
        stage(foreign.capsuleId)
        val provider = IncomingSenderIndexCandidateProvider(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            senderIndexBundleReader = SenderIndexBundleReader(roots, sealer),
            currentOwner = { owner },
        )

        val index = provider.load(owner)

        assertTrue(index.candidates.isEmpty())
        assertTrue(index.chooserHints.isEmpty())
    }

    private suspend fun seedIndexed(capsule: IncomingCapsuleEntity) {
        database.incomingCapsuleDao().upsertAllForOwner(
            capsule.ownerUserId,
            listOf(capsule.copy(materialState = LocalMaterialState.DISCOVERED)),
        )
        database.incomingCapsuleDao().transitionMaterialStateForOwner(
            capsule.ownerUserId,
            capsule.capsuleId,
            LocalMaterialState.INDEX_CACHED,
        )
    }

    private suspend fun stage(capsuleId: String) {
        val capsule = CapsuleId.parseRest(capsuleId)
        val result = SenderIndexBundleStager(roots, sealer).stage(
            SenderIndexBundleStageRequest(
                ownerFor(capsuleId),
                ownerFor(capsuleId),
                capsule,
                recognition(capsule),
                TestSenderVerification.forCapsule(capsule),
            ),
        )
        if (result !is SenderIndexBundleStageResult.Staged) error("test bundle was not staged")
    }

    private fun ownerFor(capsuleId: String): UserId =
        if (capsuleId.endsWith("c041")) otherOwner else owner

    private fun capsule(id: String, readyAt: Long, owner: UserId) = IncomingCapsuleEntity(
        capsuleId = id,
        ownerUserId = owner.toRestString(),
        senderUserId = "0198f0a0-0000-7000-8000-00000000c101",
        recipientUserId = owner.toRestString(),
        senderSigningKeyBundleId = "0198f0a0-0000-7000-8000-00000000c102",
        recipientEncryptionKeyBundleId = "0198f0a0-0000-7000-8000-00000000c103",
        protocolVersion = 1,
        serverStatus = "READY",
        readyAtEpochMs = readyAt,
        signedStatementBytes = byteArrayOf(1, 2, 3),
        materialState = LocalMaterialState.DISCOVERED,
    )

    private fun recognition(capsule: CapsuleId): RecognitionManifestContent =
        RecognitionManifestContent(
        manifestVersion = RecognitionManifestCodec.FORMAT_VERSION,
            capsuleIdRaw = capsule.toProtoBytes().toByteArray(),
            senderHandleSnapshot = "sender_${capsule.toRestString().takeLast(4)}",
            createdAtEpochSeconds = 1_700_000_001L,
            placeLabel = "place_${capsule.toRestString().takeLast(4)}",
            frontFingerprint = fingerprint(11),
        )

    private fun fingerprint(seed: Int): ByteArray = FingerprintCodec.serialize(
        PostcardFingerprint(
            profileId = RecognitionProfile.MVP_ORB_V1_ID,
            canonicalWidthPx = 1200,
            canonicalHeightPx = 800,
            coarseHash64 = seed.toLong(),
            keypoints = listOf(
                FingerprintKeypoint(0.5, 0.5, 1.0, 9000, seed, 0),
            ),
            descriptors = listOf(ByteArray(FingerprintCodec.DESCRIPTOR_BYTES) { seed.toByte() }),
            quality = ExtractionQuality(1.0, 1.0, 0.1, 0.5),
        ),
    )

    private fun destination(owner: UserId, capsule: CapsuleId): File = File(
        roots.child(owner, AccountScopedFileRoots.ChildRoot.FINGERPRINTS),
        "capsules/${capsule.toRestString()}.index.bundle",
    )
}

private object RealProviderFileSystem : SenderIndexBundleReaderFileSystem {
    override fun attributes(path: Path): SenderIndexBundleFileAttributes? = try {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        SenderIndexBundleFileAttributes(
            isSymbolicLink = attributes.isSymbolicLink,
            isRegularFile = attributes.isRegularFile,
            isDirectory = attributes.isDirectory,
            size = attributes.size(),
        )
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    override fun openRead(path: Path): InputStream =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
}
