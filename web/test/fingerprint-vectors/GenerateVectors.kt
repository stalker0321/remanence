import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.core.model.SiftRootSiftKeypoint
import java.io.File

/** TEST-ONLY generator: executes the UNCHANGED Kotlin codec/domain to emit genuine
 *  format-3 wire vectors (JSON). Not production; no photographs/real data/keys. */

private fun hex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
    return sb.toString()
}

private fun row(seed: Int): ByteArray = ByteArray(128) { i -> ((i * 31 + seed) and 0xFF).toByte() }
private fun zeroRow(): ByteArray = ByteArray(128)
private fun kp(x: Int, y: Int, s: Int, a: Int, r: Int, o: Int) = SiftRootSiftKeypoint(x, y, s, a, r, o)

private class V(
    val name: String,
    val w: Int,
    val h: Int,
    val hash: Long,
    val pts: List<SiftRootSiftKeypoint>,
    val rows: List<ByteArray>,
)

private fun jsonEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

fun main(args: Array<String>) {
    val outPath = args[0]
    val profile = SiftRootSiftFingerprintCodec.PROFILE_ID
    val vectors = listOf(
        V("zero_defaults", 1600, 1200, 0L, listOf(kp(0, 0, 0, 0, 0, 0)), listOf(zeroRow())),
        V("hash_minus_one", 1600, 1200, -1L, listOf(kp(1, 2, 3, 4, 5, 1)), listOf(row(1))),
        V("hash_min", 1600, 1200, Long.MIN_VALUE, listOf(kp(10, 20, 30, 40, 50, -8)), listOf(row(2))),
        V("hash_max", 1600, 1200, Long.MAX_VALUE, listOf(kp(1000, 2000, 3000, 4000, 5000, 8)), listOf(row(3))),
        V("hash_high_bit", 1600, 1200, Long.MIN_VALUE + 1, listOf(kp(999999, 1, 1000000, 35999, 1000000, -1)), listOf(row(4))),
        V("dims_min", 1, 1, 7L, listOf(kp(0, 1000000, 1000000, 0, 1000000, 0)), listOf(row(5))),
        V("dims_max", 100000, 100000, 9007199254740993L, listOf(kp(1000000, 0, 0, 35999, 0, 8)), listOf(row(6))),
        V(
            "multi_points",
            1600,
            1200,
            -9223372036854775807L,
            listOf(kp(1, 2, 3, 4, 5, 0), kp(6, 7, 8, 9, 10, -8), kp(11, 12, 13, 14, 15, 8)),
            listOf(row(7), row(8), row(9)),
        ),
    )
    val sb = StringBuilder()
    sb.append("{\n  \"profileId\": \"").append(profile).append("\",\n  \"vectors\": [\n")
    vectors.forEachIndexed { index, v ->
        val fp = SiftRootSiftFingerprint(profile, v.w, v.h, v.hash, v.pts, v.rows)
        val bytes = SiftRootSiftFingerprintCodec.serialize(fp)
        val parsed = SiftRootSiftFingerprintCodec.parse(bytes)
        val re = SiftRootSiftFingerprintCodec.serialize(parsed)
        check(bytes.contentEquals(re)) { "kotlin reparse mismatch for ${v.name}" }
        check(parsed.coarseHash64 == v.hash) { "kotlin coarse hash mismatch for ${v.name}" }
        check(parsed.keypoints == v.pts) { "kotlin keypoint mismatch for ${v.name}" }
        sb.append("    {\n")
        sb.append("      \"name\": \"").append(jsonEscape(v.name)).append("\",\n")
        sb.append("      \"canonicalWidthPx\": ").append(v.w).append(",\n")
        sb.append("      \"canonicalHeightPx\": ").append(v.h).append(",\n")
        sb.append("      \"coarseHash64\": \"").append(v.hash).append("\",\n")
        sb.append("      \"keypoints\": [")
        sb.append(v.pts.joinToString(", ") { "{ \"xMicro\": ${it.xMicro}, \"yMicro\": ${it.yMicro}, \"scaleMicro\": ${it.scaleMicro}, \"angleCentiDegrees\": ${it.angleCentiDegrees}, \"responseQuantized\": ${it.responseQuantized}, \"octave\": ${it.octave} }" })
        sb.append("],\n")
        sb.append("      \"descriptorsHex\": [")
        sb.append(v.rows.joinToString(", ") { "\"${hex(it)}\"" })
        sb.append("],\n")
        sb.append("      \"wireHex\": \"").append(hex(bytes)).append("\"\n")
        sb.append("    }").append(if (index == vectors.size - 1) "\n" else ",\n")
    }
    sb.append("  ]\n}\n")
    File(outPath).writeText(sb.toString())
    println("wrote ${vectors.size} genuine Kotlin vectors to $outPath")
}
