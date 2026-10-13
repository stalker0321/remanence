package dev.hryshyn.remanence.telemetry

import dev.hryshyn.remanence.core.data.network.ApiBaseUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

/** Deliberately NOT apiStack/client.newBuilder(): no credentials, shared cookies or request logging. */
internal class TelemetryHttpTransport(base: ApiBaseUrl) : TelemetryTransport {
    private val endpoint = base.resolve("v1/telemetry/remanence")
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).cache(null)
        .callTimeout(10, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .build()
    override fun send(bytes: ByteArray): TelemetryPendingSend {
        require(bytes.size in 1..32768)
        val body = object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        }
        val call = client.newCall(Request.Builder().url(endpoint).post(body).build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { bytes.fill(0) }
            override fun onResponse(call: Call, response: Response) { try { response.close() } finally { bytes.fill(0) } }
        })
        return TelemetryPendingSend { call.cancel(); bytes.fill(0) }
    }
}
