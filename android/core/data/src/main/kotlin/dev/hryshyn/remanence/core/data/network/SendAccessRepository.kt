package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.UserId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

enum class SendAccessMode { RECORD, ENFORCE }
data class SendAccessBucket(val used: Int, val remaining: Int)
/** Advisory metadata, never a publication capability or a slot reservation. */
data class SendAccess(
    val mode: SendAccessMode,
    val lifetime: Boolean,
    val self: SendAccessBucket,
    val other: SendAccessBucket,
    val canPublishSelf: Boolean,
    val canPublishOther: Boolean,
)
enum class SendAccessFailure { SIGN_IN, UNAVAILABLE }
sealed interface SendAccessResult {
    data object Hidden : SendAccessResult
    data class Loaded(val value: SendAccess) : SendAccessResult
    data class Failed(val reason: SendAccessFailure) : SendAccessResult
}
fun interface SendAccessPort {
    suspend fun read(owner: UserId): SendAccessResult
}

/** Exact T376/T379 read DTO. This repository never stores or changes a balance. */
class SendAccessRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
    private val leases: SessionRequestLeaseProvider,
) : SendAccessPort {
    override suspend fun read(owner: UserId): SendAccessResult {
        val lease = leases.capture()?.takeIf { it.ownerUserId == owner && leases.isLive(it) }
            ?: return SendAccessResult.Failed(SendAccessFailure.SIGN_IN)
        fun unavailable() = SendAccessResult.Failed(
            if (leases.isLive(lease)) SendAccessFailure.UNAVAILABLE else SendAccessFailure.SIGN_IN)
        val request = leases.tag(Request.Builder().url(baseUrl.resolve("v1/me/send-access"))
            .header("Accept", "application/json").header("Cache-Control", "no-store").get().build(), lease)
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                if (!leases.isLive(lease)) return@executeResponseWithCallLifetime unavailable()
                val result = when (response.code) {
                    404 -> SendAccessResult.Hidden
                    401, 403 -> SendAccessResult.Failed(SendAccessFailure.SIGN_IN)
                    200 -> {
                        val body = response.body
                        require(body.contentType()?.let { it.type == "application" && it.subtype == "json" } == true)
                        val limit = 32 * 1024L
                        require(body.contentLength() <= limit)
                        val buffer = Buffer()
                        val source = body.source()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buffer, limit + 1 - buffer.size)
                            require(buffer.size <= limit)
                            if (read == -1L) break
                        }
                        SendAccessResult.Loaded(decodeSendAccess(buffer.readUtf8()))
                    }
                    else -> unavailable()
                }
                currentCoroutineContext().ensureActive()
                if (leases.isLive(lease)) result else unavailable()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { unavailable() }
        catch (_: IllegalArgumentException) { unavailable() }
    }
}

internal fun decodeSendAccess(raw: String): SendAccess {
    val root = Json.parseToJsonElement(raw).exactObject("policy_version", "mode", "lifetime", "free", "can_publish")
    require(root.strictInt("policy_version") == 1)
    val mode = when (root.strictString("mode")) {
        "record" -> SendAccessMode.RECORD
        "enforce" -> SendAccessMode.ENFORCE
        else -> throw IllegalArgumentException("unsupported policy")
    }
    val lifetime = root.strictBoolean("lifetime")
    val free = root.getValue("free").exactObject("self", "other")
    fun bucket(key: String): SendAccessBucket {
        val value = free.getValue(key).exactObject("limit", "used", "remaining")
        val used = value.strictInt("used")
        val remaining = value.strictInt("remaining")
        require(value.strictInt("limit") == 2 && used in 0..2 && remaining in 0..2 && used + remaining == 2)
        return SendAccessBucket(used, remaining)
    }
    val self = bucket("self")
    val other = bucket("other")
    val can = root.getValue("can_publish").exactObject("self", "other")
    val canSelf = can.strictBoolean("self")
    val canOther = can.strictBoolean("other")
    require(canSelf == (mode == SendAccessMode.RECORD || lifetime || self.remaining > 0))
    require(canOther == (mode == SendAccessMode.RECORD || lifetime || other.remaining > 0))
    return SendAccess(mode, lifetime, self, other, canSelf, canOther)
}

internal fun JsonElement.exactObject(vararg keys: String): JsonObject =
    (this as? JsonObject)?.also { require(it.keys == keys.toSet()) }
        ?: throw IllegalArgumentException("invalid object")

internal fun JsonObject.strictString(key: String): String =
    (getValue(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("invalid string")

internal fun JsonObject.strictInt(key: String): Int =
    (getValue(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        ?: throw IllegalArgumentException("invalid integer")

internal fun JsonObject.strictBoolean(key: String): Boolean =
    (getValue(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: throw IllegalArgumentException("invalid boolean")
