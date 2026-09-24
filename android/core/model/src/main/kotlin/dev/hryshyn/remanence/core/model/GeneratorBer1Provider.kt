package dev.hryshyn.remanence.core.model

/**
 * ADR-017/018 step 2: the real G2 [GeneratorDiscovery.GeneratorProvider] over
 * the BER1 editorial-rows planner. It yields exactly one deterministic
 * candidate from an eligible G1 input, or a typed non-success; the candidate
 * id is derived from the BEXPR01-free expression canonical hash so it is
 * stable across seeds. A non-empty note is NOT resolved here (on-device text
 * measurement belongs to the renderer), so it is reported as route-elsewhere.
 * Model-only: no UI, no publishing, no wire.
 */
object GeneratorBer1Provider {

    const val PROVIDER_ID = "dev.hryshyn.remanence.generator.ber1-editorial-rows"
    const val PROVIDER_VERSION = 1

    class Ber1Provider(
        override val ref: GeneratorDiscovery.ProviderRef =
            GeneratorDiscovery.ProviderRef(PROVIDER_ID, PROVIDER_VERSION),
    ) : GeneratorDiscovery.GeneratorProvider {

        override suspend fun generate(
            request: GeneratorDiscovery.GenerationRequest,
        ): GeneratorDiscovery.ProviderOutcome {
            val input = request.input
            if (input.music != null) {
                return GeneratorDiscovery.ProviderOutcome.NotApplicable(
                    "BER1 does not render a music reference",
                )
            }
            return when (val plan = GeneratorEditorialRows.plan(input)) {
                is GeneratorEditorialRows.PlanResult.Planned ->
                    GeneratorDiscovery.ProviderOutcome.Candidates(
                        listOf(
                            GeneratorDiscovery.Candidate(
                                candidateId = candidateIdFor(plan.expression),
                                ordinal = 0,
                                provider = ref,
                                expression = plan.expression,
                            ),
                        ),
                    )
                is GeneratorEditorialRows.PlanResult.NeedsNoteMeasure ->
                    GeneratorDiscovery.ProviderOutcome.NotApplicable(
                        "non-empty note requires on-device text measurement",
                    )
                is GeneratorEditorialRows.PlanResult.Incompatible ->
                    GeneratorDiscovery.ProviderOutcome.Incompatible(plan.reason)
                is GeneratorEditorialRows.PlanResult.Invalid ->
                    GeneratorDiscovery.ProviderOutcome.Failed(
                        plan.reasons.joinToString("; "),
                    )
            }
        }
    }

    /** Deterministic, bounded candidate id from the expression canonical hash. */
    fun candidateIdFor(expression: GeneratorExpression.ResolvedExpression): String =
        "ber1-" + GeneratorExpression.canonicalHash(expression).take(CANDIDATE_ID_HASH_CHARS)

    private const val CANDIDATE_ID_HASH_CHARS = 32
}
