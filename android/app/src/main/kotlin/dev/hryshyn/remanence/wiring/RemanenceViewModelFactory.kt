package dev.hryshyn.remanence.wiring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.session.RootViewModel
import dev.hryshyn.remanence.ui.create.CreateViewModel
import dev.hryshyn.remanence.ui.scan.ScanViewModel
import dev.hryshyn.remanence.ui.home.HomeCapabilityViewModel
import dev.hryshyn.remanence.ui.auth.LoginViewModel
import dev.hryshyn.remanence.ui.auth.RegistrationViewModel
import java.io.File

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
            ),
            profile = dev.hryshyn.remanence.core.recognition.RecognitionProfile.mvpOrbV1(),
            // FIX-STATE-13: the staging ROOT; each publication owns
            // create-staging/<capsule UUID>/ inside it.
            stagingDirectory = File(container.appFilesRoot, "create-staging"),
            openPhotoSource = { pickerId ->
                val uri = android.net.Uri.parse(pickerId)
                dev.hryshyn.remanence.create.PhotoSource {
                    container.appContext.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("photo picker stream unavailable")
                }
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
