package dev.hryshyn.remanence.core.data.outbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.CapsuleArtifactCryptor
import dev.hryshyn.remanence.core.crypto.CapsuleKeysetGenerator
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.db.OutboxBlobUploadState
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput
import dev.hryshyn.remanence.core.model.UserId

/**
 * M1-C14: the outbox staging transaction is atomic under local failure and
 * ciphertext-only. The canary payloads are really encrypted with the capsule
 * keyset and HPKE envelope, staged, and then every produced byte — artifact
 * files, envelope file, and the SQLite database file itself — is scanned for
 * the plaintext markers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapsuleOutboxStagerTest {

    private val noteMarker = "remanence-canary-NOTE-plaintext-Ω"
    private val photoMarker = "remanence-canary-JPEG-plaintext-bytes"
    private val manifestMarker = "remanence-canary-MANIFEST-plaintext"

    private lateinit var context: Context
    private lateinit var database: RemanenceLocalDatabase
    private lateinit var storageRoots: dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots

    private val capsuleId = UUID.fromString("2b111111-2222-4333-8444-555555555555")
    private val recipientUser = UUID.fromString("2b222222-3333-4444-8555-666666666666")
    private val senderUser = UUID.fromString("2b333333-4444-4555-8666-777777777777")
    private val recipientBundle = UUID.fromString("2b444444-5555-4666-8777-888888888888")
    private val senderBundle = UUID.fromString("2b555555-6666-4777-8888-999999999999")

    private companion object {
        const val OWNER: String = "0198f0a0-0000-7000-8000-00000000ab01"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TinkPrimitives.ensureRegistered()
        File(context.filesDir, "accounts").deleteRecursively()
        storageRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(context.filesDir)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        File(context.filesDir, "accounts").deleteRecursively()
    }

    /** The staged owner's own outbox-ciphertext directory (per P04 wiring). */
    private fun outboxRoot(owner: String = OWNER): File =
        storageRoots.child(
            dev.hryshyn.remanence.core.model.UserId(java.util.UUID.fromString(owner)),
            dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT,
        )

    private fun newFileBackedDatabase(name: String): RemanenceLocalDatabase =
        Room.databaseBuilder(context, RemanenceLocalDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    /** Real encryption chain: AEAD artifacts + HPKE envelope over canary payloads. */
    private fun preparedCapsule(
        ownerUserId: String = OWNER,
        capsuleUuid: java.util.UUID = capsuleId,
        blobIdBase: Long = 0L,
    ): PreparedOutboxCapsule {
        val capsuleKeyset = CapsuleKeysetGenerator().generate()
        val identity = AccountIdentityGenerator().generate()
        val recipientPublic = com.google.crypto.tink.TinkProtoKeysetFormat.parseKeysetWithoutSecret(
            identity.encryptionPublicKeyset,
        )
        fun aad(blobId: UUID, kind: CapsuleArtifactKind, ordinal: Int) = ArtifactAadInput(
            capsuleId = CapsuleId(capsuleUuid),
            blobId = BlobId(blobId),
            artifactKind = kind,
            ordinal = ordinal,
            senderUserId = UserId(senderUser),
            recipientUserId = UserId(recipientUser),
        )

        fun blob(n: Int): UUID = UUID.fromString("00000000-0000-4000-8000-%012d".format(blobIdBase + n))

        val cryptor = CapsuleArtifactCryptor()
        val recognition = cryptor.encrypt(
            capsuleKeyset,
            aad(blob(1), CapsuleArtifactKind.RECOGNITION_MANIFEST, -1),
            "$manifestMarker-recognition".toByteArray(Charsets.UTF_8),
        )
        val content = cryptor.encrypt(
            capsuleKeyset,
            aad(blob(2), CapsuleArtifactKind.CONTENT_MANIFEST, -1),
            ("$manifestMarker-content $noteMarker").toByteArray(Charsets.UTF_8),
        )
        val photos = (0 until 3).map { index ->
            val ciphertext = cryptor.encrypt(
                capsuleKeyset,
                aad(blob(10 + index), CapsuleArtifactKind.PHOTO, index),
                "$photoMarker-$index".toByteArray() + ByteArray(64) { it.toByte() },
            )
            PreparedOutboxArtifact(blob(10 + index), OutboxArtifactKind.PHOTO, index, ciphertext)
        }
        val envelopeContext = RecipientEnvelopeContextInput(
            capsuleId = CapsuleId(capsuleUuid),
            senderUserId = UserId(senderUser),
            recipientUserId = UserId(recipientUser),
            recipientKeyBundleId = KeyBundleId(recipientBundle),
        )
        val envelope = RecipientEnvelopeCryptor().seal(
            recipientPublic,
            envelopeContext,
            "envelope-plaintext-with-$noteMarker".toByteArray(Charsets.UTF_8),
        )

        return PreparedOutboxCapsule(
            capsuleId = capsuleUuid,
            idempotencyKey = "idempotency-$capsuleUuid",
            ownerUserId = ownerUserId,
            senderUserId = senderUser,
            recipientUserId = recipientUser,
            senderKeyBundleId = senderBundle,
            recipientKeyBundleId = recipientBundle,
            senderSigningPublicKeysetB64Url = "dGVzdC1wdWJsaWMta2V5c2V0",
            envelopeCiphertext = envelope,
            publishStatementBytes = "signed-statement".toByteArray(),
            publishStatementSignature = ByteArray(69) { 1 },
            artifacts =
            listOf(
                PreparedOutboxArtifact(blob(1), OutboxArtifactKind.RECOGNITION_MANIFEST, -1, recognition),
                PreparedOutboxArtifact(blob(2), OutboxArtifactKind.CONTENT_MANIFEST, -1, content),
            ) + photos,
        )
    }

    @Test
    fun stagesFilesAndRowsAtomicallyWithVerifiableBindings() = runBlocking {
        database = newFileBackedDatabase("stager-happy.db")
        val prepared = preparedCapsule()
        val stager = CapsuleOutboxStager(database, storageRoots)

        val staged = stager.stage(prepared)

        assertEquals(prepared.artifacts.size, staged.artifactPaths.size)
        assertTrue(File(staged.envelopePath).exists())
        staged.artifactPaths.forEach { assertTrue(File(it).exists()) }

        val capsuleRow = database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), OWNER)!!
        assertEquals(OutboxCapsuleState.ENCRYPTED, capsuleRow.state)
        // FIX-REVIEW-04: sender and recipient identities persist SEPARATELY.
        assertEquals(senderUser.toString(), capsuleRow.senderUserId)
        assertEquals(recipientUser.toString(), capsuleRow.recipientUserId)
        assertEquals(senderBundle.toString(), capsuleRow.senderKeyBundleId)
        assertEquals(recipientBundle.toString(), capsuleRow.recipientKeyBundleId)
        assertEquals("dGVzdC1wdWJsaWMta2V5c2V0", capsuleRow.senderSigningPublicKeysetB64)
        // M2-P02: staged rows carry the immutable owning account.
        assertEquals(OWNER, capsuleRow.ownerUserId)
        assertTrue(database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), OWNER).all { it.ownerUserId == OWNER })

        val rows = database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), OWNER)
        assertEquals(prepared.artifacts.size, rows.size)
        rows.forEach { row ->
            assertEquals(OutboxBlobUploadState.PENDING, row.uploadState)
            val bytes = File(row.localCiphertextPath).readBytes()
            assertEquals(row.sizeBytes, bytes.size.toLong())
            assertEquals(
                MessageDigest.getInstance("SHA-256").digest(bytes).toList(),
                row.sha256.toList(),
            )
        }
    }

    @Test
    fun refusesNonCanonicalOwnerAccountIdsBeforeWritingAnything() = runBlocking {
        database = newFileBackedDatabase("stager-owner.db")
        val stager = CapsuleOutboxStager(database, storageRoots)

        for (invalid in listOf("", "not-a-uuid", OWNER.uppercase())) {
            try {
                stager.stage(preparedCapsule(ownerUserId = invalid))
                throw AssertionError("expected refusal for '$invalid'")
            } catch (expected: IllegalArgumentException) {
                assertEquals("owner account id must be a canonical UUID string", expected.message)
            }
            // Nothing may have been written for the refused invocation.
            assertEquals(null, database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), OWNER))
        }
    }

    @Test
    fun localFailureRollsBackRowsAndRemovesEveryFileWithoutPlaintextTraces() = runBlocking {
        database = newFileBackedDatabase("stager-failure.db")
        val prepared = preparedCapsule()
        // Deterministic local failure committed BEFORE staging begins: a
        // directory occupies the last photo's target path, so its rename fails
        // after the envelope and earlier artifacts were already persisted.
        val blockedBlobId = prepared.artifacts.last().blobId
        val blockedTarget = File(outboxRoot(), "$blockedBlobId.bin")
        blockedTarget.mkdirs()

        val stager = CapsuleOutboxStager(database, storageRoots)
        try {
            stager.stage(prepared)
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            // The existing blocker path must be refused BEFORE any overwrite.
            assertEquals("outbox file already present: $blockedBlobId.bin", expected.message)
        }

        // Nothing of this invocation may survive: only the injected blocker
        // directory remains, and neither capsule nor blob rows exist.
        val leftovers = outboxRoot().listFiles().orEmpty()
        assertEquals(listOf("$blockedBlobId.bin"), leftovers.map { it.name })
        assertTrue(leftovers.single().isDirectory)
        assertEquals(null, database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), OWNER))
        assertEquals(
            0,
            database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), OWNER).size,
        )
    }

    @Test
    fun refusesToStageTheSameCapsuleTwiceAndKeepsFirstRecordIntact() = runBlocking {
        database = newFileBackedDatabase("stager-twice.db")
        val stager = CapsuleOutboxStager(database, storageRoots)
        stager.stage(preparedCapsule())

        try {
            stager.stage(preparedCapsule())
            throw AssertionError("expected refusal")
        } catch (expected: IllegalStateException) {
            assertEquals("capsule already staged", expected.message)
        }

        assertEquals(5, database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), OWNER).size)
    }

    /**
     * M2-P03: a second local account can never stage over, observe, or clean
     * up a capsule owned by the first account.
     */
    @Test
    fun anotherOwnerCannotObserveMutateOrReplaceAStagedCapsule() = runBlocking {
        val otherOwner = "0198f0a0-0000-7000-8000-00000000ab02"
        database = newFileBackedDatabase("stager-owner-isolation.db")
        val stager = CapsuleOutboxStager(database, storageRoots)
        stager.stage(preparedCapsule())

        // Staging the SAME capsule id under another owner is refused (the
        // strict insert aborts on the globally unique capsule id) and leaves
        // the winner intact.
        try {
            stager.stage(preparedCapsule(ownerUserId = otherOwner))
            throw AssertionError("expected refusal")
        } catch (_: Exception) {
            // any failure is acceptable; nothing of account B may persist
        }

        assertNull(database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), otherOwner))
        assertTrue(database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), otherOwner).isEmpty())
        assertEquals(5, database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), OWNER).size)
    }

    /**
     * M2 review regression: account B owns the committed capsule FIRST and
     * account A attacks by reusing B's capsule_id. The collision must fail
     * atomically BEFORE any winner row or file changes, and A must afterwards
     * be able to neither overwrite nor delete any of B's material.
     */
    @Test
    fun attackerCannotOverwriteOrDeleteWinnerByReusingItsCapsuleId() = runBlocking {
        val winnerOwner = "0198f0a0-0000-7000-8000-00000000ab02"
        val attackerOwner = OWNER
        database = newFileBackedDatabase("stager-owner-collision.db")
        val stager = CapsuleOutboxStager(database, storageRoots)

        // B commits first, producing random ciphertext only it knows about.
        stager.stage(preparedCapsule(ownerUserId = winnerOwner))

        // Snapshot B exactly as committed.
        val winnerRow =
            database.outboxCapsuleDao()
                .getByCapsuleIdAndOwner(capsuleId.toString(), winnerOwner)!!
        val winnerBlobs =
            database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), winnerOwner)
        val winnerFiles =
            listOfNotNull(
                winnerRow.envelopePath,
                winnerRow.recognitionManifestPath,
                winnerRow.contentManifestPath,
                winnerRow.publishStatementPath,
                winnerRow.publishStatementSignaturePath,
            ).map { File(it) } + winnerBlobs.map { File(it.localCiphertextPath) }
        val winnerBytes = winnerFiles.map { it.readBytes().toList() }

        // A stages the SAME capsule id with fresh ciphertext bytes. The
        // strict insert aborts on the foreign-owned row; A may write inside
        // ITS OWN root during the attempt but every such byte is rolled back
        // out by the invocation-local cleanup afterwards.
        try {
            stager.stage(preparedCapsule(ownerUserId = attackerOwner))
            throw AssertionError("expected collision refusal")
        } catch (_: Exception) {
            // any refusal shape is acceptable; integrity asserts below decide
        }

        // Winner row is untouched, byte-for-byte logically identical.
        assertEquals(
            winnerRow,
            database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), winnerOwner),
        )
        assertEquals(5, database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), winnerOwner).size)
        winnerFiles.forEachIndexed { index, file ->
            assertEquals(winnerBytes[index], file.readBytes().toList())
        }
        // No residue of A exists anywhere.
        assertNull(
            database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), attackerOwner),
        )
        assertTrue(
            database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), attackerOwner).isEmpty(),
        )

        // A cannot mutate or delete B through any owner-required surface.
        assertEquals(
            0,
            database.outboxCapsuleDao().transitionStateForOwner(
                capsuleId.toString(),
                attackerOwner,
                OutboxCapsuleState.PUBLISHED,
                listOf(OutboxCapsuleState.ENCRYPTED),
            ),
        )
        assertEquals(
            0,
            database.outboxBlobDao().deleteByCapsuleIdAndOwner(capsuleId.toString(), attackerOwner),
        )
        assertEquals(
            5,
            database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), winnerOwner).size,
        )

        // Filesystem isolation: B's committed files live only under B's own
        // outbox root; A's refused attempt left NOTHING in its directory.
        val winnerRoot = outboxRoot(winnerOwner)
        assertTrue(winnerFiles.all { it.canonicalFile.path.startsWith(winnerRoot.canonicalFile.path) })
        val attackerRoot = outboxRoot(attackerOwner)
        if (attackerRoot.exists()) {
            assertTrue(attackerRoot.listFiles().orEmpty().isEmpty())
        }
    }

    /**
     * M2-P04 filesystem proof: each local account stages into ITS OWN
     * accounts/<owner>/outbox-ciphertext root, and a failed cleanup-on-write
     * inside one owner's root can never touch another owner's directory.
     */
    @Test
    fun abAccountsStageIntoDisjointDirectoriesAndCleanupStaysInsideOneAccount() = runBlocking {
        database = newFileBackedDatabase("stager-ab-dirs.db")
        val stager = CapsuleOutboxStager(database, storageRoots)
        val bCapsule = UUID.fromString("3c111111-2222-4333-8444-555555555555")
        val otherOwner = "0198f0a0-0000-7000-8000-00000000ab02"

        val stagedA = stager.stage(preparedCapsule())
        val stagedB =
            stager.stage(
                preparedCapsule(
                    ownerUserId = otherOwner,
                    capsuleUuid = bCapsule,
                    blobIdBase = 1_000_000L,
                ),
            )

        // Every produced file resolves strictly within its own owner root.
        val rootA = outboxRoot(OWNER)
        val rootB = outboxRoot(otherOwner)
        assertTrue(rootA.exists() && rootB.exists())
        assertTrue(rootA.canonicalFile != rootB.canonicalFile)
        (listOf(stagedA.envelopePath) + stagedA.artifactPaths).forEach { path ->
            assertTrue(File(path).canonicalPath.startsWith(rootA.canonicalPath))
        }
        (listOf(stagedB.envelopePath) + stagedB.artifactPaths).forEach { path ->
            assertTrue(File(path).canonicalPath.startsWith(rootB.canonicalPath))
        }
        assertEquals(stagedA.artifactPaths.size + 3, rootA.listFiles().size)
        assertEquals(stagedB.artifactPaths.size + 3, rootB.listFiles().size)

        // Cleanup of a failing staging in A's root leaves B's bytes intact.
        val bSnapshot: List<Pair<String, Long>> =
            (listOf(stagedB.envelopePath) + stagedB.artifactPaths)
                .map { it to File(it).length() }
        val blockedId = preparedCapsule(blobIdBase = 2_000_000L).artifacts.last().blobId
        File(rootA, "$blockedId.bin").mkdirs()
        try {
            stager.stage(preparedCapsule(capsuleUuid = UUID.fromString("4c222222-3333-4444-8555-666666666666"), blobIdBase = 2_000_000L))
            throw AssertionError("expected collision refusal")
        } catch (_: IllegalStateException) {
            // deterministic refusal on the pre-blocked target path
        }
        // The blocker target survived, all in-flight files were cleaned,
        // and every previously committed byte - in BOTH accounts - is intact.
        assertTrue(File(rootA, "$blockedId.bin").isDirectory)
        rootA.listFiles().orEmpty().forEach { file ->
            assertFalse("temp residue must not survive", file.name.contains(".tmp-"))
        }
        (listOf(stagedA.envelopePath) + stagedA.artifactPaths).forEach { path ->
            assertTrue("committed A file must survive the failing sibling staging", File(path).exists())
        }
        bSnapshot.forEach { (path, length) ->
            val file = File(path)
            assertTrue(file.exists() && file.length() == length)
        }
    }

    @Test
    fun replayedStagingCannotOverwriteOrDeleteTheWinner() = runBlocking {
        database = newFileBackedDatabase("stager-replay.db")
        val stager = CapsuleOutboxStager(database, storageRoots)
        stager.stage(preparedCapsule())

        // Snapshot the winner exactly as committed. Each preparedCapsule()
        // call produces fresh random ciphertext, so any overwrite would be
        // observable byte-for-byte.
        val winnerRow = database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), OWNER)!!
        val winnerBlobs = database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), OWNER)
        val winnerFiles = listOfNotNull(
            winnerRow.envelopePath,
            winnerRow.recognitionManifestPath,
            winnerRow.contentManifestPath,
            winnerRow.publishStatementPath,
            winnerRow.publishStatementSignaturePath,
        ).map { File(it) } + winnerBlobs.map { File(it.localCiphertextPath) }
        val winnerBytes = winnerFiles.map { it.readBytes().toList() }

        try {
            stager.stage(preparedCapsule())
            throw AssertionError("expected replay refusal")
        } catch (expected: IllegalStateException) {
            assertEquals("capsule already staged", expected.message)
        }

        val afterRow = database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), OWNER)!!
        assertEquals(winnerRow, afterRow)
        assertEquals(5, database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), OWNER).size)
        winnerFiles.forEachIndexed { index, file ->
            assertTrue("winner file ${file.name} must survive replay", file.exists())
            assertEquals(winnerBytes[index], file.readBytes().toList())
        }
    }

    /**
     * M2-P04 review fix: when the account OUTBOX_CIPHERTEXT root cannot be
     * created (its owner directory is occupied by a plain file), staging
     * fails explicitly BEFORE writing anything - no Room row, no blob row,
     * no filesystem residue, and another account's root stays untouched.
     */
    @Test
    fun ownerSlotOccupiedByFileRefusesStagingBeforeAnyWrite() = runBlocking {
        database = newFileBackedDatabase("stager-root-blocked-file.db")
        val otherOwner = "0198f0a0-0000-7000-8000-00000000ab02"
        // Occupy the owner's directory slot with a plain FILE.
        val blockedOwnerSlot = File(context.filesDir, "accounts/$OWNER")
        blockedOwnerSlot.parentFile!!.mkdirs()
        assertTrue(blockedOwnerSlot.createNewFile())

        val stager = CapsuleOutboxStager(database, storageRoots)
        try {
            stager.stage(preparedCapsule())
            throw AssertionError("expected refusal")
        } catch (expected: IllegalStateException) {
            assertEquals("cannot prepare outbox ciphertext root for this account", expected.message)
        }

        // Refused atomically: Room knows nothing, filesystem was not touched
        // beneath the boundary, and NO residue exists anywhere in accounts/.
        assertNull(database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), OWNER))
        assertTrue(database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), OWNER).isEmpty())
        assertEquals(0, countOutboxCapsuleRows())
        assertFalse(blockedOwnerSlot.isDirectory)

        // Another account keeps working normally beside the blocked one.
        val stagedForOther =
            stager.stage(preparedCapsule(ownerUserId = otherOwner, capsuleUuid = UUID.fromString("3c111111-2222-4333-8444-555555555555"), blobIdBase = 5_000_000L))
        assertTrue(File(stagedForOther.envelopePath).exists())
        assertNotNull(
            database.outboxCapsuleDao()
                .getByCapsuleIdAndOwner("3c111111-2222-4333-8444-555555555555", otherOwner),
        )
    }

    /**
     * M2-P04 review fix: an existing NON-DIRECTORY at the resolved
     * outbox-ciphertext path refuses staging fail-closed before any write;
     * Room stays empty and the hostile entry itself is left unmodified so
     * ownership never silently migrates onto it.
     */
    @Test
    fun nonDirectoryOutboxRootRefusesStagingAndPreservesOtherAccounts() = runBlocking {
        database = newFileBackedDatabase("stager-root-nondir.db")
        // A plain regular FILE occupies the root path itself.
        val ownerDir = File(context.filesDir, "accounts/$OWNER")
        val fileOccupant =
            File(ownerDir, "outbox-ciphertext").apply {
                ownerDir.mkdirs()
                writeText("not a directory")
            }
        val occupantBytes = fileOccupant.readBytes()
        assertFalse(fileOccupant.isDirectory)

        val otherOwner = "0198f0a0-0000-7000-8000-00000000ab02"
        val bSeed = File(outboxRoot(otherOwner), "seed.bin")
            .apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(7, 7, 7)) }

        val stager = CapsuleOutboxStager(database, storageRoots)
        try {
            stager.stage(preparedCapsule())
            throw AssertionError("expected refusal")
        } catch (expected: IllegalStateException) {
            assertEquals("cannot prepare outbox ciphertext root for this account", expected.message)
        }

        assertNull(database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), OWNER))
        assertEquals(0, countOutboxCapsuleRows())
        // The occupant is untouched byte-for-byte; nothing was written around it.
        assertTrue(fileOccupant.readBytes().contentEquals(occupantBytes))
        assertTrue(fileOccupant.isFile)
        // The other account's root data survives byte-for-byte too.
        assertTrue(bSeed.readBytes().contentEquals(byteArrayOf(7, 7, 7)))
    }

    private fun countOutboxCapsuleRows(): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox_capsule").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @Test
    fun concurrentStagingOfSameCapsuleProducesExactlyOneWinner() = runBlocking {
        database = newFileBackedDatabase("stager-concurrent.db")
        val stager = CapsuleOutboxStager(database, storageRoots)

        val outcomes = kotlinx.coroutines.coroutineScope {
            val first = this@coroutineScope.async { runCatching { stager.stage(preparedCapsule()) } }
            val second = this@coroutineScope.async { runCatching { stager.stage(preparedCapsule()) } }
            listOf(first.await(), second.await())
        }

        val winners = outcomes.filter { it.isSuccess }
        val losers = outcomes.filter { it.isFailure }
        assertEquals(1, winners.size)
        assertEquals(1, losers.size)
        assertTrue(losers.single().exceptionOrNull() is IllegalStateException)

        val row = database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), OWNER)!!
        assertEquals(OutboxCapsuleState.ENCRYPTED, row.state)
        assertEquals(5, database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), OWNER).size)
        // Every committed path exists with content; no temp residue remains.
        val committedPaths =
            listOfNotNull(row.envelopePath, row.publishStatementPath, row.publishStatementSignaturePath) +
                database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), OWNER).map { it.localCiphertextPath }
        committedPaths.forEach { assertTrue(File(it).exists() && File(it).length() > 0L) }
        assertTrue(outboxRoot().listFiles()!!.none { it.name.contains(".tmp-") })
    }

    @Test
    fun stagingCompletesOnlyThroughGuardedPreparingToEncryptedTransition() = runBlocking {
        database = newFileBackedDatabase("stager-transition.db")
        CapsuleOutboxStager(database, storageRoots).stage(preparedCapsule())

        // A completed staging is ENCRYPTED — never stranded in PREPARING.
        assertEquals(
            OutboxCapsuleState.ENCRYPTED,
            database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), OWNER)!!.state,
        )
        // The guarded transition only accepts PREPARING as origin.
        assertEquals(
            0,
            database.outboxCapsuleDao().transitionStateForOwner(
                capsuleId.toString(),
                OWNER,
                OutboxCapsuleState.ENCRYPTED,
                listOf(OutboxCapsuleState.PREPARING),
            ),
        )
    }

    @Test
    fun statementAndSignatureSurviveRoomRestartByteIdentically() = runBlocking {
        val dbName = "stager-restart.db"
        database = newFileBackedDatabase(dbName)
        val prepared = preparedCapsule()
        CapsuleOutboxStager(database, storageRoots).stage(prepared)

        // ---- process death ----
        val stagedStatementBytes = prepared.publishStatementBytes
        val stagedSignatureBytes = prepared.publishStatementSignature
        database.close()

        // ---- second process reads the durable outbox from disk only ----
        database = newFileBackedDatabase(dbName)
        val reopened = database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), OWNER)
        assertNotNull(reopened)
        assertNotNull(reopened!!.publishStatementPath)
        assertNotNull(reopened.publishStatementSignaturePath)
        assertTrue(stagedStatementBytes.contentEquals(File(reopened.publishStatementPath!!).readBytes()))
        assertTrue(stagedSignatureBytes.contentEquals(File(reopened.publishStatementSignaturePath!!).readBytes()))
    }

    @Test
    fun plaintextCanaryAcrossEveryProducedByteFindsNothing() = runBlocking {
        val dbName = "canary.db"
        database = newFileBackedDatabase(dbName)
        val stager = CapsuleOutboxStager(database, storageRoots)

        stager.stage(preparedCapsule())

        val markers = listOf(noteMarker, photoMarker, manifestMarker).map { it.toByteArray(Charsets.UTF_8) }
        val dbDir = context.getDatabasePath(dbName).parentFile
        // Flush WAL content into every file we are about to scan.
        val checkpoint = database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
        checkpoint.moveToFirst()
        checkpoint.close()
        val stagedFiles: MutableList<File> = outboxRoot().listFiles()?.toMutableList() ?: mutableListOf()
        dbDir?.listFiles()?.filterTo(stagedFiles) { it.name.startsWith(dbName) }
        assertTrue("canary must scan produced bytes", stagedFiles.isNotEmpty())
        stagedFiles.filter(File::isFile).forEach { file ->
            val bytes = file.readBytes()
            markers.forEach { marker ->
                assertTrue(
                    "plaintext canary found in ${file.name}",
                    indexOf(bytes, marker) < 0,
                )
            }
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
