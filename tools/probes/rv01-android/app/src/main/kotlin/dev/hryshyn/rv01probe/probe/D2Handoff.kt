package dev.hryshyn.rv01probe.probe

/**
 * D1-side operator handoff for the first A/G1 rehearsal. The display value is
 * non-secret: exact 22-char K_U plus the exact runId/context fields the
 * operator re-enters on D2. It never enters evidence JSON, logs, URIs, or
 * provider identity; the screen is its only surface.
 *
 * P framing is unchanged: RVP1 P necessarily carries its claimed
 * ExpectedContext (including runId) by design. K_U never enters P; the
 * trusted D2 context is independently constructed here and never derived
 * from P. No D2 resume, no Remanence integration, no physical/cloud claim.
 */
data class D2HandoffDisplay(
    val key: String,
    val runIdHex: String,
    val accountBindingClass: String,
    val generation: String,
    val targetRole: String,
    val profile: String,
    val purpose: String,
) {
    /** Single copyable line for the operator to carry to D2. */
    fun copyText(): String =
        "RV01-D2-HANDOFF-V1 key=$key runIdHex=$runIdHex account=$accountBindingClass " +
            "generation=$generation role=$targetRole profile=$profile purpose=$purpose"

    companion object {
        const val SCHEMA_TAG = "RV01-D2-HANDOFF-V1"

        fun from(key: String, d2Context: ExpectedContext): D2HandoffDisplay? {
            if (!ProbeKey.isValid(key)) return null
            if (d2Context.targetRole != ContextTargetRole.D2_TARGET) return null
            if (d2Context.accountBindingClass != AccountBindingClass.A) return null
            if (d2Context.generation != ContextGeneration.G1) return null
            if (d2Context.runId.size != ExpectedContext.RUN_ID_BYTES) return null
            return D2HandoffDisplay(
                key = key,
                runIdHex = d2Context.runId.toHex(),
                accountBindingClass = "A",
                generation = "G1",
                targetRole = "D2_TARGET",
                profile = "BLOCKSTORE_U_PLUS_P",
                purpose = "BLOCKSTORE_REC01",
            )
        }
    }
}

/** Bounded D1-retained D2 handoff: independent context, its sidecar, display. */
data class D2Sealed(
    val context: ExpectedContext,
    val sidecar: ByteArray,
    val display: D2HandoffDisplay,
)

/**
 * Seals a distinct opaque P_D2 with the same ephemeral U/canary under an
 * independently constructed trusted D2 context (A, G1, exact runId copy,
 * D2_TARGET). The D1 context is never mutated or reinterpreted. Null on any
 * invalid input or seal failure; partial material is wiped. K_U never enters
 * the sidecar; only the display carries it.
 */
fun sealD2Handoff(
    key: String,
    canary: ByteArray,
    unwrapMaterial: ByteArray,
    d1Context: ExpectedContext,
): D2Sealed? {
    if (!ProbeKey.isValid(key)) return null
    if (canary.size != ProbeSidecar.CANARY_BYTES) return null
    if (unwrapMaterial.size != ProbeSidecar.KEY_BYTES) return null
    if (d1Context.targetRole != ContextTargetRole.D1_SOURCE) return null
    if (d1Context.accountBindingClass != AccountBindingClass.A) return null
    if (d1Context.generation != ContextGeneration.G1) return null
    if (d1Context.runId.size != ExpectedContext.RUN_ID_BYTES) return null

    val d2RunId = d1Context.runId.copyOf()
    val d2Context = try {
        ExpectedContext(
            accountBindingClass = AccountBindingClass.A,
            runId = d2RunId,
            generation = ContextGeneration.G1,
            targetRole = ContextTargetRole.D2_TARGET,
        )
    } catch (_: RuntimeException) {
        d2RunId.fill(0)
        return null
    }
    val sidecar = ProbeSidecar.seal(canary, unwrapMaterial, d2Context)
    if (sidecar == null) {
        d2Context.wipeRunId()
        return null
    }
    val display = D2HandoffDisplay.from(key, d2Context)
    if (display == null) {
        sidecar.fill(0)
        d2Context.wipeRunId()
        return null
    }
    return D2Sealed(d2Context, sidecar, display)
}

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    val alphabet = "0123456789abcdef"
    for (i in indices) {
        val value = this[i].toInt() and 0xff
        chars[i * 2] = alphabet[value ushr 4]
        chars[i * 2 + 1] = alphabet[value and 0x0f]
    }
    return chars.concatToString()
}

internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = this[i * 2].digitToIntOrNull(16) ?: return null
        val lo = this[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
