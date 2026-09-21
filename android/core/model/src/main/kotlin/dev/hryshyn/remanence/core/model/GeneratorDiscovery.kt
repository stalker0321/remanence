package dev.hryshyn.remanence.core.model

/**
 * G2 — provider-neutral generator discovery/execution boundary and
 * deterministic candidate-set model.
 *
 * Authority: `docs/hold/generator-boundary.md` (Generate/Select semantics:
 * bounded candidate set or explicit incompatibility; exact candidate
 * snapshot; previous/next restores it, never silently reruns) and
 * `CANONICAL_PRODUCT_RENDERER_ARCHITECTURE.md` §3/§6 (selector filters
 * prerequisites, resolves approved branches, then checks fit; structural
 * exclusion is Not-applicable; first expression of the remaining set is
 * chosen; select/previous restore exact snapshots).
 *
 * Scope (strict): discovery ordering, execution boundary, candidate
 * identity/ordering/validation, outcome taxonomy, seed provenance.
 * Explicitly NOT in G2: real renderer algorithms, branch/layout rules
 * (grammar v1–v7 research artifacts are NOT promoted into production
 * policy here), crop correction/analysis, UI, publishing, receive
 * presentation, music semantics, M4.
 *
 * Resolved design decisions (from the docs, no guessing):
 * - Providers are discovered in deterministic order: ascending
 *   `providerId`, then descending `providerVersion` (newest first per
 *   id). Registration order never affects enumeration.
 * - `Unsupported` (provider-declared: this input is outside the
 *   provider) is distinct from `Failed` (provider errored or returned
 *   an unusable set): unsupported is routed, never retried or treated
 *   as empty success.
 * - A provider claiming applicability MUST yield a non-empty candidate
 *   list; an empty list is recorded as a provider failure
 *   (fail-closed: the boundary never proceeds on an empty set silently).
 * - Candidate ordinals must be 0..n-1 in listed order per provider;
 *   violation rejects the whole provider set (fail-closed).
 * - Attribution is dispatcher-owned: accepted candidates are stamped
 *   with the executing provider's ref; provider-supplied identity is
 *   never trusted and never reaches the accepted set.
 * - Validity is checked BEFORE duplicate tracking, so an invalid-first
 *   record can never suppress a later valid candidate with the same id.
 * - Cancellation is cooperative and rethrown, never converted into
 *   provider failure.
 * - Externally influenced inputs are explicitly bounded: ids ≤256 chars,
 *   reasons truncated at 2000 chars with a marker, at most 10 000
 *   candidates per request and per provider outcome.
 * - Result snapshots are immutable copies; full results are
 *   deterministic independent of registration order.
 * - Candidate IDs must be unique across the whole enumeration; later
 *   duplicates are rejected (first-in-provider-order wins).
 * - Every candidate expression must pass [GeneratorExpression] structural
 *   validation; invalid ones are rejected with reasons, never repaired.
 * - `maxCandidates` truncates deterministically in enumeration order and
 *   records how many were dropped; truncation never reorders.
 * - Provenance is stable: the same (input hash, generationId, seed,
 *   provider set) must enumerate identically — repeatability is a
 *   contract property, pinned by tests with fake providers.
 * - Provider exceptions ([Exception], never [Error]) are isolated: the
 *   failing provider is recorded and enumeration continues.
 */
object GeneratorDiscovery {

    /** Max chars for externally supplied ids (provider/candidate/generation). */
    const val MAX_ID_CHARS = 256

    /** Max chars kept from provider-supplied reason/error text (truncated). */
    const val MAX_REASON_CHARS = 2000

    /** Max candidates per request (flooding is a provider fault). */
    const val MAX_CANDIDATES = 10_000

    /** Max candidates accepted from a single provider outcome. */
    const val MAX_PROVIDER_CANDIDATES = 10_000

    /**
     * Cooperative-cancellation signal: thrown (never converted) when
     * [run] observes cancellation. Callers thread their own token
     * through `shouldCancel`; provider-thrown
     * [java.util.concurrent.CancellationException] is likewise rethrown.
     */
    class GenerationCancelledException(message: String) : RuntimeException(message)

    /** Stable provider identity (non-blank, e.g. reverse-dns name). */
    data class ProviderRef(val providerId: String, val providerVersion: Int)

    /** Provider-neutral generation entry point (implemented by providers). */
    interface GeneratorProvider {
        val ref: ProviderRef
        fun generate(request: GenerationRequest): ProviderOutcome
    }

    /** Boundary request: input + stable generation identity + seed + cap. */
    data class GenerationRequest(
        val input: GeneratorExpression.GeneratorInput,
        val generationId: String,
        val seed: Long,
        val maxCandidates: Int,
    )

    /** One enumerated candidate: stable id + ordinal + frozen expression. */
    data class Candidate(
        val candidateId: String,
        val ordinal: Int,
        val provider: ProviderRef,
        val expression: GeneratorExpression.ResolvedExpression,
    )

    /** Provider-level outcome taxonomy. */
    sealed interface ProviderOutcome {
        data class Candidates(val candidates: List<Candidate>) : ProviderOutcome
        data class Unsupported(val reason: String) : ProviderOutcome
        data class Failed(val error: String) : ProviderOutcome
    }

    /** One rejected candidate with machine-readable cause. */
    data class CandidateRejection(
        val candidateId: String,
        val provider: ProviderRef,
        val cause: String,
    )

    /** Per-provider execution record (stable order). */
    data class ProviderRecord(
        val provider: ProviderRef,
        val outcome: String,
        val accepted: Int,
        val rejected: Int,
        val detail: String,
    )

    /** Boundary result: ordered accepted set + rejections + provenance. */
    data class GenerationResult(
        val generationId: String,
        val seed: Long,
        val inputHash: String,
        val accepted: List<Candidate>,
        val rejected: List<CandidateRejection>,
        val providers: List<ProviderRecord>,
        val truncated: Int,
    )

    /** Validates a boundary request (structural + explicit bounds). */
    fun validateRequest(request: GenerationRequest): List<String> {
        val reasons = mutableListOf<String>()
        if (request.generationId.isBlank()) reasons += "generationId must not be blank"
        if (request.generationId.length > MAX_ID_CHARS) {
            reasons += "generationId must be <= $MAX_ID_CHARS chars"
        }
        if (request.maxCandidates <= 0) reasons += "maxCandidates must be positive"
        if (request.maxCandidates > MAX_CANDIDATES) reasons += "maxCandidates must be <= $MAX_CANDIDATES"
        when (val valid = GeneratorExpression.validate(request.input)) {
            is GeneratorExpression.InputValidation.Valid -> Unit
            is GeneratorExpression.InputValidation.Invalid -> reasons += valid.reasons.map { "input: $it" }
        }
        return reasons
    }

    /** Validates a provider-declared identity (dispatcher-owned check). */
    fun validateProviderRef(ref: ProviderRef): List<String> {
        val reasons = mutableListOf<String>()
        if (ref.providerId.isBlank()) reasons += "providerId must not be blank"
        if (ref.providerId.length > MAX_ID_CHARS) reasons += "providerId must be <= $MAX_ID_CHARS chars"
        if (ref.providerVersion < 0) reasons += "providerVersion must be >= 0"
        return reasons
    }

    /** Bounds provider-supplied free text deterministically (truncates). */
    internal fun boundReason(text: String): String =
        if (text.length <= MAX_REASON_CHARS) text else text.take(MAX_REASON_CHARS) + "…[truncated]"

    /** Deterministic discovery order: providerId asc, version desc. */
    fun discover(providers: List<GeneratorProvider>): List<GeneratorProvider> =
        providers.sortedWith(
            compareBy<GeneratorProvider> { it.ref.providerId }
                .thenByDescending { it.ref.providerVersion },
        )

    /**
     * Executes generation across providers with failure isolation.
     * Requires a structurally valid request (see [validateRequest]) and
     * valid provider identities (see [validateProviderRef]).
     *
     * Attribution is dispatcher-owned: accepted candidates are stamped
     * with the executing provider's [ProviderRef] — provider-supplied
     * identity is never trusted. Cancellation is cooperative:
     * `shouldCancel` is observed before every provider and
     * [GenerationCancelledException] /
     * [java.util.concurrent.CancellationException] propagate instead of
     * converting into provider failure. Snapshots in [GenerationResult]
     * are immutable copies.
     */
    fun run(
        request: GenerationRequest,
        providers: List<GeneratorProvider>,
        shouldCancel: () -> Boolean = { false },
    ): GenerationResult {
        require(validateRequest(request).isEmpty()) {
            "invalid request: ${validateRequest(request).joinToString("; ")}"
        }
        val refProblems = providers.flatMap { validateProviderRef(it.ref) }
        require(refProblems.isEmpty()) { "invalid provider: ${refProblems.joinToString("; ")}" }
        val ordered = discover(providers)
        val accepted = mutableListOf<Candidate>()
        val rejected = mutableListOf<CandidateRejection>()
        val records = mutableListOf<ProviderRecord>()
        val seenIds = mutableSetOf<String>()
        var remaining = request.maxCandidates
        var truncated = 0

        fun checkCancelled() {
            if (shouldCancel()) throw GenerationCancelledException("cancelled: ${request.generationId}")
        }

        checkCancelled()
        for (provider in ordered) {
            checkCancelled()
            val outcome = try {
                provider.generate(request)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: GenerationCancelledException) {
                throw e
            } catch (e: Exception) {
                ProviderOutcome.Failed("exception: ${e::class.simpleName}: ${e.message}")
            }
            when (outcome) {
                is ProviderOutcome.Unsupported -> records += ProviderRecord(
                    provider = provider.ref,
                    outcome = "unsupported",
                    accepted = 0,
                    rejected = 0,
                    detail = boundReason(outcome.reason),
                )
                is ProviderOutcome.Failed -> records += ProviderRecord(
                    provider = provider.ref,
                    outcome = "failed",
                    accepted = 0,
                    rejected = 0,
                    detail = boundReason(outcome.error),
                )
                is ProviderOutcome.Candidates -> {
                    val list = outcome.candidates
                    if (list.isEmpty()) {
                        records += ProviderRecord(provider.ref, "failed", 0, 0, "empty candidate set")
                        continue
                    }
                    if (list.size > MAX_PROVIDER_CANDIDATES) {
                        records += ProviderRecord(
                            provider.ref, "failed", 0, 0,
                            "candidate set exceeds bound $MAX_PROVIDER_CANDIDATES",
                        )
                        continue
                    }
                    var localAccepted = 0
                    var localRejected = 0
                    val orderOk = list.mapIndexed { index, c -> c.ordinal == index }.all { it }
                    if (!orderOk) {
                        for (c in list) {
                            rejected += CandidateRejection(boundId(c.candidateId), provider.ref, "non-sequential ordinal")
                            localRejected++
                        }
                        records += ProviderRecord(provider.ref, "failed", 0, localRejected, "non-sequential ordinals")
                        continue
                    }
                    for (candidate in list) {
                        if (remaining <= 0) {
                            truncated++
                            continue
                        }
                        if (candidate.candidateId.isBlank() || candidate.candidateId.length > MAX_ID_CHARS) {
                            rejected += CandidateRejection(boundId(candidate.candidateId), provider.ref, "bad id")
                            localRejected++
                            continue
                        }
                        // Validity BEFORE duplicate tracking: an invalid-first
                        // record must never suppress a later valid candidate.
                        val validity = GeneratorExpression.validateResolved(candidate.expression)
                        if (validity is GeneratorExpression.InputValidation.Invalid) {
                            rejected += CandidateRejection(
                                candidate.candidateId,
                                provider.ref,
                                "invalid expression: ${boundReason(validity.reasons.joinToString("; "))}",
                            )
                            localRejected++
                            continue
                        }
                        if (!seenIds.add(candidate.candidateId)) {
                            rejected += CandidateRejection(candidate.candidateId, provider.ref, "duplicate id")
                            localRejected++
                            continue
                        }
                        // Dispatcher-owned attribution: stamp the executing
                        // provider; the supplied ref is never trusted.
                        accepted += candidate.copy(provider = provider.ref)
                        localAccepted++
                        remaining--
                    }
                    records += ProviderRecord(
                        provider = provider.ref,
                        outcome = "candidates",
                        accepted = localAccepted,
                        rejected = localRejected,
                        detail = "",
                    )
                }
            }
        }
        return GenerationResult(
            generationId = request.generationId,
            seed = request.seed,
            inputHash = GeneratorExpression.canonicalHash(request.input),
            accepted = accepted.toList(),
            rejected = rejected.toList(),
            providers = records.toList(),
            truncated = truncated,
        )
    }

    /** Bounds rejection ids (blank stays blank-flagged, long truncated). */
    private fun boundId(candidateId: String): String =
        if (candidateId.length <= MAX_ID_CHARS) candidateId
        else candidateId.take(MAX_ID_CHARS) + "…[truncated]"
}
