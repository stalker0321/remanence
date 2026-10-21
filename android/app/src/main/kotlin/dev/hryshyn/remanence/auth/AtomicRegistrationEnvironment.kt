package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto
import dev.hryshyn.remanence.core.data.network.AtomicRegistrationRepository
import dev.hryshyn.remanence.core.data.network.LoginResponseDto
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

internal fun atomicEnvironment(container: AppContainer): AtomicEnvironment {
    val coordinator = container.apiStack.sessionRefreshCoordinator
    return AtomicEnvironment(
        api = AtomicRegistrationRepository(container.apiBaseUrl),
        capture = {
            container.sessionReplacement.acquireLease()
        },
        isCurrent = { lease -> coordinator.acquireAccountLease() == lease && coordinator.rawAccessToken() == null },
        read = { token, path ->
            // Not an installed normal session yet. This bare recovery path sends only
            // the just-issued login's token, never a captured setup capability.
            require(path == "status" || path == "record")
            container.apiStack.recoveryRepository.request(path, token = token)
        },
        publish = { lease, login, restored, current ->
            publishAtomic(container, lease, login, restored, current)
        },
        isPublished = { login -> coordinator.rawAccessToken() == login.accessToken },
    )
}

private suspend fun publishAtomic(container: AppContainer, lease: Long, login: LoginResponseDto,
    restored: AccountRecoveryCrypto.Restored?, publication: AtomicPublication) {
    val coordinator = container.apiStack.sessionRefreshCoordinator
    val current = publication.isCurrent
    var published = false
    try {
        container.sessionReplacement.replaceGuarded(lease, UserId.parseRest(login.user.userId), login.accessToken, login.refreshToken, current, publication.install) {
            check(current())
            withContext(Dispatchers.IO) {
                check(coordinator.commitLocalRecovery(lease) {
                    publication.install {
                        if (restored != null) {
                            if (!container.kekBoundary.hasKey(container.identityKekAlias)) container.kekBoundary.createAes256GcmKey(container.identityKekAlias)
                            // Only permanent GET/unwrap output, using existing KEK/no-overwrite store.
                            container.ownerIdentityStores.installRecovered(login.user.userId, container.identityKekAlias, restored)
                        } else {
                            // Existing-account compatibility: resolve only an already matching
                            // staged/legacy identity. Never generate a new one on sign-in.
                            try {
                                container.registrationIdentityAdapter.bindVerifiedLogin(null, login.user.userId, login.activeKeyBundle.keyBundleId)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Existing login stays authenticated/restore-required. */ }
                        }
                    }
                })
            }
            check(current())
            container.currentAccountStore.record(login.user.userId, login.user.handle, login.activeKeyBundle.keyBundleId)
            withContext(Dispatchers.IO) {
                check(coordinator.commitLocalRecovery(lease) {
                    publication.install {
                        if (restored != null) container.recoveryReadiness.markVerified(login.user.userId, login.activeKeyBundle.keyBundleId)
                    }
                })
            }
        }
        published = true
        coordinator.commitLocalRecovery(lease) {
            if (coordinator.rawAccessToken() == login.accessToken) {
                container.sessionIdHolder.set(UserId.parseRest(login.user.userId), login.sessionId)
            }
        }
    } finally {
        if (!published) withContext(NonCancellable) {
            withTimeoutOrNull(5000) { runCatching { container.bareAuthRepository.logout(login.accessToken) } }
        }
    }
}
