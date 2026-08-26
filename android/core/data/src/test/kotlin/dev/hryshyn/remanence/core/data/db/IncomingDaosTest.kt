package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingDaosTest {

    private companion object {
        const val OWNER = "0198f0a0-0000-7000-8000-00000000ow01"
    }

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var capsuleDao: IncomingCapsuleDao
    private lateinit var envelopeDao: IncomingEnvelopeDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        capsuleDao = database.incomingCapsuleDao()
        envelopeDao = database.incomingEnvelopeDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun capsule(
        capsuleId: String = "0198f0a0-0000-7000-8000-00000000ca01",
        state: IncomingMaterialState = IncomingMaterialState.DISCOVERED,
        readyAt: Long = 1_755_000_000_000,
    ) = IncomingCapsuleEntity(
        capsuleId = capsuleId,
        ownerUserId = "0198f0a0-0000-7000-8000-00000000ow01",
        senderUserId = "0198f0a0-0000-7000-8000-00000000se01",
        recipientUserId = "0198f0a0-0000-7000-8000-00000000re01",
        senderSigningKeyBundleId = "0198f0a0-0000-7000-8000-00000000sk01",
        recipientEncryptionKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        protocolVersion = 1,
        serverStatus = "READY",
        readyAtEpochMs = readyAt,
        signedStatementBytes = byteArrayOf(1, 2, 3),
        materialState = state,
    )

    private fun envelope(capsuleId: String = "0198f0a0-0000-7000-8000-00000000ca01") =
        IncomingEnvelopeEntity(
            capsuleId = capsuleId,
            ownerUserId = "0198f0a0-0000-7000-8000-00000000ow01",
            recipientKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
            hpkeCiphertext = byteArrayOf(9, 8, 7),
            transportSha256 = ByteArray(32) { 4 },
            receivedAtEpochMs = 1_755_000_100_000,
        )

    @Test
    fun upsertThenReadReturnsRoutedMetadataOnly() = runBlocking {
        val record = capsule()
        capsuleDao.upsertAllForOwner(listOf(record))
        assertEquals(record.signedStatementBytes.toList(), capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.signedStatementBytes.toList())
    }

    @Test
    fun replayedUpsertIsIdempotentByCapsuleId() = runBlocking {
        val record = capsule()
        capsuleDao.upsertAllForOwner(listOf(record))
        val updated = record.copy(serverStatus = "READY", readyAtEpochMs = 1_755_000_999_999)
        capsuleDao.upsertAllForOwner(listOf(updated))

        val loaded = capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!
        assertEquals(updated.readyAtEpochMs, loaded.readyAtEpochMs)
        // exactly one row for the capsule id after replay
        assertEquals(1, countRows("incoming_capsule"))
    }

    @Test
    fun materialStateTransitionHonorsAllowedOriginStates() = runBlocking {
        val record = capsule(state = IncomingMaterialState.DISCOVERED)
        capsuleDao.upsertAllForOwner(listOf(record))

        val skippedRows = capsuleDao.transitionMaterialStateForOwner(
            record.capsuleId,
            OWNER,
            IncomingMaterialState.FINGERPRINT_ACCEPTED,
            listOf(IncomingMaterialState.INDEX_CACHED),
        )
        assertEquals(0, skippedRows)

        val advancedRows = capsuleDao.transitionMaterialStateForOwner(
            record.capsuleId,
            OWNER,
            IncomingMaterialState.INDEX_CACHED,
            listOf(IncomingMaterialState.DISCOVERED, IncomingMaterialState.CORRUPT),
        )
        assertEquals(1, advancedRows)
        assertEquals(
            IncomingMaterialState.INDEX_CACHED,
            capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.materialState,
        )
    }

    @Test
    fun envelopeUpsertIsIdempotentAndReplaySafe() = runBlocking {
        val record = envelope()
        envelopeDao.upsertForOwner(record)
        envelopeDao.upsertForOwner(record.copy(receivedAtEpochMs = 1_755_000_200_000))

        val loaded = envelopeDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!
        assertEquals(1_755_000_200_000, loaded.receivedAtEpochMs)
        assertEquals(record.hpkeCiphertext.toList(), loaded.hpkeCiphertext.toList())
        assertEquals(1, countRows("incoming_envelope"))
    }

    @Test
    fun clearRemovesIncomingRecords() = runBlocking {
        val id = "0198f0a0-0000-7000-8000-00000000ca02"
        capsuleDao.upsertAllForOwner(listOf(capsule(capsuleId = id)))
        envelopeDao.upsertForOwner(envelope(capsuleId = id))
        capsuleDao.clear()
        envelopeDao.clear()
        assertNull(capsuleDao.getByCapsuleIdAndOwner(id, OWNER))
        assertNull(envelopeDao.getByCapsuleIdAndOwner(id, OWNER))
    }

    private fun countRows(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
