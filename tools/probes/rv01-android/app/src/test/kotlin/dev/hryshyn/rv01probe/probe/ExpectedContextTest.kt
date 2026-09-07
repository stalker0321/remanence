package dev.hryshyn.rv01probe.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExpectedContextTest {
    @Test
    fun `canonical context is exactly the documented 50 byte layout`() {
        val context = context(AccountBindingClass.B, ContextGeneration.G2, ContextTargetRole.D2_TARGET)
        val encoded = context.canonicalBytes()

        assertEquals(ExpectedContext.CANONICAL_BYTES, encoded.size)
        assertArrayEquals(byteArrayOf(0x45, 0x43, 0x30, 0x31), encoded.copyOfRange(0, 4))
        assertEquals(1, encoded[4].toInt())
        assertEquals(1, encoded[5].toInt())
        assertEquals(1, ByteBuffer.wrap(encoded, 6, 2).order(ByteOrder.BIG_ENDIAN).short.toInt())
        assertEquals(16, encoded[8].toInt())
        assertArrayEquals(
            "BLOCKSTORE_REC01".toByteArray(Charsets.UTF_8),
            encoded.copyOfRange(9, 25),
        )
        assertArrayEquals(context.runId, encoded.copyOfRange(25, 41))
        assertEquals(2L, ByteBuffer.wrap(encoded, 41, 8).order(ByteOrder.BIG_ENDIAN).long)
        assertEquals(2, encoded[49].toInt())

        val decoded = ExpectedContext.decode(encoded)
        assertNotNull(decoded)
        assertEquals(context, decoded)
        assertArrayEquals(encoded, decoded!!.canonicalBytes())
    }

    @Test
    fun `context rejects malformed fixed layout and unknown values`() {
        val encoded = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
            .canonicalBytes()
        val vectors = listOf(
            "truncated" to encoded.copyOf(49),
            "trailing" to encoded + byteArrayOf(0),
            "magic" to encoded.changed(0, 0x00),
            "account" to encoded.changed(4, 0xff),
            "profile" to encoded.changed(5, 0xff),
            "profile version" to encoded.changed(7, 0x02),
            "purpose length" to encoded.changed(8, 0x0f),
            "invalid UTF-8" to encoded.changed(9, 0xff),
            "generation" to encoded.changed(48, 0xff),
            "target role" to encoded.changed(49, 0xff),
        )

        vectors.forEach { (name, value) ->
            assertNull(name, ExpectedContext.decode(value))
        }
    }

    @Test
    fun `each trusted context field mismatch remains canonical but rejects sidecar`() {
        val canary = ByteArray(32) { (it + 1).toByte() }
        val material = ByteArray(32) { (it + 33).toByte() }
        val trusted = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
        val sidecar = checkNotNull(ProbeSidecar.seal(canary, material, trusted))
        val changedRunId = trusted.runId.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val mismatches = listOf(
            "runId" to trusted.copy(runId = changedRunId),
            "account class" to trusted.copy(accountBindingClass = AccountBindingClass.B),
            "generation" to trusted.copy(generation = ContextGeneration.G2),
            "device role" to trusted.copy(targetRole = ContextTargetRole.D2_TARGET),
        )

        mismatches.forEach { (name, changed) ->
            val changedCanonical = changed.canonicalBytes()
            val decoded = ExpectedContext.decode(changedCanonical)
            assertNotNull("$name must remain a valid canonical context", decoded)
            assertArrayEquals(changedCanonical, decoded!!.canonicalBytes())
            assertNull("$name trusted-context mismatch", ProbeSidecar.open(sidecar, material, changed))

            val packageWithChangedAccountOrRun = sidecar.withClaimedContext(changed)
            assertNull(
                "$name claimed-context mismatch",
                ProbeSidecar.open(packageWithChangedAccountOrRun, material, trusted),
            )
        }
    }

    private fun context(
        account: AccountBindingClass,
        generation: ContextGeneration,
        role: ContextTargetRole,
    ) = ExpectedContext(
        accountBindingClass = account,
        runId = ByteArray(16) { (it + 1).toByte() },
        generation = generation,
        targetRole = role,
    )

    private fun ByteArray.changed(index: Int, value: Int): ByteArray = copyOf().also {
        it[index] = value.toByte()
    }

    private fun ByteArray.withClaimedContext(context: ExpectedContext): ByteArray = copyOf().also {
        context.canonicalBytes().copyInto(it, destinationOffset = 8)
    }
}
