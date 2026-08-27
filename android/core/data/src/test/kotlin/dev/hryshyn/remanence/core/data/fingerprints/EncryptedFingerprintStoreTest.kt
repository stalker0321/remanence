package dev.hryshyn.remanence.core.data.fingerprints

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.FingerprintSide
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.db.RecognitionFingerprintDao
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.UserId

/** XOR sealer: reversible, and detects tampering via the AAD check below. */
private class XorSealer(private val failOnTamperedAad: Boolean = false) : SecretSealer {
    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        if (failOnTamperedAad && aad.isEmpty()) throw IllegalArgumentException("bad aad")
        return ByteArray(plaintext.size) { (plaintext[it].toInt() xor 0x5A).toByte() }
    }

    override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray =
        ByteArray(ciphertext.size) { (ciphertext[it].toInt() xor 0x5A).toByte() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedFingerprintStoreTest {

    private companion object {
        val OWNER_ID = UserId(UUID.fromString("5108f0a0-0000-7000-8000-00000000aa01"))
    }

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var roots: AccountScopedFileRoots
    private lateinit var appFilesDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        appFilesDir = context.filesDir
        roots = AccountScopedFileRoots(appFilesDir)
    }

    @After
    fun tearDown() {
        database.close()
        File(appFilesDir, "accounts").deleteRecursively()
    }

    private val ownerFingerprintRoot: File
        get() = roots.child(OWNER_ID, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)

    /** The shared legacy root must never be created or read. */
    private val sharedLegacyRoot: File
        get() = File(appFilesDir, "fingerprints")

    private fun store(sealer: SecretSealer = XorSealer()) =
        EncryptedFingerprintStore(
            roots = roots,
            sealer = sealer,
            dao = database.recognitionFingerprintDao(),
            ownerUserIdProvider = { OWNER_ID.toRestString() },
        )

    @Test
    fun persistEncryptsAtRestAndDecryptsRoundtrip() = runBlocking {
        val sut = store()
        val plaintext = "fingerprint-bytes-π".toByteArray()

        val id = sut.persist("capsule-a", FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", plaintext)

        val entity = database.recognitionFingerprintDao().getByFingerprintIdAndOwner(id, OWNER_ID.toRestString())!!
        val onDisk = File(ownerFingerprintRoot, entity.encryptedPath).readBytes()
        assertFalse("plaintext must never reach disk", onDisk.contentEquals(plaintext))
        assertTrue(entity.encryptedPath.endsWith(".fpw"))

        assertArrayEquals(plaintext, sut.decrypt(id))
        assertFalse("no shared fingerprint root may exist", sharedLegacyRoot.exists())
    }

    @Test
    fun duplicateBaselineIsRejectedBeforeAnyWrite() = runBlocking {
        val sut = store()
        val firstId = sut.persist("capsule-a", FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", byteArrayOf(1))

        try {
            sut.persist("capsule-a", FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", byteArrayOf(2))
            throw AssertionError("expected duplicate rejection")
        } catch (expected: DuplicateFingerprintException) {
            // correct
        }

        assertEquals(1, ownerFingerprintRoot.listFiles()!!.size)
        assertArrayEquals(byteArrayOf(1), sut.decrypt(firstId))
    }

    @Test
    fun failedInsertRemovesItsCiphertextFileOnly() = runBlocking {
        val dao = database.recognitionFingerprintDao()
        val explodingDao = object : RecognitionFingerprintDao by dao {
            override suspend fun insertAll(fingerprints: List<dev.hryshyn.remanence.core.data.db.RecognitionFingerprintEntity>) {
                throw IllegalStateException("database exploded")
            }
        }
        val sut = EncryptedFingerprintStore(
            roots,
            XorSealer(),
            explodingDao,
            ownerUserIdProvider = { OWNER_ID.toRestString() },
        )

        try {
            sut.persist("capsule-a", FingerprintSide.BACK, FingerprintOrigin.SENDER, "mvp-orb-v1", byteArrayOf(9))
            throw AssertionError("expected insert failure")
        } catch (expected: IllegalStateException) {
            assertEquals("database exploded", expected.message)
        }
        assertEquals(0, ownerFingerprintRoot.listFiles()!!.size)
    }

    @Test
    fun hasBaselineReflectsOnlyPersistedSides() = runBlocking {
        val sut = store()
        assertFalse(sut.hasBaseline("capsule-c", FingerprintSide.FRONT, FingerprintOrigin.SENDER))

        sut.persist("capsule-c", FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", byteArrayOf(1))

        assertTrue(sut.hasBaseline("capsule-c", FingerprintSide.FRONT, FingerprintOrigin.SENDER))
        assertFalse(sut.hasBaseline("capsule-c", FingerprintSide.BACK, FingerprintOrigin.SENDER))
        assertFalse(sut.hasBaseline("capsule-other", FingerprintSide.FRONT, FingerprintOrigin.SENDER))
    }

    @Test
    fun missingRecordAndMissingFileFailClosed() = runBlocking {
        val sut = store()
        try {
            sut.decrypt("does-not-exist")
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            // correct
        }

        val id = sut.persist("capsule-b", FingerprintSide.BACK, FingerprintOrigin.SENDER, "mvp-orb-v1", byteArrayOf(3))
        database.recognitionFingerprintDao().deleteByCapsuleIdAndOwner("capsule-b", OWNER_ID.toRestString())
        try {
            sut.decrypt(id)
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            // correct
        }
    }

    @Test
    fun ownerChangeDuringCallCannotMixAccountsIntoOnePersist() = runBlocking {
        val otherId = UserId(UUID.fromString("5108f0a0-0000-7000-8000-00000000bb02"))
        val ownerProviderValue = java.util.concurrent.atomic.AtomicReference(OWNER_ID.toRestString())

        // Flip the provider value AFTER the snapshot has been taken but
        // BEFORE the insert records the row: the captured snapshot of the
        // original owner must win for both the file root and the row.
        val swapDuringInsert =
            object : RecognitionFingerprintDao by database.recognitionFingerprintDao() {
                override suspend fun insertAll(fingerprints: List<dev.hryshyn.remanence.core.data.db.RecognitionFingerprintEntity>) {
                    ownerProviderValue.set(otherId.toRestString())
                    database.recognitionFingerprintDao().insertAll(fingerprints)
                }
            }
        val swappingStore = EncryptedFingerprintStore(
            roots,
            XorSealer(),
            swapDuringInsert,
            ownerUserIdProvider = { ownerProviderValue.get() },
        )

        val id = swappingStore.persist(
            "capsule-swap", FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", byteArrayOf(7),
        )

        // Everything landed under the ORIGINAL owner only.
        val rows = database.recognitionFingerprintDao().getAllForOwner(OWNER_ID.toRestString())
        assertEquals(1, rows.size)
        assertEquals(id, rows.single().fingerprintId)
        assertTrue(File(ownerFingerprintRoot, rows.single().encryptedPath).exists())

        assertTrue(database.recognitionFingerprintDao().getAllForOwner(otherId.toRestString()).isEmpty())
    }
}
