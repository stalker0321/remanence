package dev.hryshyn.rv01probe.probe

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProbeSidecarTest {
    @Test
    fun `deterministic RVP1 round trip uses trusted context as AAD`() {
        val canary = ByteArray(32) { (it + 1).toByte() }
        val material = ByteArray(32) { (it + 33).toByte() }
        val context = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
        val sidecar = ProbeSidecar.seal(canary, material, context, FixedRandom())

        assertNotNull(sidecar)
        assertEquals(ProbeSidecar.TOTAL_BYTES, sidecar!!.size)
        val opened = ProbeSidecar.open(sidecar, material, context)
        assertArrayEquals(canary, opened)
        opened!!.fill(0)
    }

    @Test
    fun `malicious vectors reject without returning plaintext`() {
        val canary = ByteArray(32) { (it + 1).toByte() }
        val material = ByteArray(32) { (it + 33).toByte() }
        val context = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
        val otherContext = context(AccountBindingClass.B, ContextGeneration.G2, ContextTargetRole.D2_TARGET)
        val sidecar = ProbeSidecar.seal(canary, material, context, FixedRandom())!!

        val vectors = listOf(
            "truncated" to sidecar.copyOf(120),
            "trailing" to sidecar + byteArrayOf(0),
            "oversize" to sidecar + ByteArray(ProbeSidecar.MAX_SIZE),
            "magic" to sidecar.changed(0, 0x00),
            "version" to sidecar.changed(5, 0x02),
            "context length" to sidecar.changed(7, 0x31),
            "invalid claimed UTF-8" to sidecar.changed(17, 0xff),
            "nonce length" to sidecar.changed(58, 0x0b),
            "nonce byte" to sidecar.changed(59, sidecar[59].toInt() xor 1),
            "ciphertext length" to sidecar.changed(72, 0x31),
            "ciphertext tamper" to sidecar.changed(73, sidecar[73].toInt() xor 1),
            "tag byte" to sidecar.changed(120, sidecar[120].toInt() xor 1),
            "claimed context mismatch" to sidecar.withClaimedContext(otherContext),
        )

        vectors.forEach { (name, value) ->
            assertNull(name, ProbeSidecar.open(value, material, context))
        }
        assertNull("wrong unwrap material", ProbeSidecar.open(sidecar, material.changed(0, 0x7f), context))
        assertNull("wrong trusted context", ProbeSidecar.open(sidecar, material, otherContext))
    }

    @Test
    fun `sidecar size boundary separates structural 1024 rejection from pre-parse 1025 rejection`() {
        val canary = ByteArray(32) { (it + 1).toByte() }
        val material = ByteArray(32) { (it + 33).toByte() }
        val context = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
        val sidecar = ProbeSidecar.seal(canary, material, context, FixedRandom())!!

        val exactlyMax = sidecar.copyOf(ProbeSidecar.MAX_SIZE)
        val overMax = sidecar.copyOf(ProbeSidecar.MAX_SIZE + 1)
        assertEquals(ProbeSidecar.MAX_SIZE, exactlyMax.size)
        assertEquals(ProbeSidecar.MAX_SIZE + 1, overMax.size)
        assertNull("1024-byte input is structurally invalid, not a valid sidecar", ProbeSidecar.open(exactlyMax, material, context))
        assertNull("1025-byte input is rejected before parsing", ProbeSidecar.open(overMax, material, context))
    }

    @Test
    fun `seal rejects wrong plaintext or key size`() {
        val context = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
        assertNull(ProbeSidecar.seal(ByteArray(31), ByteArray(32), context, FixedRandom()))
        assertNull(ProbeSidecar.seal(ByteArray(32), ByteArray(31), context, FixedRandom()))
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

    private class FixedRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.forEachIndexed { index, _ -> bytes[index] = (index + 1).toByte() }
        }
    }
}
