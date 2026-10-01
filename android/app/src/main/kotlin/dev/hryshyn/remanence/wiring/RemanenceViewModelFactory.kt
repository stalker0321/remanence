package dev.hryshyn.remanence.wiring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkManager
import androidx.work.await
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.core.data.network.CoordinatedRefreshOutcome
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.create.GeneratorCreateBridge
import dev.hryshyn.remanence.session.RootViewModel
import dev.hryshyn.remanence.ui.create.CreateViewModel
import dev.hryshyn.remanence.ui.create.RecipientPickerViewModel
import dev.hryshyn.remanence.ui.scan.ScanViewModel
import dev.hryshyn.remanence.ui.home.HomeCapabilityViewModel
import dev.hryshyn.remanence.ui.auth.LoginViewModel
import dev.hryshyn.remanence.ui.auth.RegistrationViewModel
import dev.hryshyn.remanence.sync.CapsuleUploadWorker

/**
 * FIX-M1-007-08: single Compose-facing factory so every screen ViewModel is
 * lifecycle-owned (viewModelScope) and wired from the one AppContainer.
 */
class RemanenceViewModelFactory(
    private val container: AppContainer,
    /**
     * C2 factory threading (step 2 seam): per-owner C1 bridge provider for
     * the upcoming CreateViewModel session-sync step. Defaults to the
     * production container wiring (`AppContainer.generatorCreateBridge`);
     * tests may substitute a memory-backed provider. Not yet consumed —
     * CreateViewModel stays untouched until the next step.
     */
    val generatorBridgeProvider: (UserId) -> GeneratorCreateBridge.Bridge =
        container::generatorCreateBridge,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        RootViewModel::class.java -> RootViewModel(
            sessionBootstrap = container.sessionBootstrap,
            logoutAction = { container.logoutUseCase.logout() },
            invalidateSessionRefreshes = { container.sessionRefreshCoordinator.invalidate() },
            invalidateSessionBoundary = { container.sessionBoundary.invalidate() },
            // FIX-REVIEW-03: THE one authoritative grant lifecycle.
            presentationGrants = container.presentationGrants,
            resumeCapsuleUploads = { owner ->
                container.capsuleUploadResumer.resume(owner)
            },
            scheduleIncomingSync = { owner ->
                container.scheduleIncomingSync(owner)
            },
            recoverCreateStaging = { owner ->
                container.sweepCreateStagingOnce(owner)
            },
        ) as T
        LoginViewModel::class.java -> LoginViewModel(container.loginUseCase) as T
        RegistrationViewModel::class.java -> RegistrationViewModel(container.registrationUseCase) as T
        HomeCapabilityViewModel::class.java ->
            HomeCapabilityViewModel(container.identityAvailability) as T
        CreateViewModel::class.java -> CreateViewModel(
            // Admission is checked separately for friendly UI. The request itself
            // must let the shared interceptor source the current open-domain bearer.
            directory = { rawHandle -> container.directoryRepository.lookup(rawHandle) },
            accessTokenProvider = {
                container.apiStack.sessionRefreshCoordinator.openDomainAccessToken()
            },
            // BUG-014: bounded single session recovery for null-token lookup
            // starts, through the existing coordinator path (the same
            // refreshForBootstrap entry SessionBootstrap uses). No new
            // component, timer or listener; unexpected failures degrade to
            // the network copy rather than a false auth claim.
            recipientLookupSessionRefresh = {
                val ownerId = container.currentAccountStore.load()?.userId?.let { raw ->
                    runCatching { UserId.parseRest(raw) }.getOrNull()
                }
                if (ownerId == null) {
                    RecipientPickerViewModel.SessionRecovery.NO_SESSION
                } else {
                    try {
                        when (container.sessionRefreshCoordinator.refreshForBootstrap(ownerId)) {
                            is CoordinatedRefreshOutcome.Rotated,
                            is CoordinatedRefreshOutcome.Reused,
                            -> RecipientPickerViewModel.SessionRecovery.RECOVERED
                            is CoordinatedRefreshOutcome.NoToken,
                            is CoordinatedRefreshOutcome.Rejected,
                            is CoordinatedRefreshOutcome.Invalidated,
                            -> RecipientPickerViewModel.SessionRecovery.NO_SESSION
                            is CoordinatedRefreshOutcome.Unreachable,
                            is CoordinatedRefreshOutcome.Unavailable,
                            -> RecipientPickerViewModel.SessionRecovery.NETWORK_FAILED
                        }
                    } catch (_: Exception) {
                        RecipientPickerViewModel.SessionRecovery.NETWORK_FAILED
                    }
                }
            },
            capsuleRevoke = container.apiStack.capsuleRevokeRepository,
            // T062: direct MusicBrainz/CAA descriptor source for the selected
            // recording; never the backend, never a placeholder provider.
            musicArtworkResolver = container.apiStack.musicArtworkResolver,
            networkConnected = {
                val manager = container.appContext.getSystemService(
                    android.net.ConnectivityManager::class.java,
                )
                val network = manager?.activeNetwork
                val capabilities = network?.let { manager.getNetworkCapabilities(it) }
                capabilities?.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET,
                ) == true
            },
            recipientLookupOwnerProvider = {
                container.currentAccountStore.load()?.userId
            },
            recipientLookupBoundaryEpoch = container.sessionBoundary::currentEpoch,
            registerRecipientLookupBoundary = container.sessionBoundary::register,
            // C2: the per-owner C1 bridge provider for VM session-sync.
            generatorBridgeProvider = generatorBridgeProvider,
            // Production collage analysis (T002 cutover): full-resolution
            // upright originals retained by the session feed a real
            // AndroidCollageCropSource with bundled-model runners. Each build
            // owns fresh runners (the seam closes them); model loads happen
            // lazily on first analysis. Removal (null factory) keeps the
            // chooser on its typed notice path.
            collageSourceFactory = productionCollageCropSourceFactory(container.appContext),
            // Stage1-C CONTENT preview: bounded upright in-memory sources.
            generatorPreviewLoader = dev.hryshyn.remanence.create.DefaultGeneratorPreviewLoader(
                openSource = { pickerId ->
                    val uri = android.net.Uri.parse(pickerId)
                    dev.hryshyn.remanence.create.PhotoSource {
                        container.appContext.contentResolver.openInputStream(uri)
                            ?: throw java.io.IOException("photo picker stream unavailable")
                    }
                },
                decoder = container.generatorPhotoDecoder,
                normalizer = container.generatorPhotoNormalizer,
            ),
            identityProvider = {
                val row = container.currentAccountStore.loadEntity() ?: return@CreateViewModel null
                when (val loaded = container.identityRepository.load()) {
                    is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available ->
                        dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot(
                            userId = row.userId,
                            handle = row.handleNormalized,
                            activeKeyBundleId = row.activeKeyBundleId,
                            encryptionPrivateHandle = loaded.encryptionHandle,
                            signingPrivateHandle = loaded.signingHandle,
                        )
                    dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.RecoveryRequired -> null
                }
            },
            persistence = container.fingerprintPersistence,
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(
                container.database,
                // M2-P04: staged ciphertext lands in each owner's own
                // accounts/<owner>/outbox-ciphertext root.
                container.accountScopedFileRoots,
                // M2-P08: the per-account retry material store resolved from
                // the same account-scoped roots the stager already owns.
                dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore(
                    container.accountScopedFileRoots,
                ),
            ),
            profile = dev.hryshyn.remanence.core.recognition.RecognitionProfile.postcardSiftRootSiftV1(),
            // LUNA-01: plaintext staging resolves from the immutable owner
            // snapshot into accounts/<owner>/temp/create/<capsule UUID>.
            accountScopedFileRoots = container.accountScopedFileRoots,
            openPhotoSource = { pickerId ->
                val uri = android.net.Uri.parse(pickerId)
                dev.hryshyn.remanence.create.PhotoSource {
                    container.appContext.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("photo picker stream unavailable")
                }
            },
            // M2-P08: sender-retry keyset wrapper + dedicated KEK alias
            // from the AppContainer-owned sender-retry boundary.
            senderRetryKeysetWrapper = container.senderRetryKeysetWrapper,
            senderRetryKekAlias = container.senderRetryKekAlias,
            // A04: acknowledge unique upload scheduling before showing the
            // non-success staged/pending state to the user.
            enqueueUpload = { owner, capsule ->
                CapsuleUploadWorker.enqueue(
                    WorkManager.getInstance(container.appContext),
                    owner,
                    capsule,
                ).await()
            },
            outboxCapsuleDao = container.database.outboxCapsuleDao(),
        ) as T
        ScanViewModel::class.java -> ScanViewModel(
            persistence = container.fingerprintPersistence,
            database = container.database,
            profile = dev.hryshyn.remanence.core.recognition.RecognitionProfile.postcardSiftRootSiftV1(),
            identityProvider = {
                val row = container.currentAccountStore.loadEntity() ?: return@ScanViewModel null
                when (val loaded = container.identityRepository.load()) {
                    is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available ->
                        dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot(
                            userId = row.userId,
                            handle = row.handleNormalized,
                            activeKeyBundleId = row.activeKeyBundleId,
                            encryptionPrivateHandle = loaded.encryptionHandle,
                            signingPrivateHandle = loaded.signingHandle,
                        )
                    dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.RecoveryRequired -> null
                }
            },
            // FIX-REVIEW2-04: verification trusts ONLY the directory-backed
            // sender-key boundary - never storage-adjacent key material.
            trustedSenderKeys = container.trustedSenderKeys,
            // FIX-REVIEW-03: issue ONLY through THE shared presentation authority.
            presentationGrants = container.presentationGrants,
            incomingPresentationPreparation = container.incomingPresentationPreparation,
            candidateIndexProvider = { owner ->
                container.incomingSenderIndexCandidateProvider.load(owner)
            },
            observeIncomingIndexReadiness = { owner ->
                container.database.incomingCapsuleDao()
                    .observeSenderIndexCandidateCountForOwner(owner.toRestString())
            },
            scheduleIncomingSync = { owner ->
                container.scheduleIncomingSync(owner)
            },
            networkConnected = {
                val manager = container.appContext.getSystemService(
                    android.net.ConnectivityManager::class.java,
                )
                val network = manager?.activeNetwork
                val capabilities = network?.let { manager.getNetworkCapabilities(it) }
                capabilities?.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET,
                ) == true
            },
            sessionBoundary = container.sessionBoundary,
            // T118 telemetry-only frozen matcher shadow: the accepted S1->S4
            // path beside the v1 chain, local injectable switch, bounded
            // scalar diagnostics only, every decision discarded. The catalog
            // seam stays null until the lead delivers provenance-valid S5
            // bytes (T017/T081/T116: never fabricated here), so today the
            // shadow reports a bounded unavailable outcome without touching v1.
            matcherShadow = dev.hryshyn.remanence.scan.matcher.MatcherShadowRunner(
                config = dev.hryshyn.remanence.scan.matcher.MatcherShadowConfig.telemetryOnly(),
                stages = dev.hryshyn.remanence.scan.matcher.AndroidMatcherShadowStages(
                    modelBytes = {
                        try {
                            container.appContext.assets
                                .open(
                                    dev.hryshyn.remanence.core.recognition.FrozenLocalizer
                                        .MODEL_ASSET_PATH,
                                ).use { it.readBytes() }
                        } catch (_: Throwable) {
                            null
                        }
                    },
                    catalogIndex = { null },
                ),
                sink = dev.hryshyn.remanence.scan.matcher.MatcherShadowTelemetry.sink,
            ),
        ) as T
        else -> throw IllegalArgumentException("unknown ViewModel: ${modelClass.name}")
    }

}
