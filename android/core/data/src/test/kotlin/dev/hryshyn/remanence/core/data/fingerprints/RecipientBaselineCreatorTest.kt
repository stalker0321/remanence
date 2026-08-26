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

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var filesRoot: File
    private lateinit var creator: RecipientBaselineCreator

    private val capsuleId = "3c111111-2222-4333-8444-555555555555"

    private fun side(side: FingerprintSide) = ReceivedSideCapture(
        profileId = "mvp-orb-v1",
        side = side,
        serializedBytes = "fp-${side.name}".toByteArray(),
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesRoot = File(context.filesDir, "recipient-baseline-test")
        filesRoot.mkdirs()
        creator = RecipientBaselineCreator(
            EncryptedFingerprintStore(filesRoot, XorSealerForRecipient(), database.recognitionFingerprintDao(), ownerUserIdProvider = { "0198f0a0-0000-7000-8000-00000000ow01" }),
        )
    }

    @After
    fun tearDown() {
        database.close()
        filesRoot.deleteRecursively()
    }

    private suspend fun seedSenderPair(preferred: Boolean) {
        val store = EncryptedFingerprintStore(filesRoot, XorSealerForRecipient(), database.recognitionFingerprintDao(), ownerUserIdProvider = { "0198f0a0-0000-7000-8000-00000000ow01" })
        store.persist(capsuleId, FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", "sender-front".toByteArray())
        store.persist(capsuleId, FingerprintSide.BACK, FingerprintOrigin.SENDER, "mvp-orb-v1", "sender-back".toByteArray())
        if (preferred) {
            store.setPreferredPair(capsuleId, FingerprintOrigin.SENDER)
        }
    }

    @Test
    fun verifiedReceiptCreatesPreferredRecipientPairAndDemotesSenderFallback() = runBlocking {
        seedSenderPair(preferred = true)

        creator.createAfterVerifiedReceipt(capsuleId, side(FingerprintSide.FRONT), side(FingerprintSide.BACK))

        val rows = database.recognitionFingerprintDao().getAllByCapsuleId(capsuleId)
        assertEquals(4, rows.size)
        val recipientRows = rows.filter { it.origin == FingerprintOrigin.RECIPIENT }
        assertEquals(setOf(FingerprintSide.FRONT, FingerprintSide.BACK), recipientRows.map { it.side }.toSet())
        assertTrue(recipientRows.all { it.preferred })
        // Sender pair survives untouched as the fallback, no longer preferred.
        val senderRows = rows.filter { it.origin == FingerprintOrigin.SENDER }
        assertTrue(senderRows.all { !it.preferred })

        // The recipient baseline is sealed and round-trips.
        val frontRow = recipientRows.single { it.side == FingerprintSide.FRONT }
        val store = EncryptedFingerprintStore(filesRoot, XorSealerForRecipient(), database.recognitionFingerprintDao(), ownerUserIdProvider = { "0198f0a0-0000-7000-8000-00000000ow01" })
        assertEquals("fp-FRONT", String(store.decrypt(frontRow.fingerprintId)))
    }

    @Test
    fun initialRecipientBaselineIsImmutable() = runBlocking {
        creator.createAfterVerifiedReceipt(capsuleId, side(FingerprintSide.FRONT), side(FingerprintSide.BACK))

        try {
            creator.createAfterVerifiedReceipt(capsuleId, side(FingerprintSide.FRONT), side(FingerprintSide.BACK))
            throw AssertionError("expected immutability rejection")
        } catch (expected: ImmutableBaselineException) {
            assertEquals(
                "recipient baseline for capsule $capsuleId already exists; it is immutable",
                expected.message,
            )
        }

        // Still exactly one pair after the refused attempt.
        val rows = database.recognitionFingerprintDao().getAllByCapsuleId(capsuleId)
            .filter { it.origin == FingerprintOrigin.RECIPIENT }
        assertEquals(2, rows.size)
    }

    @Test
    fun failedBackPersistenceRollsThePairBackInsteadOfLeavingHalfABaseline() = runBlocking {
        val failingStore = EncryptedFingerprintStore(
            File(filesRoot, "unwritable-root").apply { mkdirs() },
            object : SecretSealer {
                override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray =
                    ByteArray(plaintext.size)
                override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray =
                    ByteArray(ciphertext.size)
            },
            database.recognitionFingerprintDao(),
            ownerUserIdProvider = { "0198f0a0-0000-7000-8000-00000000ow01" },
        )
        var attempts = 0
        val flakyCreator = RecipientBaselineCreator(failingStore)
        // Force the BACK persist to fail by pre-seeding a RECIPIENT back row
        // that bypasses hasBaseline's FRONT check ordering.
        failingStore.persist(capsuleId, FingerprintSide.BACK, FingerprintOrigin.RECIPIENT, "mvp-orb-v1", "preexisting-back".toByteArray())
        attempts += 1

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                flakyCreator.createAfterVerifiedReceipt(capsuleId, side(FingerprintSide.FRONT), side(FingerprintSide.BACK))
            }
        }

        val recipientRows = database.recognitionFingerprintDao().getAllByCapsuleId(capsuleId)
            .filter { it.origin == FingerprintOrigin.RECIPIENT }
        // Only the pre-existing BACK remains; the rolled-back FRONT is gone.
        assertEquals(listOf(FingerprintSide.BACK), recipientRows.map { it.side })
        assertFalse(recipientRows.any { it.preferred })
        assertTrue(attempts >= 1)
    }
}
