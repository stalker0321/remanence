package postmark.core.data.outbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import postmark.core.crypto.AccountIdentityGenerator
import postmark.core.crypto.CapsuleArtifactCryptor
import postmark.core.crypto.CapsuleKeysetGenerator
import postmark.core.crypto.RecipientEnvelopeCryptor
import postmark.core.crypto.TinkPrimitives
import postmark.core.data.db.OutboxBlobUploadState
import postmark.core.data.db.OutboxCapsuleState
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.model.ArtifactAadInput
import postmark.core.model.BlobId
import postmark.core.model.CapsuleArtifactKind
import postmark.core.model.CapsuleId
import postmark.core.model.KeyBundleId
import postmark.core.model.RecipientEnvelopeContextInput
import postmark.core.model.UserId

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

    private val noteMarker = "postmark-canary-NOTE-plaintext-Ω"
    private val photoMarker = "postmark-canary-JPEG-plaintext-bytes"
    private val manifestMarker = "postmark-canary-MANIFEST-plaintext"

    private lateinit var context: Context
    private lateinit var database: PostmarkLocalDatabase
    private lateinit var ciphertextDirectory: File

    private val capsuleId = UUID.fromString("2b111111-2222-4333-8444-555555555555")
    private val recipientUser = UUID.fromString("2b222222-3333-4444-8555-666666666666")
    private val senderUser = UUID.fromString("2b333333-4444-4555-8666-777777777777")
    private val recipientBundle = UUID.fromString("2b444444-5555-4666-8777-888888888888")
    private val senderBundle = UUID.fromString("2b555555-6666-4777-8888-999999999999")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TinkPrimitives.ensureRegistered()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun newFileBackedDatabase(name: String): PostmarkLocalDatabase =
        Room.databaseBuilder(context, PostmarkLocalDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    /** Real encryption chain: AEAD artifacts + HPKE envelope over canary payloads. */
    private fun preparedCapsule(): PreparedOutboxCapsule {
        val capsuleKeyset = CapsuleKeysetGenerator().generate()
        val identity = AccountIdentityGenerator().generate()
        val recipientPublic = com.google.crypto.tink.TinkProtoKeysetFormat.parseKeysetWithoutSecret(
            identity.encryptionPublicKeyset,
        )
        fun aad(blobId: UUID, kind: CapsuleArtifactKind, ordinal: Int) = ArtifactAadInput(
            capsuleId = CapsuleId(capsuleId),
            blobId = BlobId(blobId),
            artifactKind = kind,
            ordinal = ordinal,
            senderUserId = UserId(senderUser),
            recipientUserId = UserId(recipientUser),
        )

        fun blob(n: Int): UUID = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

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
            capsuleId = CapsuleId(capsuleId),
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
            capsuleId = capsuleId,
            idempotencyKey = "idempotency-$capsuleId",
            recipientUserId = recipientUser,
            recipientKeyBundleId = recipientBundle,
            envelopeCiphertext = envelope,
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
        ciphertextDirectory = File(context.filesDir, "outbox-staging-ok")
        val prepared = preparedCapsule()
        val stager = CapsuleOutboxStager(database, ciphertextDirectory)

        val staged = stager.stage(prepared)

        assertEquals(prepared.artifacts.size, staged.artifactPaths.size)
        assertTrue(File(staged.envelopePath).exists())
        staged.artifactPaths.forEach { assertTrue(File(it).exists()) }

        val capsuleRow = database.outboxCapsuleDao().getByCapsuleId(capsuleId.toString())!!
        assertEquals(OutboxCapsuleState.ENCRYPTED, capsuleRow.state)
        assertEquals(recipientUser.toString(), capsuleRow.recipientUserId)

        val rows = database.outboxBlobDao().getAllByCapsuleId(capsuleId.toString())
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
    fun localFailureRollsBackRowsAndRemovesEveryFileWithoutPlaintextTraces() = runBlocking {
        database = newFileBackedDatabase("stager-failure.db")
        ciphertextDirectory = File(context.filesDir, "outbox-staging-conflict")
        val prepared = preparedCapsule()
        // Deterministic local failure committed BEFORE staging begins: a
        // directory occupies the last photo's target path, so its rename fails
        // after the envelope and earlier artifacts were already persisted.
        val blockedBlobId = prepared.artifacts.last().blobId
        assertTrue(File(ciphertextDirectory, "$blockedBlobId.bin").mkdirs())

        val stager = CapsuleOutboxStager(database, ciphertextDirectory)
        try {
            stager.stage(prepared)
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("could not persist $blockedBlobId.bin", expected.message)
        }

        // Nothing of this invocation may survive: only the injected blocker
        // directory remains, and neither capsule nor blob rows exist.
        val leftovers = ciphertextDirectory.listFiles().orEmpty()
        assertEquals(listOf("$blockedBlobId.bin"), leftovers.map { it.name })
        assertTrue(leftovers.single().isDirectory)
        assertEquals(null, database.outboxCapsuleDao().getByCapsuleId(capsuleId.toString()))
        assertEquals(
            0,
            database.outboxBlobDao().getAllByCapsuleId(capsuleId.toString()).size,
        )
    }

    @Test
    fun refusesToStageTheSameCapsuleTwiceAndKeepsFirstRecordIntact() = runBlocking {
        database = newFileBackedDatabase("stager-twice.db")
        ciphertextDirectory = File(context.filesDir, "outbox-staging-twice")
        val stager = CapsuleOutboxStager(database, ciphertextDirectory)
        stager.stage(preparedCapsule())

        try {
            stager.stage(preparedCapsule())
            throw AssertionError("expected refusal")
        } catch (expected: IllegalStateException) {
            assertEquals("capsule already staged", expected.message)
        }

        assertEquals(5, database.outboxBlobDao().getAllByCapsuleId(capsuleId.toString()).size)
    }

    @Test
    fun plaintextCanaryAcrossEveryProducedByteFindsNothing() = runBlocking {
        val dbName = "canary.db"
        database = newFileBackedDatabase(dbName)
        ciphertextDirectory = File(context.filesDir, "outbox-canary")
        val stager = CapsuleOutboxStager(database, ciphertextDirectory)

        stager.stage(preparedCapsule())

        val markers = listOf(noteMarker, photoMarker, manifestMarker).map { it.toByteArray(Charsets.UTF_8) }
        val dbDir = context.getDatabasePath(dbName).parentFile
        // Flush WAL content into every file we are about to scan.
        val checkpoint = database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
        checkpoint.moveToFirst()
        checkpoint.close()
        val stagedFiles: MutableList<File> = ciphertextDirectory.listFiles()?.toMutableList() ?: mutableListOf()
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
