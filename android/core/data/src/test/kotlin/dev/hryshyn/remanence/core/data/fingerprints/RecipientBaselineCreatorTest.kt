package dev.hryshyn.remanence.core.data.fingerprints

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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


private class XorSealerForRecipient : SecretSealer {
    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray =
        ByteArray(plaintext.size) { (plaintext[it].toInt() xor 0x5A).toByte() }

    override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray =
        ByteArray(ciphertext.size) { (ciphertext[it].toInt() xor 0x5A).toByte() }
}

/** Origin/preferred persistence proof for M1-M16. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipientBaselineCreatorTest {

    private companion object {
        const val OWNER = "5108f0a0-0000-7000-8000-00000000aa01"
    }

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var filesRoot: File
    private lateinit var roots: AccountScopedFileRoots
    private lateinit var creator: RecipientBaselineCreator

    private val capsuleId = "3c111111-2222-4333-8444-555555555555"

    private fun front() = ReceivedSideCapture(
        profileId = "mvp-orb-v1",
        side = FingerprintSide.FRONT,
        serializedBytes = "fp-FRONT".toByteArray(),
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesRoot = File(context.filesDir, "recipient-baseline-test")
        filesRoot.mkdirs()
        roots = AccountScopedFileRoots(filesRoot)
        creator = RecipientBaselineCreator(
            EncryptedFingerprintStore(roots, XorSealerForRecipient(), database.recognitionFingerprintDao(), ownerUserIdProvider = { "5108f0a0-0000-7000-8000-00000000aa01" }),
        )
    }

    @After
    fun tearDown() {
        database.close()
        filesRoot.deleteRecursively()
    }

    private suspend fun seedSenderFront(preferred: Boolean) {
        val store = EncryptedFingerprintStore(roots, XorSealerForRecipient(), database.recognitionFingerprintDao(), ownerUserIdProvider = { "5108f0a0-0000-7000-8000-00000000aa01" })
        store.persist(capsuleId, FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", "sender-front".toByteArray())
        if (preferred) {
            store.setPreferredPair(capsuleId, FingerprintOrigin.SENDER)
        }
    }

    @Test
    fun verifiedReceiptCreatesPreferredRecipientFrontAndDemotesSenderFallback() = runBlocking {
        seedSenderFront(preferred = true)

        creator.createAfterVerifiedReceipt(capsuleId, front())

        val rows = database.recognitionFingerprintDao().getAllByCapsuleIdAndOwner(capsuleId, OWNER)
        assertEquals(2, rows.size)
        val recipientRows = rows.filter { it.origin == FingerprintOrigin.RECIPIENT }
        assertEquals(setOf(FingerprintSide.FRONT), recipientRows.map { it.side }.toSet())
        assertTrue(recipientRows.all { it.preferred })
        // Sender row survives untouched as the fallback, no longer preferred.
        val senderRows = rows.filter { it.origin == FingerprintOrigin.SENDER }
        assertTrue(senderRows.all { !it.preferred })

        // The recipient baseline is sealed and round-trips.
        val frontRow = recipientRows.single { it.side == FingerprintSide.FRONT }
        val store = EncryptedFingerprintStore(roots, XorSealerForRecipient(), database.recognitionFingerprintDao(), ownerUserIdProvider = { "5108f0a0-0000-7000-8000-00000000aa01" })
        assertEquals("fp-FRONT", String(store.decrypt(frontRow.fingerprintId)))
    }

    @Test
    fun initialRecipientBaselineIsImmutable() = runBlocking {
        creator.createAfterVerifiedReceipt(capsuleId, front())

        try {
            creator.createAfterVerifiedReceipt(capsuleId, front())
            throw AssertionError("expected immutability rejection")
        } catch (expected: ImmutableBaselineException) {
            assertEquals(
                "recipient baseline for capsule $capsuleId already exists; it is immutable",
                expected.message,
            )
        }

        // Still exactly one row after the refused attempt.
        val rows = database.recognitionFingerprintDao().getAllByCapsuleIdAndOwner(capsuleId, OWNER)
            .filter { it.origin == FingerprintOrigin.RECIPIENT }
        assertEquals(1, rows.size)
    }
}
