package dev.hryshyn.remanence.wiring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkManager
import androidx.work.await
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.session.RootViewModel
import dev.hryshyn.remanence.ui.create.CreateViewModel
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
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        RootViewModel::class.java -> RootViewModel(
            sessionBootstrap = container.sessionBootstrap,
            logoutAction = { container.logoutUseCase.logout() },
            // FIX-REVIEW-03: THE one authoritative grant lifecycle.
            grants = container.scanGrants,
            resumeCapsuleUploads = { owner ->
                container.capsuleUploadResumer.resume(owner)
            },
            scheduleIncomingSync = { owner ->
                container.scheduleIncomingSync(owner)
            },
        ) as T
        LoginViewModel::class.java -> LoginViewModel(container.loginUseCase) as T
        RegistrationViewModel::class.java -> RegistrationViewModel(container.registrationUseCase) as T
        HomeCapabilityViewModel::class.java ->
            HomeCapabilityViewModel(container.identityAvailability) as T
        CreateViewModel::class.java -> CreateViewModel(
            directory = container.directoryRepository::lookup,
            accessTokenProvider = { container.authTokenHolder.accessToken },
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
            profile = dev.hryshyn.remanence.core.recognition.RecognitionProfile.mvpOrbV1(),
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
        ) as T
        ScanViewModel::class.java -> ScanViewModel(
            persistence = container.fingerprintPersistence,
            database = container.database,
            profile = dev.hryshyn.remanence.core.recognition.RecognitionProfile.mvpOrbV1(),
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
            grantsClockMillis = { System.currentTimeMillis() },
            // FIX-REVIEW-03: issue ONLY through THE shared authoritative manager.
            grants = container.scanGrants,
        ) as T
        else -> throw IllegalArgumentException("unknown ViewModel: ${modelClass.name}")
    }
}
