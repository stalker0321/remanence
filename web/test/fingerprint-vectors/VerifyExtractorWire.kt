import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import java.io.File
import kotlin.system.exitProcess

/** TEST-ONLY minimal verifier: proves a browser-produced format-3 wire blob is
 *  accepted (parsed + canonically re-serialized byte-for-byte) by the UNCHANGED
 *  actual Kotlin codec. Not a replacement codec; no is-an-Android-detector claim.
 *  Reads the "wireHex" and "distinctWireHex" fields from a browser report JSON. */
private fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "odd hex length" }
    return ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
}

private fun field(json: String, name: String): String {
    val match = Regex("\"$name\"\\s*:\\s*\"([0-9a-fA-F]*)\"").find(json)
        ?: throw IllegalStateException("missing \"$name\" in report")
    return match.groupValues[1]
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: VerifyExtractorWireKt <browser-report.json>")
        exitProcess(2)
    }
    val json = File(args[0]).readText()
    val results = listOf("wireHex", "distinctWireHex").map { name ->
        val bytes = hexToBytes(field(json, name))
        require(bytes.isNotEmpty()) { "$name is empty" }
        val parsed = SiftRootSiftFingerprintCodec.parse(bytes)
        val canonical = SiftRootSiftFingerprintCodec.serialize(parsed)
        try {
            check(canonical.contentEquals(bytes)) { "$name is not Kotlin-canonical" }
        } finally {
            canonical.fill(0)
        }
        println("KOTLIN-WIRE-OK field=$name keypoints=${parsed.keypoints.size} w=${parsed.canonicalWidthPx} h=${parsed.canonicalHeightPx} coarse=${parsed.coarseHash64} bytes=${bytes.size}")
        bytes.size
    }
    println("KOTLIN-VERIFY-PASS verified=${results.size} report=${args[0]}")
}
