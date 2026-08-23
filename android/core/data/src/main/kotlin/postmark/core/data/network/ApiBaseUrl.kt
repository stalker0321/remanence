package postmark.core.data.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ApiBaseUrl private constructor(val httpUrl: HttpUrl) {
    fun resolve(relativePath: String): HttpUrl {
        require(!relativePath.startsWith("/")) { "path must not start with '/'" }
        require("://" !in relativePath) { "path must not be absolute" }
        return httpUrl.resolve(relativePath)
            ?: throw IllegalArgumentException("cannot resolve path")
    }

    companion object {
        private val httpLoopbackHosts = setOf("localhost", "127.0.0.1", "::1")

        fun parse(raw: String): ApiBaseUrl {
            require(raw == raw.trim()) { "base URL must not have surrounding whitespace" }
            require(raw.endsWith("/")) { "base URL must end with '/'" }
            val schemeSep = raw.indexOf("://")
            require(schemeSep > 0) { "malformed URL" }
            val authorityAndPath = raw.substring(schemeSep + 3)
            require(authorityAndPath.isNotEmpty() && authorityAndPath[0] != '/') { "host is required" }
            val parsed = raw.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("malformed URL")
            require(parsed.host.isNotEmpty()) { "host is required" }
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "userinfo is not allowed" }
            require(parsed.query == null && parsed.encodedQuery == null) { "query is not allowed" }
            require(parsed.fragment == null && parsed.encodedFragment == null) { "fragment is not allowed" }
            when (parsed.scheme) {
                "https" -> Unit
                "http" -> require(parsed.host in httpLoopbackHosts) {
                    "http is only allowed for loopback hosts"
                }
                else -> throw IllegalArgumentException("unsupported scheme")
            }
            return ApiBaseUrl(parsed)
        }
    }
}
