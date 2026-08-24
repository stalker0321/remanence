package app.postmark.memory.wiring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.postmark.memory.AppContainer
import app.postmark.memory.session.RootViewModel
import app.postmark.memory.ui.create.CreateViewModel
import app.postmark.memory.ui.scan.ScanViewModel
import app.postmark.memory.ui.home.HomeCapabilityViewModel
import app.postmark.memory.ui.auth.LoginViewModel
import app.postmark.memory.ui.auth.RegistrationViewModel
import java.io.File

/**
 * FIX-M1-007-08: single Compose-facing factory so every screen ViewModel is
 * lifecycle-owned (viewModelScope) and wired from the one AppContainer.
 */
class PostmarkViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        RootViewModel::class.java -> RootViewModel(
            sessionBootstrap = container.sessionBootstrap,
            logoutAction = { container.logoutUseCase.logout() },
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
                    is postmark.core.crypto.IdentityBundleRepository.LoadResult.Available ->
                        app.postmark.memory.ui.create.SenderIdentitySnapshot(
                            userId = row.userId,
                            handle = row.handleNormalized,
                            activeKeyBundleId = row.activeKeyBundleId,
                            encryptionPrivateHandle = loaded.encryptionHandle,
                            signingPrivateHandle = loaded.signingHandle,
                        )
                    postmark.core.crypto.IdentityBundleRepository.LoadResult.RecoveryRequired -> null
                }
            },
            persistence = container.fingerprintPersistence,
            outboxStager = postmark.core.data.outbox.CapsuleOutboxStager(
                container.database,
                File(container.appFilesRoot, "outbox-ciphertext"),
            ),
            profile = postmark.core.recognition.RecognitionProfile.mvpOrbV1(),
            stagingDirectory = File(container.appFilesRoot, "create-staging"),
            openPhotoSource = { pickerId ->
                val uri = android.net.Uri.parse(pickerId)
                app.postmark.memory.create.PhotoSource {
                    container.appContext.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("photo picker stream unavailable")
                }
            },
        ) as T
        ScanViewModel::class.java -> ScanViewModel(
            persistence = container.fingerprintPersistence,
            database = container.database,
            profile = postmark.core.recognition.RecognitionProfile.mvpOrbV1(),
            identityProvider = {
                val row = container.currentAccountStore.loadEntity() ?: return@ScanViewModel null
                when (val loaded = container.identityRepository.load()) {
                    is postmark.core.crypto.IdentityBundleRepository.LoadResult.Available ->
                        app.postmark.memory.ui.create.SenderIdentitySnapshot(
                            userId = row.userId,
                            handle = row.handleNormalized,
                            activeKeyBundleId = row.activeKeyBundleId,
                            encryptionPrivateHandle = loaded.encryptionHandle,
                            signingPrivateHandle = loaded.signingHandle,
                        )
                    postmark.core.crypto.IdentityBundleRepository.LoadResult.RecoveryRequired -> null
                }
            },
            signingPublicExports = {
                when (val exports = container.identityRepository.loadPublicExports()) {
                    is postmark.core.crypto.IdentityBundleRepository.PublicExportsResult.Available ->
                        exports.signingPublicKeyset
                    postmark.core.crypto.IdentityBundleRepository.PublicExportsResult.RecoveryRequired -> null
                }
            },
            grantsClockMillis = { System.currentTimeMillis() },
        ) as T
        else -> throw IllegalArgumentException("unknown ViewModel: ${modelClass.name}")
    }
}
