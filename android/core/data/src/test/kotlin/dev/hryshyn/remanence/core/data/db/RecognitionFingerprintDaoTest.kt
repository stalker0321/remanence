package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionFingerprintDaoTest {

    private companion object {
        const val OWNER = "0198f0a0-0000-7000-8000-00000000ow01"
        const val OTHER_OWNER = "0198f0a0-0000-7000-8000-00000000ow02"
    }

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

    private suspend fun insertAll(fingerprints: List<RecognitionFingerprintEntity>) {
        dao.insertAll(OWNER, fingerprints)
    }

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
        insertAll(senderPair + recipientPair)

        assertEquals(
            listOf("fp-sf", "fp-sb").sorted(),
            dao.getByCapsuleIdAndOriginAndOwner("0198f0a0-0000-7000-8000-00000000ca01", FingerprintOrigin.SENDER, OWNER).map { it.fingerprintId }.sorted(),
        )
        assertEquals(4, dao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER).size)
    }

    @Test
    fun duplicateSideOriginBaselineIsRejected(): Unit = runBlocking {
        insertAll(
            listOf(
                fingerprint("fp-first", FingerprintSide.FRONT, FingerprintOrigin.RECIPIENT),
                fingerprint("fp-second", FingerprintSide.FRONT, FingerprintOrigin.SENDER),
            ),
        )
        try {
            insertAll(
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
    fun authoritativeOwnerMismatchIsRejectedBeforeAnyInsert() = runBlocking {
        val first = fingerprint("fp-first", FingerprintSide.FRONT, FingerprintOrigin.SENDER)
        val foreign = fingerprint("fp-foreign", FingerprintSide.BACK, FingerprintOrigin.SENDER)
            .copy(ownerUserId = OTHER_OWNER)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.insertAll(OWNER, listOf(first, foreign)) }
        }
        assertTrue(dao.getAllForOwner(OWNER).isEmpty())
        assertTrue(dao.getAllForOwner(OTHER_OWNER).isEmpty())
    }

    @Test
    fun foreignFingerprintIdCollisionAbortsWholeBatchBeforeInsert() = runBlocking {
        val foreign = fingerprint("fp-foreign", FingerprintSide.FRONT, FingerprintOrigin.SENDER)
            .copy(ownerUserId = OTHER_OWNER)
        dao.insertAll(OTHER_OWNER, listOf(foreign))

        val newForOwner = fingerprint("fp-new", FingerprintSide.BACK, FingerprintOrigin.SENDER)
        val attemptedForeignReuse = foreign.copy(ownerUserId = OWNER)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { dao.insertAll(OWNER, listOf(newForOwner, attemptedForeignReuse)) }
        }

        assertTrue(dao.getByFingerprintIdAndOwner(newForOwner.fingerprintId, OWNER) == null)
        assertEquals(foreign, dao.getByFingerprintIdAndOwner(foreign.fingerprintId, OTHER_OWNER))
    }

    @Test
    fun setPreferredPairMarksExactlyOneOriginPair() = runBlocking {
        insertAll(
            listOf(
                fingerprint("fp-sf", FingerprintSide.FRONT, FingerprintOrigin.SENDER),
                fingerprint("fp-sb", FingerprintSide.BACK, FingerprintOrigin.SENDER),
                fingerprint("fp-rf", FingerprintSide.FRONT, FingerprintOrigin.RECIPIENT),
                fingerprint("fp-rb", FingerprintSide.BACK, FingerprintOrigin.RECIPIENT),
            ),
        )

        dao.setPreferredPairForOwner("0198f0a0-0000-7000-8000-00000000ca01", FingerprintOrigin.RECIPIENT, OWNER)

        val rows = dao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER)
        assertEquals(
            listOf(false, false),
            rows.filter { it.origin == FingerprintOrigin.SENDER }.map { it.preferred }.sorted(),
        )
        assertTrue(rows.filter { it.origin == FingerprintOrigin.RECIPIENT }.all { it.preferred })
        assertEquals(2, rows.count { it.preferred })
    }

    @Test
    fun switchingPreferredOriginClearsPreviousFlag() = runBlocking {
        insertAll(
            listOf(
                fingerprint("fp-rf", FingerprintSide.FRONT, FingerprintOrigin.RECIPIENT, preferred = true),
                fingerprint("fp-rb", FingerprintSide.BACK, FingerprintOrigin.RECIPIENT, preferred = true),
            ),
        )
        dao.setPreferredPairForOwner("0198f0a0-0000-7000-8000-00000000ca01", FingerprintOrigin.SENDER, OWNER)
        // SENDER rows do not exist yet; nothing may remain preferred.
        assertEquals(0, dao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER).count { it.preferred })

        insertAll(
            listOf(
                fingerprint("fp-sf", FingerprintSide.FRONT, FingerprintOrigin.SENDER),
                fingerprint("fp-sb", FingerprintSide.BACK, FingerprintOrigin.SENDER),
            ),
        )
        dao.setPreferredPairForOwner("0198f0a0-0000-7000-8000-00000000ca01", FingerprintOrigin.SENDER, OWNER)
        val rows = dao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER)
        assertFalse(rows.first { it.fingerprintId == "fp-rf" }.preferred)
        assertTrue(rows.first { it.fingerprintId == "fp-sb" }.preferred)
        assertEquals(2, rows.count { it.preferred })
    }
}
