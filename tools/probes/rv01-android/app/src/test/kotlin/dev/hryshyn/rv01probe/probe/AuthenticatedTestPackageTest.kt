package dev.hryshyn.rv01probe.probe

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AuthenticatedTestPackageTest {
    @Test
    fun `package binds context and rejects tampering`() {
        val canary = ByteArray(32) { it.toByte() }
        val material = ByteArray(32) { (it + 32).toByte() }
        val context = TestPackageContext(version = 4, purpose = "rv01-test")
        val packageValue = AuthenticatedTestPackage.seal(
            canary,
            material,
            context,
            random = SecureRandom(),
        )
        assertNotNull(packageValue)
        val encoded = packageValue!!.encode()

        assertArrayEquals(canary, AuthenticatedTestPackage.open(encoded, material, context))
        assertNull(
            AuthenticatedTestPackage.open(
                encoded,
                material,
                context.copy(purpose = "other-context"),
            ),
        )

        val tampered = encoded.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 1).toByte()
        assertNull(AuthenticatedTestPackage.open(tampered, material, context))
    }
}
