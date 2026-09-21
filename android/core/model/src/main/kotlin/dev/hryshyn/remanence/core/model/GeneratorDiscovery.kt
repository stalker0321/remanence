package dev.hryshyn.remanence.core.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
 * - Outcome taxonomy: `NotApplicable` (route elsewhere) vs
 *   `Incompatible` (stop) per arch §4/§6. `Unsupported` is retained
 *   unchanged for compatibility and records as route-elsewhere.
 * - `Incompatible` is typed and explicit but never suppresses later
 *   independent providers; when the final accepted set is empty and any
 *   applicable provider was incompatible, the terminal outcome is typed
 *   `INCOMPATIBLE` rather than an empty success (`NotApplicable` stays
 *   routable). Record outcomes are typed ([ProviderOutcomeRecord]) —
 *   routers must not string-parse.
 * - Dependency versions are strictly positive at enumeration:
 *   zero and negative grammar/font/palette versions are rejected;
 *   unknown positive versions pass through recorded.
 * - Accepted nested containers (input photos, placements, diagnostics)
 *   are deep-detached from provider-owned lists before accept/freeze;
 *   canonical bytes and hashes are unchanged.
 * - A provider claiming applicability MUST yield a non-empty candidate
 *   list; an empty list is recorded as a provider failure
 *   (fail-closed: the boundary never proceeds on an empty set silently).
 * - Candidate ordinals must be 0..n-1 in listed order per provider;
 *   violation rejects the whole provider set (fail-closed).
 * - Attribution is dispatcher-owned: accepted candidates are stamped
 *   with the executing provider's ref. A mismatch is RECORDED
 *   (provider-record detail), never silently laundered; `Candidate.provider`
 *   is output-only.
 * - Validity is checked BEFORE duplicate tracking, so an invalid-first
 *   record can never suppress a later valid candidate with the same id.
 * - Every candidate expression must equal the request input
 *   (data-class equality): structurally valid expressions built on
 *   foreign photos/note/owner are rejected as `"input mismatch"`, so
 *   `GenerationResult.inputHash` always describes the accepted set.
 * - Cancellation is real coroutine cancellation: `generate` is suspend,
 *   `run` observes the coroutine context before every provider, and
 *   [CancellationException] propagates unwrapped, never converted into
 *   provider failure. The `shouldCancel` polling hook is retained for
 *   non-coroutine cancellation signals; [GenerationCancelledException]
 *   is a transparent [CancellationException] subtype (kept — not
 *   dropped — so the ordered cooperative-cancellation coverage keeps
 *   compiling; behaviorally it IS a coroutine cancellation).
 * - Externally influenced inputs are explicitly bounded (DoS ceilings
 *   sized far above legitimate use — a handful of providers each
 *   yielding a handful of full expressions — not memory proofs):
 *   ids ≤256 chars, reasons truncated at 2000 chars with a marker, at
 *   most 32 providers, at most 1000 candidates per request, at most 100
 *   per provider outcome, at most 32 diagnostics per expression
 *   (keep-first-N, dropped count recorded).
 * - Rejection causes are machine-readable ([RejectionCause]) alongside
 *   the human cause string (unchanged vocabulary).
 * - Exception redaction: only the exception class simpleName is
 *   recorded, never the message text.
 * - Result snapshots are immutable copies; full results are
 *   deterministic independent of registration order.
 * - Version interpretation belongs to renderers, never to enumeration:
 *   negative grammar/font/palette versions are nonsense and rejected
 *   here; zero and unknown positive versions pass through recorded.
 *   Source byte handles (`contentId`→bytes binding) are a mandatory G3
 *   adapter concern and must NOT enter G1 identity.
 */
object GeneratorDiscovery {

    /** Max chars for externally supplied ids (provider/candidate/generation). */
    const val MAX_ID_CHARS = 256

    /** Max chars kept from provider-supplied reason/error text (truncated). */
    const val MAX_REASON_CHARS = 2000

    /** Max providers per request (DoS ceiling, far above legitimate use). */
    const val MAX_PROVIDERS = 32

    /** Max candidates per request (DoS ceiling, far above legitimate use). */
    const val MAX_CANDIDATES = 1000

    /** Max candidates accepted from a single provider outcome. */
    const val MAX_PROVIDER_CANDIDATES = 100

    /** Max diagnostics kept per expression (keep-first-N, dropped counted). */
    const val MAX_DIAGNOSTICS = 32

    /**
     * Cooperative-cancellation signal: a transparent [CancellationException]
     * subtype thrown when `shouldCancel` is observed. Retained (not dropped)
     * so ordered cooperative coverage keeps compiling; behaviorally it is a
     * plain coroutine cancellation and propagates identically.
     */
    class GenerationCancelledException(message: String) : CancellationException(message)

    /** Stable provider identity (non-blank, e.g. reverse-dns name). */
    data class ProviderRef(val providerId: String, val providerVersion: Int)

    /** Provider-neutral generation entry point (implemented by providers). */
    interface GeneratorProvider {
        val ref: ProviderRef
        suspend fun generate(request: GenerationRequest): ProviderOutcome
    }

    /** Boundary request: input + stable generation identity + seed + cap. */
    data class GenerationRequest(
        val input: GeneratorExpression.GeneratorInput,
        val generationId: String,
        val seed: Long,
        val maxCandidates: Int,
    )

    /**
     * One enumerated candidate: stable id + ordinal + frozen expression.
     * `provider` is OUTPUT-ONLY: the dispatcher stamps the executing
     * provider's ref on accept; anything the provider supplied there is
     * never trusted (mismatches are recorded, see N1).
     */
    data class Candidate(
        val candidateId: String,
        val ordinal: Int,
        val provider: ProviderRef,
        val expression: GeneratorExpression.ResolvedExpression,
    )

    /** Provider-level outcome taxonomy (arch §4/§6 vocabulary). */
    sealed interface ProviderOutcome {
        data class Candidates(val candidates: List<Candidate>) : ProviderOutcome

        /** Outside this provider: route elsewhere. */
        data class NotApplicable(val reason: String) : ProviderOutcome

        /** Structurally excluded: stop, do not route further. */
        data class Incompatible(val reason: String) : ProviderOutcome

        /** Retained route-elsewhere outcome (records as "unsupported"). */
        data class Unsupported(val reason: String) : ProviderOutcome

        data class Failed(val error: String) : ProviderOutcome
    }

    /** Machine-readable rejection cause (human `cause` string unchanged). */
    enum class RejectionCause {
        BLANK_ID,
        BAD_ID,
        DUPLICATE_ID,
        INVALID_EXPRESSION,
        INPUT_MISMATCH,
        NON_SEQUENTIAL_ORDINAL,
        PROVIDER_BOUND_EXCEEDED,
        NEGATIVE_VERSION,
        ZERO_VERSION,
    }

    /** Typed provider-record outcome (no string parsing by routers). */
    enum class ProviderOutcomeRecord {
        CANDIDATES,
        NOT_APPLICABLE,
        INCOMPATIBLE,
        UNSUPPORTED,
        FAILED,
    }

    /**
     * Typed terminal outcome of an enumeration: [SUCCESS] when the accepted
     * set is non-empty; [INCOMPATIBLE] when it is empty but at least one
     * applicable provider was incompatible (never an empty success);
     * [EMPTY] otherwise. `Incompatible` never suppresses later independent
     * providers — enumeration always continues.
     */
    enum class GenerationTerminalOutcome {
        SUCCESS,
        INCOMPATIBLE,
        EMPTY,
    }

    /** One rejected candidate with machine-readable cause. */
    data class CandidateRejection(
        val candidateId: String,
        val provider: ProviderRef,
        val cause: String,
        val causeCode: RejectionCause,
    )

    /** Per-provider execution record (stable order, typed outcome). */
    data class ProviderRecord(
        val provider: ProviderRef,
        val outcome: ProviderOutcomeRecord,
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
        val terminal: GenerationTerminalOutcome,
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
     * Requires a structurally valid request (see [validateRequest]),
     * valid provider identities (see [validateProviderRef]), and at most
     * [MAX_PROVIDERS] providers. Real coroutine cancellation propagates
     * unwrapped via [currentCoroutineContext]; `shouldCancel` additionally
     * observes non-coroutine cancellation signals. Snapshots in
     * [GenerationResult] are immutable copies.
     */
    suspend fun run(
        request: GenerationRequest,
        providers: List<GeneratorProvider>,
        shouldCancel: () -> Boolean = { false },
    ): GenerationResult {
        require(validateRequest(request).isEmpty()) {
            "invalid request: ${validateRequest(request).joinToString("; ")}"
        }
        val refProblems = providers.flatMap { validateProviderRef(it.ref) }
        require(refProblems.isEmpty()) { "invalid provider: ${refProblems.joinToString("; ")}" }
        require(providers.size <= MAX_PROVIDERS) { "providers exceed bound $MAX_PROVIDERS" }
        val duplicateRefs = providers.groupingBy { it.ref }.eachCount().filterValues { it > 1 }.keys
        require(duplicateRefs.isEmpty()) { "duplicate provider ref: ${duplicateRefs.joinToString()}" }
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

        currentCoroutineContext().ensureActive()
        checkCancelled()
        for (provider in ordered) {
            currentCoroutineContext().ensureActive()
            checkCancelled()
            val outcome = try {
                provider.generate(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                ProviderOutcome.Failed(e::class.simpleName ?: "Exception")
            }
            when (outcome) {
                is ProviderOutcome.Unsupported -> records += ProviderRecord(
                    provider = provider.ref,
                    outcome = ProviderOutcomeRecord.UNSUPPORTED,
                    accepted = 0,
                    rejected = 0,
                    detail = boundReason(outcome.reason),
                )
                is ProviderOutcome.NotApplicable -> records += ProviderRecord(
                    provider = provider.ref,
                    outcome = ProviderOutcomeRecord.NOT_APPLICABLE,
                    accepted = 0,
                    rejected = 0,
                    detail = boundReason(outcome.reason),
                )
                is ProviderOutcome.Incompatible -> records += ProviderRecord(
                    provider = provider.ref,
                    outcome = ProviderOutcomeRecord.INCOMPATIBLE,
                    accepted = 0,
                    rejected = 0,
                    detail = boundReason(outcome.reason),
                )
                is ProviderOutcome.Failed -> records += ProviderRecord(
                    provider = provider.ref,
                    outcome = ProviderOutcomeRecord.FAILED,
                    accepted = 0,
                    rejected = 0,
                    detail = boundReason(outcome.error),
                )
                is ProviderOutcome.Candidates -> {
                    val list = outcome.candidates
                    if (list.isEmpty()) {
                        records += ProviderRecord(
                            provider.ref, ProviderOutcomeRecord.FAILED, 0, 0, "empty candidate set",
                        )
                        continue
                    }
                    if (list.size > MAX_PROVIDER_CANDIDATES) {
                        records += ProviderRecord(
                            provider.ref, ProviderOutcomeRecord.FAILED, 0, 0,
                            "candidate set exceeds bound $MAX_PROVIDER_CANDIDATES",
                        )
                        continue
                    }
                    var localAccepted = 0
                    var localRejected = 0
                    var normalized = 0
                    var diagnosticsDropped = 0
                    val details = mutableListOf<String>()
                    val orderOk = list.mapIndexed { index, c -> c.ordinal == index }.all { it }
                    if (!orderOk) {
                        for (c in list) {
                            rejected += CandidateRejection(
                                boundId(c.candidateId), provider.ref,
                                "non-sequential ordinal", RejectionCause.NON_SEQUENTIAL_ORDINAL,
                            )
                            localRejected++
                        }
                        records += ProviderRecord(
                            provider.ref, ProviderOutcomeRecord.FAILED,
                            0, localRejected, "non-sequential ordinals",
                        )
                        continue
                    }
                    for (candidate in list) {
                        if (remaining <= 0) {
                            truncated++
                            continue
                        }
                        if (candidate.candidateId.isBlank()) {
                            rejected += CandidateRejection(
                                candidate.candidateId, provider.ref,
                                "blank id", RejectionCause.BLANK_ID,
                            )
                            localRejected++
                            continue
                        }
                        if (candidate.candidateId.length > MAX_ID_CHARS) {
                            rejected += CandidateRejection(
                                boundId(candidate.candidateId), provider.ref,
                                "bad id", RejectionCause.BAD_ID,
                            )
                            localRejected++
                            continue
                        }
                        // Deep-detach provider-owned nested containers BEFORE
                        // any check or accept: later provider-side mutation of
                        // photos/placements/diagnostics must not reach the
                        // accepted set. Content-identical, so validity and
                        // hashes are unaffected (no canonical byte change).
                        val detached = detach(candidate.expression)
                        // Validity BEFORE duplicate tracking: an invalid-first
                        // record must never suppress a later valid candidate.
                        val validity = GeneratorExpression.validateResolved(detached)
                        if (validity is GeneratorExpression.InputValidation.Invalid) {
                            rejected += CandidateRejection(
                                candidate.candidateId,
                                provider.ref,
                                "invalid expression: ${boundReason(validity.reasons.joinToString("; "))}",
                                RejectionCause.INVALID_EXPRESSION,
                            )
                            localRejected++
                            continue
                        }
                        // Strictly positive dependency versions (orchestrator
                        // ruling): zero and negative rejected here; unknown
                        // positive versions pass through recorded. G1 itself
                        // is unchanged (versions opaque at that layer).
                        val negative = detached.grammarVersion < 0 ||
                            detached.fontVersion < 0 ||
                            detached.paletteVersion < 0
                        val zero = detached.grammarVersion == 0 ||
                            detached.fontVersion == 0 ||
                            detached.paletteVersion == 0
                        if (negative || zero) {
                            val (cause, code) = if (negative) {
                                "negative version" to RejectionCause.NEGATIVE_VERSION
                            } else {
                                "zero version" to RejectionCause.ZERO_VERSION
                            }
                            rejected += CandidateRejection(
                                candidate.candidateId, provider.ref, cause, code,
                            )
                            localRejected++
                            continue
                        }
                        if (detached.input != request.input) {
                            rejected += CandidateRejection(
                                candidate.candidateId, provider.ref,
                                "input mismatch", RejectionCause.INPUT_MISMATCH,
                            )
                            localRejected++
                            continue
                        }
                        if (!seenIds.add(candidate.candidateId)) {
                            rejected += CandidateRejection(
                                candidate.candidateId, provider.ref,
                                "duplicate id", RejectionCause.DUPLICATE_ID,
                            )
                            localRejected++
                            continue
                        }
                        var stored = detached
                        if (stored.diagnostics.size > MAX_DIAGNOSTICS) {
                            diagnosticsDropped += stored.diagnostics.size - MAX_DIAGNOSTICS
                            stored = stored.copy(diagnostics = stored.diagnostics.take(MAX_DIAGNOSTICS))
                        }
                        // Dispatcher-owned attribution: stamp the executing
                        // provider and RECORD any mismatch (never launder).
                        if (candidate.provider != provider.ref) normalized++
                        accepted += candidate.copy(provider = provider.ref, expression = stored)
                        localAccepted++
                        remaining--
                    }
                    if (normalized > 0) details += "attribution normalized: $normalized"
                    if (diagnosticsDropped > 0) details += "diagnostics truncated: dropped=$diagnosticsDropped"
                    records += ProviderRecord(
                        provider = provider.ref,
                        outcome = ProviderOutcomeRecord.CANDIDATES,
                        accepted = localAccepted,
                        rejected = localRejected,
                        detail = details.joinToString("; "),
                    )
                }
            }
        }
        // Terminal outcome: non-empty accepted set wins; an empty set with
        // at least one incompatible provider is typed Incompatible (never
        // an empty success); otherwise Empty. Incompatible never suppressed
        // later providers — enumeration above always continued.
        val terminal = when {
            accepted.isNotEmpty() -> GenerationTerminalOutcome.SUCCESS
            records.any { it.outcome == ProviderOutcomeRecord.INCOMPATIBLE } ->
                GenerationTerminalOutcome.INCOMPATIBLE
            else -> GenerationTerminalOutcome.EMPTY
        }
        return GenerationResult(
            generationId = request.generationId,
            seed = request.seed,
            inputHash = GeneratorExpression.canonicalHash(request.input),
            accepted = accepted.toList(),
            rejected = rejected.toList(),
            providers = records.toList(),
            truncated = truncated,
            terminal = terminal,
        )
    }

    /**
     * Deep-detaches provider-owned nested containers (input photos,
     * placements, diagnostics) into fresh lists. Data classes themselves
     * are immutable; only the containers are provider-reachable. Content
     * is unchanged, so canonical bytes and hashes are unaffected.
     */
    private fun detach(expression: GeneratorExpression.ResolvedExpression) =
        expression.copy(
            input = expression.input.copy(photos = expression.input.photos.toList()),
            placements = expression.placements.toList(),
            diagnostics = expression.diagnostics.toList(),
        )

    /** Bounds rejection ids (blank stays blank-flagged, long truncated). */
    private fun boundId(candidateId: String): String =
        if (candidateId.length <= MAX_ID_CHARS) candidateId
        else candidateId.take(MAX_ID_CHARS) + "…[truncated]"
}
