package dev.hryshyn.remanence.core.data.fingerprints

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.FingerprintSide
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots

/** XOR sealer like the store tests: reversible bytes, no real crypto needed here. */
private class CandidateIsolationXorSealer : SecretSealer {
    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray =
        ByteArray(plaintext.size) { (plaintext[it].toInt() xor 0x5A).toByte() }

    override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray =
        ByteArray(ciphertext.size) { (ciphertext[it].toInt() xor 0x5A).toByte() }
}

/**
 * M2-P03 regression: the recognition candidate index is ACCOUNT-scoped.
 * Baselines persisted while local account A is authenticated survive logout
 * (same-account retention), but after login as account B the index source,
 * the per-record lookups, and every preferred/delete transition expose ZERO
 * of A's recognition candidates; only a re-login as A resolves them again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionCandidateIsolationTest {

    private val ownerA = "5108f0a0-0000-7000-8000-00000000aa01"
    private val ownerAId = dev.hryshyn.remanence.core.model.UserId(java.util.UUID.fromString(ownerA))
    private val ownerB = "5108f0a0-0000-7000-8000-00000000bb02"
    private val capsuleId = "0198f0a0-0000-7000-8000-00000000ca01"
    private val profileId = "mvp-orb-v1"

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var filesRoot: File
    private lateinit var roots: AccountScopedFileRoots

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesRoot = File(context.filesDir, "fingerprints-candidate-isolation")
        filesRoot.mkdirs()
        roots = AccountScopedFileRoots(filesRoot)
    }

    @After
    fun tearDown() {
        database.close()
        filesRoot.deleteRecursively()
    }

    private fun store(ownerUserId: String) =
        EncryptedFingerprintStore(
            roots = roots,
            sealer = CandidateIsolationXorSealer(),
            dao = database.recognitionFingerprintDao(),
            ownerUserIdProvider = { ownerUserId },
        )

    private fun bytesOf(seed: Byte) = ByteArray(64) { seed }

    @Test
    fun logoutAThenLoginBExposesZeroACandidatesAndNothingOfAIsMutable() = runBlocking {
        // --- logged in as A: sender baseline is sealed and indexed ---
        val storeA = store(ownerA)
        val frontId = storeA.persist(
            capsuleId, FingerprintSide.FRONT, FingerprintOrigin.SENDER, profileId, bytesOf(1),
        )
        // The index source itself has A's candidates before logout:
        assertEquals(1, database.recognitionFingerprintDao().getAllForOwner(ownerA).size)

        // --- LOGOUT A (rows/files retained for same account), LOGIN B ---
        val storeB = store(ownerB)

        // ZERO A candidates in B's scan index.
        assertTrue(database.recognitionFingerprintDao().getAllForOwner(ownerB).isEmpty())

        // Per-record resolution and decryption fail closed for B.
        assertNull(database.recognitionFingerprintDao().getByFingerprintIdAndOwner(frontId, ownerB))
        assertThrows(IllegalStateException::class.java) { runBlocking { storeB.decrypt(frontId) } }

        // No baseline visibility and no preferred-pair influence on A's rows.
        assertFalse(storeB.hasBaseline(capsuleId, FingerprintSide.FRONT, FingerprintOrigin.SENDER))
        storeB.setPreferredPair(capsuleId, FingerprintOrigin.SENDER)
        assertEquals(
            0,
            database.recognitionFingerprintDao().getAllByCapsuleIdAndOwner(capsuleId, ownerA)
                .count { it.preferred },
        )

        // Deletion is owner-guarded: B cannot remove A's baseline or file.
        assertEquals(
            0,
            database.recognitionFingerprintDao().deleteByFingerprintIdAndOwner(frontId, ownerB),
        )
        storeB.deleteFileOf(frontId) // silently inert: record not owned by B
        assertTrue(roots.child(ownerAId, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)
            .resolve(storeARelativePathOrThrow(frontId)).exists())

        // A's capsule identity stays globally unique - B cannot claim it either.
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                storeB.persist(
                    capsuleId, FingerprintSide.FRONT, FingerprintOrigin.SENDER, profileId, bytesOf(9),
                )
            }
        }

        // --- re-login as A: everything still resolves exactly ---
        assertArrayEquals(bytesOf(1), storeA.decrypt(frontId))
        assertEquals(
            1,
            database.recognitionFingerprintDao().getAllForOwner(ownerA).size,
        )
    }

    /** Resolves A's stored relative path through its owned row for file assertions. */
    private suspend fun storeARelativePathOrThrow(fingerprintId: String): String =
        requireNotNull(
            database.recognitionFingerprintDao().getByFingerprintIdAndOwner(fingerprintId, ownerA),
        ).encryptedPath
}
