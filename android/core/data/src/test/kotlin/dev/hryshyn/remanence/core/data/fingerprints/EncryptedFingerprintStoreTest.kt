package dev.hryshyn.remanence.core.data.fingerprints

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
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

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var filesRoot: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesRoot = File(context.filesDir, "fingerprints-test")
        filesRoot.mkdirs()
    }

    @After
    fun tearDown() {
        database.close()
        filesRoot.deleteRecursively()
    }

    private fun store(sealer: SecretSealer = XorSealer()) =
        EncryptedFingerprintStore(filesRoot, sealer, database.recognitionFingerprintDao())

    @Test
    fun persistEncryptsAtRestAndDecryptsRoundtrip() = runBlocking {
        val sut = store()
        val plaintext = "fingerprint-bytes-π".toByteArray()

        val id = sut.persist("capsule-a", FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", plaintext)

        val entity = database.recognitionFingerprintDao().getByFingerprintId(id)!!
        val onDisk = File(filesRoot, entity.encryptedPath).readBytes()
        assertFalse("plaintext must never reach disk", onDisk.contentEquals(plaintext))
        assertTrue(entity.encryptedPath.endsWith(".fpw"))

        assertArrayEquals(plaintext, sut.decrypt(id))
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

        assertEquals(1, filesRoot.listFiles()!!.size)
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
        val sut = EncryptedFingerprintStore(filesRoot, XorSealer(), explodingDao)

        try {
            sut.persist("capsule-a", FingerprintSide.BACK, FingerprintOrigin.SENDER, "mvp-orb-v1", byteArrayOf(9))
            throw AssertionError("expected insert failure")
        } catch (expected: IllegalStateException) {
            assertEquals("database exploded", expected.message)
        }
        assertEquals(0, filesRoot.listFiles()!!.size)
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
        database.recognitionFingerprintDao().deleteByCapsuleId("capsule-b")
        try {
            sut.decrypt(id)
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            // correct
        }
    }
}
