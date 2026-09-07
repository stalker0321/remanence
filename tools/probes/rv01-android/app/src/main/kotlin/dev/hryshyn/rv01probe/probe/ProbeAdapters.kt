package dev.hryshyn.rv01probe.probe

import java.security.GeneralSecurityException

interface CandidateBackend {
    fun detect(): CapabilityDetection

    /** Returns provider output only when the backend has a genuine capability. */
    fun capabilityMaterial(context: TestPackageContext): ByteArray?

    fun storeOpaque(value: RetrievedOpaquePackage): StoreStatus

    fun retrieveOpaque(): RetrieveOutcome
}

enum class StoreStatus {
    STORED,
    UNAVAILABLE,
    FAILED,
}

interface CandidateAdapter {
    val family: CandidateFamily

    fun detect(): CapabilityDetection

    fun wrap(canary: ByteArray, context: TestPackageContext): WrapOutcome

    fun retrieve(): RetrieveOutcome

    fun unwrap(value: RetrievedOpaquePackage, expectedContext: TestPackageContext): UnwrapOutcome
}

abstract class ExperimentalCandidateAdapter(
    final override val family: CandidateFamily,
    private val backend: CandidateBackend,
) : CandidateAdapter {
    final override fun detect(): CapabilityDetection = backend.detect()

    final override fun wrap(canary: ByteArray, context: TestPackageContext): WrapOutcome {
        if (backend.detect().status != CapabilityStatus.AVAILABLE) return WrapOutcome.Unavailable
        val material = backend.capabilityMaterial(context) ?: return WrapOutcome.Unavailable
        return try {
            val packageValue = AuthenticatedTestPackage.seal(canary, material, context)
                ?: return WrapOutcome.Failed
            val opaque = RetrievedOpaquePackage(
                bytes = packageValue.encode(),
                version = packageValue.version,
                context = packageValue.context,
            )
            when (backend.storeOpaque(opaque)) {
                StoreStatus.STORED -> WrapOutcome.Stored(opaque)
                StoreStatus.UNAVAILABLE -> WrapOutcome.Unavailable
                StoreStatus.FAILED -> WrapOutcome.Failed
            }
        } finally {
            material.fill(0)
        }
    }

    final override fun retrieve(): RetrieveOutcome {
        if (backend.detect().status != CapabilityStatus.AVAILABLE) return RetrieveOutcome.Unavailable
        return backend.retrieveOpaque()
    }

    final override fun unwrap(
        value: RetrievedOpaquePackage,
        expectedContext: TestPackageContext,
    ): UnwrapOutcome {
        if (backend.detect().status != CapabilityStatus.AVAILABLE) return UnwrapOutcome.Unavailable
        if (value.version != expectedContext.version || value.context != expectedContext.purpose) {
            return UnwrapOutcome.Rejected
        }
        val material = backend.capabilityMaterial(expectedContext) ?: return UnwrapOutcome.Unavailable
        return try {
            AuthenticatedTestPackage.open(value.bytes, material, expectedContext)
                ?.let(UnwrapOutcome::Success)
                ?: UnwrapOutcome.Rejected
        } catch (_: GeneralSecurityException) {
            UnwrapOutcome.Rejected
        } finally {
            material.fill(0)
        }
    }
}

/** Placeholder for a real Block Store bridge; it cannot report success by itself. */
class BlockStoreExperimentalAdapter(
    backend: CandidateBackend = UnavailableBlockStoreBackend(),
) : ExperimentalCandidateAdapter(CandidateFamily.BLOCK_STORE, backend)

/** Placeholder for a real provider-synced WebAuthn/Credential Manager PRF bridge. */
class PrfExperimentalAdapter(
    backend: CandidateBackend = UnavailablePrfBackend(),
) : ExperimentalCandidateAdapter(CandidateFamily.PRF, backend)

private fun unavailableDetection(path: RestorePath) = CapabilityDetection(
    status = CapabilityStatus.UNAVAILABLE,
    placement = CapabilityPlacement.NOT_APPLICABLE,
    backupEligibility = BackupEligibility.UNSUPPORTED,
    screenLock = ScreenLockState.NOT_APPLICABLE,
    e2ee = E2eeState.NOT_APPLICABLE,
    restorePath = path,
)

class UnavailableBlockStoreBackend : CandidateBackend {
    override fun detect(): CapabilityDetection = unavailableDetection(RestorePath.BLOCK_STORE_CLOUD)

    override fun capabilityMaterial(context: TestPackageContext): ByteArray? = null

    override fun storeOpaque(value: RetrievedOpaquePackage): StoreStatus = StoreStatus.UNAVAILABLE

    override fun retrieveOpaque(): RetrieveOutcome = RetrieveOutcome.Unavailable
}

class UnavailablePrfBackend : CandidateBackend {
    override fun detect(): CapabilityDetection = unavailableDetection(RestorePath.PRF_PROVIDER_SYNC_CLOUD)

    override fun capabilityMaterial(context: TestPackageContext): ByteArray? = null

    override fun storeOpaque(value: RetrievedOpaquePackage): StoreStatus = StoreStatus.UNAVAILABLE

    override fun retrieveOpaque(): RetrieveOutcome = RetrieveOutcome.Unavailable
}

/** In-memory only backend for deterministic local dry-runs. */
class FakeCandidateBackend(
    private val unwrapMaterial: ByteArray,
    private val detection: CapabilityDetection = CapabilityDetection(
        status = CapabilityStatus.AVAILABLE,
        placement = CapabilityPlacement.DEVICE_BOUND,
        backupEligibility = BackupEligibility.NOT_APPLICABLE,
        screenLock = ScreenLockState.NOT_APPLICABLE,
        e2ee = E2eeState.NOT_APPLICABLE,
        restorePath = RestorePath.LOCAL_DRY_RUN,
    ),
) : CandidateBackend {
    private var stored: RetrievedOpaquePackage? = null

    override fun detect(): CapabilityDetection = detection

    override fun capabilityMaterial(context: TestPackageContext): ByteArray = unwrapMaterial.copyOf()

    override fun storeOpaque(value: RetrievedOpaquePackage): StoreStatus {
        stored = value.copy(bytes = value.bytes.copyOf())
        return StoreStatus.STORED
    }

    override fun retrieveOpaque(): RetrieveOutcome =
        stored?.let { RetrieveOutcome.Retrieved(it.copy(bytes = it.bytes.copyOf())) }
            ?: RetrieveOutcome.Failed
}
