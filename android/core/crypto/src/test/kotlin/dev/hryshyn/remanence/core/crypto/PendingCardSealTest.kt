package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.protocol.delivery_v2.CardSealAadV1
import dev.hryshyn.remanence.protocol.delivery_v2.PendingCardSealV1
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PendingCardSealTest {
    private val vector: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader.getResource("card-seal-v1.json")).readText(),
    ).jsonObject

    private fun text(key: String) = vector.getValue(key).jsonPrimitive.content
    private fun bytes(key: String): ByteArray = Base64.getDecoder().decode(text(key))

    @Test
    fun alphabetAndCodeFormatMatchTheReference() {
        assertEquals(text("alphabet"), PendingCardSeal.ALPHABET)
        assertEquals(text("code_display"), PendingCardSeal.formatCode(text("secret"), text("phone")))
    }

    @Test
    fun handwritingMapsTheSameWay() {
        for ((typed, expected) in vector.getValue("handwriting_inputs").jsonObject) {
            val parts = expected.jsonArray
            assertEquals(
                PendingCardSeal.ParsedCode(parts[0].jsonPrimitive.content, parts[1].jsonPrimitive.content),
                PendingCardSeal.parseCode(typed),
            )
        }
        for (bad in vector.getValue("invalid_inputs").jsonArray) {
            assertNull(PendingCardSeal.parseCode(bad.jsonPrimitive.content))
        }
    }

    @Test
    fun buildsTheExactSealOfTheReference() {
        val seal = PendingCardSeal.build(
            secret = text("secret"),
            capsuleAeadKeyset = bytes("keyset_b64"),
            openingCanonical = bytes("opening_b64"),
            binding = CardSealAadV1.parseFrom(bytes("binding_b64")),
            salt = bytes("kdf_salt_b64"),
            serverHalf = bytes("server_half_b64"),
            nonce = bytes("nonce_b64"),
        )
        assertContentEquals(bytes("seal_b64"), seal.toByteArray())
    }

    @Test
    fun opensTheReferenceSealOnlyWithTheRightCode() {
        val seal = PendingCardSealV1.parseFrom(bytes("seal_b64"))
        val opened = assertNotNull(PendingCardSeal.open(text("secret"), seal))
        assertContentEquals(bytes("keyset_b64"), opened.capsuleAeadKeyset)
        assertContentEquals(bytes("proof_b64"), opened.proof)
        assertContentEquals(bytes("opening_b64"), opened.addressOpening.toByteArray())
        assertNull(PendingCardSeal.open("0000000", seal))
    }

    @Test
    fun generatedSecretsUseOnlyTheAlphabet() {
        repeat(50) {
            val secret = PendingCardSeal.generateSecret()
            assertEquals(PendingCardSeal.SECRET_LENGTH, secret.length)
            assert(secret.all { it in PendingCardSeal.ALPHABET })
        }
    }
}
