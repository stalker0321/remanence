package dev.hryshyn.remanence.core.data.network

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Frozen T403 v1 / 22e08 interface. Never an ordinary authenticated principal. */
enum class AtomicAction(val route: String) {
    GOOGLE_OPTIONS("v1/auth/google/options"), GOOGLE_VERIFY("v1/auth/google/verify"),
    STATUS(""), CREDENTIAL_OPTIONS("credential/options"), CREDENTIAL_VERIFY("credential/verify"),
    SETUP_OPTIONS("setup/options"), SETUP_VERIFY("setup/verify"), FINALIZE("finalize"),
    LEAVE("leave"), CANCEL("cancel"), RESTART("restart"),
}

fun interface AtomicRegistrationPort {
    suspend fun request(action: AtomicAction, reservation: String?, token: String?, body: JsonObject?): JsonObject
}

class AtomicRegistrationRejected(val status: Int, val reason: String = "SETUP_REJECTED") :
    Exception("Atomic registration did not complete")

/** Closed routes, bare client, bounded strict UTF-8 JSON and coroutine-owned call lifetime. */
class AtomicRegistrationRepository(private val baseUrl: ApiBaseUrl) : AtomicRegistrationPort {
    private val client = HttpClientFactory.create().newBuilder().retryOnConnectionFailure(false).build()

    override suspend fun request(action: AtomicAction, reservation: String?, token: String?, body: JsonObject?): JsonObject {
        currentCoroutineContext().ensureActive()
        val google = action == AtomicAction.GOOGLE_OPTIONS || action == AtomicAction.GOOGLE_VERIFY
        val path = if (google) {
            require(reservation == null && token == null)
            action.route
        } else {
            AtomicWire.uuid(requireNotNull(reservation))
            AtomicWire.capability(requireNotNull(token), "rgs_")
            "v2/registration/reservations/$reservation" + if (action == AtomicAction.STATUS) "" else "/${action.route}"
        }
        val builder = PendingProfileHeaders.apply(Request.Builder().url(baseUrl.resolve(path))
            .header("Accept", "application/json").header("Cache-Control", "no-store"))
        if (!google) builder.header("Authorization", "Bearer $token")
        if (action == AtomicAction.STATUS) require(body == null)
        else {
            val bytes = requireNotNull(body).toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES)
            builder.post(bytes.toRequestBody("application/json".toMediaType()))
        }
        return client.executeResponseWithCallLifetime(builder.build()) { response ->
            val mime = response.body.contentType()
            if (mime == null || mime.type != "application" || mime.subtype !in setOf("json", "problem+json")) {
                throw AtomicRegistrationRejected(response.code)
            }
            val buffer = okio.Buffer()
            val source = response.body.source()
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer, MAX_BYTES.toLong() + 1 - buffer.size)
                if (buffer.size > MAX_BYTES) throw AtomicRegistrationRejected(0)
                if (read == -1L) break
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer.readByteArray())).toString()
            val root = NetworkJson.decodeFromString<JsonObject>(text)
            currentCoroutineContext().ensureActive()
            if (response.code != 200 && !(action == AtomicAction.FINALIZE && response.code == 201)) {
                val reason = (root["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "SETUP_REJECTED"
                throw AtomicRegistrationRejected(response.code, reason)
            }
            root
        }
    }

    private companion object { const val MAX_BYTES = 262144 }
}

enum class AtomicReservationState { RESERVED, ACTIVE, CANCELLED, EXPIRED }
data class AtomicReservation(val id: String, val accountId: String, val epoch: Int,
    val state: AtomicReservationState, val credentialIds: List<String>, val outcome: JsonObject?)
class AtomicSetup(val reservation: AtomicReservation, val token: String) {
    override fun toString() = "AtomicSetup(<redacted>)"
}

/** Shared strict scalar checks; separate from unrelated finalizer parsing. */
object AtomicWire {
    fun text(root: JsonObject, key: String, max: Int = 262144): String {
        val value = root[key] as? JsonPrimitive ?: error("invalid atomic field")
        require(value.isString && value.content.length in 1..max)
        return value.content
    }
    fun integer(root: JsonObject, key: String): Int {
        val value = root[key] as? JsonPrimitive ?: error("invalid atomic integer")
        require(!value.isString && value.content.matches(Regex("[0-9]+")))
        return requireNotNull(value.intOrNull).also { require(it > 0) }
    }
    fun flag(root: JsonObject, key: String, expected: Boolean) {
        require(root[key] == JsonPrimitive(expected))
    }
    fun uuid(value: String): String = value.also { require(UUID.fromString(it).toString() == it) }
    fun binary(value: String, max: Int = 45000): String = value.also {
        require(it.length in 1..max && it.matches(Regex("[A-Za-z0-9_-]+")))
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(it)) == it)
    }
    fun capability(value: String, prefix: String): String = value.also {
        require(it.startsWith(prefix) && it.length == prefix.length + 43)
        binary(it.substring(prefix.length), 43)
    }
    fun reservation(root: JsonObject): AtomicReservation {
        val ids = root.getValue("credential_ids").jsonArray.map { binary(it.jsonPrimitive.also { p -> require(p.isString) }.content, 2048) }
        require(ids.size <= 4 && ids.distinct().size == ids.size)
        val outcome = root.getValue("outcome").let { if (it == JsonNull) null else it.jsonObject }
        val account = uuid(text(root, "account_id", 36))
        if (outcome != null) require(uuid(text(outcome, "account_id", 36)) == account)
        text(root, "expires_at", 64)
        root.getValue("restarted_into").let { if (it != JsonNull) uuid(it.jsonPrimitive.content) }
        return AtomicReservation(uuid(text(root, "id", 36)), account, integer(root, "epoch"),
            AtomicReservationState.valueOf(text(root, "state", 16)), ids, outcome)
    }
    fun setup(root: JsonObject): AtomicSetup {
        require(text(root, "status", 32) == "SETUP_REQUIRED")
        uuid(text(root, "setup_session_id", 36)); text(root, "setup_expires_at", 64)
        return AtomicSetup(reservation(root.getValue("reservation").jsonObject), capability(text(root, "setup_token", 47), "rgs_"))
    }
    fun login(root: JsonObject): LoginResponseDto = NetworkJson.decodeFromJsonElement<LoginResponseDto>(root).also {
        uuid(it.user.userId); uuid(it.activeKeyBundle.keyBundleId); uuid(it.sessionId)
        require(it.accessToken.isNotBlank() && it.refreshToken.isNotBlank())
        require(it.activeKeyBundle.protocolVersion == 1 && it.activeKeyBundle.status == "ACTIVE")
        require(it.activeKeyBundle.suite == "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519")
    }
}
