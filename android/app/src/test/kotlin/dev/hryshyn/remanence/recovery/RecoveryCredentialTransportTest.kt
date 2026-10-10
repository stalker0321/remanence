package dev.hryshyn.remanence.recovery

import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

class RecoveryCredentialTransportTest {
    @Test fun providerPrfAndUnknownFieldsNeverEnterTransport() {
        val credential = Json.parseToJsonElement("""{
            "id":"credential", "rawId":"credential", "type":"public-key",
            "clientExtensionResults":{"prf":{"results":{"first":"SECRET_PRF"}},"future":"SECRET_OTHER"},
            "providerPrivateField":"SECRET_PROVIDER",
            "response":{"clientDataJSON":"data","authenticatorData":"auth","signature":"sig","userHandle":null,"providerSecret":"SECRET_RESPONSE"}
        }""").jsonObject
        val sent = publicCredential(credential)
        assertEquals(JsonObject(emptyMap()), sent["clientExtensionResults"])
        assertFalse(sent.toString().contains("SECRET"))
        assertEquals(setOf("id", "rawId", "type", "response", "clientExtensionResults"), sent.keys)
    }
}
