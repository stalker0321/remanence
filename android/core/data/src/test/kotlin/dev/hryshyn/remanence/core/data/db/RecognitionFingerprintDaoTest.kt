package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionFingerprintDaoTest {

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var dao: RecognitionFingerprintDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.recognitionFingerprintDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun fingerprint(
        id: String,
        side: FingerprintSide,
        origin: FingerprintOrigin,
        preferred: Boolean = false,
    ) = RecognitionFingerprintEntity(
        fingerprintId = id,
        ownerUserId = "0198f0a0-0000-7000-8000-00000000ow01",
        capsuleId = "0198f0a0-0000-7000-8000-00000000ca01",
        side = side,
        origin = origin,
        fingerprintProfileId = "mvp-orb-v1",
        encryptedPath = "files/fingerprints/$id.bin",
        createdAtEpochMs = 1_755_000_000_000,
        preferred = preferred,
    )

    @Test
    fun insertAndReadBackByOriginKeepsSidesDistinct() = runBlocking {
        val senderPair = listOf(
            fingerprint("fp-sf", FingerprintSide.FRONT, FingerprintOrigin.SENDER),
            fingerprint("fp-sb", FingerprintSide.BACK, FingerprintOrigin.SENDER),
        )
        val recipientPair = listOf(
            fingerprint("fp-rf", FingerprintSide.FRONT, FingerprintOrigin.RECIPIENT),
            fingerprint("fp-rb", FingerprintSide.BACK, FingerprintOrigin.RECIPIENT),
        )
        dao.insertAll(senderPair + recipientPair)

        assertEquals(
            listOf("fp-sf", "fp-sb").sorted(),
            dao.getByCapsuleIdAndOrigin("0198f0a0-0000-7000-8000-00000000ca01", FingerprintOrigin.SENDER).map { it.fingerprintId }.sorted(),
        )
        assertEquals(4, dao.getAllByCapsuleId("0198f0a0-0000-7000-8000-00000000ca01").size)
    }

    @Test
    fun duplicateSideOriginBaselineIsRejected(): Unit = runBlocking {
        dao.insertAll(
            listOf(
                fingerprint("fp-first", FingerprintSide.FRONT, FingerprintOrigin.RECIPIENT),
                fingerprint("fp-second", FingerprintSide.FRONT, FingerprintOrigin.SENDER),
            ),
        )
        try {
            dao.insertAll(
                listOf(fingerprint("fp-duplicate", FingerprintSide.FRONT, FingerprintOrigin.RECIPIENT)),
            )
            throw AssertionError("expected unique (capsule,side,origin) violation")
        } catch (expected: Exception) {
            val constraint = expected is android.database.sqlite.SQLiteConstraintException ||
                expected.cause is android.database.sqlite.SQLiteConstraintException
            assertTrue("unexpected: $expected", constraint)
        }
    }

    @Test
    fun setPreferredPairMarksExactlyOneOriginPair() = runBlocking {
        dao.insertAll(
            listOf(
                fingerprint("fp-sf", FingerprintSide.FRONT, FingerprintOrigin.SENDER),
                fingerprint("fp-sb", FingerprintSide.BACK, FingerprintOrigin.SENDER),
                fingerprint("fp-rf", FingerprintSide.FRONT, FingerprintOrigin.RECIPIENT),
                fingerprint("fp-rb", FingerprintSide.BACK, FingerprintOrigin.RECIPIENT),
            ),
        )

        dao.setPreferredPair("0198f0a0-0000-7000-8000-00000000ca01", FingerprintOrigin.RECIPIENT)

        val rows = dao.getAllByCapsuleId("0198f0a0-0000-7000-8000-00000000ca01")
        assertEquals(
            listOf(false, false),
            rows.filter { it.origin == FingerprintOrigin.SENDER }.map { it.preferred }.sorted(),
        )
        assertTrue(rows.filter { it.origin == FingerprintOrigin.RECIPIENT }.all { it.preferred })
        assertEquals(2, rows.count { it.preferred })
    }

    @Test
    fun switchingPreferredOriginClearsPreviousFlag() = runBlocking {
        dao.insertAll(
            listOf(
                fingerprint("fp-rf", FingerprintSide.FRONT, FingerprintOrigin.RECIPIENT, preferred = true),
                fingerprint("fp-rb", FingerprintSide.BACK, FingerprintOrigin.RECIPIENT, preferred = true),
            ),
        )
        dao.setPreferredPair("0198f0a0-0000-7000-8000-00000000ca01", FingerprintOrigin.SENDER)
        // SENDER rows do not exist yet; nothing may remain preferred.
        assertEquals(0, dao.getAllByCapsuleId("0198f0a0-0000-7000-8000-00000000ca01").count { it.preferred })

        dao.insertAll(
            listOf(
                fingerprint("fp-sf", FingerprintSide.FRONT, FingerprintOrigin.SENDER),
                fingerprint("fp-sb", FingerprintSide.BACK, FingerprintOrigin.SENDER),
            ),
        )
        dao.setPreferredPair("0198f0a0-0000-7000-8000-00000000ca01", FingerprintOrigin.SENDER)
        val rows = dao.getAllByCapsuleId("0198f0a0-0000-7000-8000-00000000ca01")
        assertFalse(rows.first { it.fingerprintId == "fp-rf" }.preferred)
        assertTrue(rows.first { it.fingerprintId == "fp-sb" }.preferred)
        assertEquals(2, rows.count { it.preferred })
    }
}
