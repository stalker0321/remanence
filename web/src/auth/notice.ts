import type { MessageKey } from '../i18n/catalog'
import { AuthError } from './errors'
import type { GoogleOutcome } from './google'
import type { RestoreOutcome } from './restore'

export function explainGoogle(outcome: GoogleOutcome): MessageKey | null {
  if (outcome.kind === 'signed-in') return null
  if (outcome.kind === 'existing-account-requires-link') return 'linkRequired'
  if (outcome.kind === 'client-upgrade-required') return 'upgradeRequired'
  return 'needsHandle'
}

export function explainRestore(outcome: RestoreOutcome): MessageKey | null {
  if (outcome.kind === 'unlocked') return null
  return 'recoveryPending'
}

export function explainFailure(error: unknown): MessageKey {
  if (!(error instanceof AuthError)) return 'authFailed'
  switch (error.code) {
    case 'EXISTING_ACCOUNT_REQUIRES_LINK':
      return 'linkRequired'
    case 'CLIENT_UPGRADE_REQUIRED':
      return 'upgradeRequired'
    case 'GOOGLE_CANCELLED':
      return 'googleCancelled'
    case 'GOOGLE_CLIENT_UNAVAILABLE':
      return 'googleUnavailable'
    case 'HANDLE_INVALID':
      return 'handleInvalid'
    case 'IDENTITY_ABSENT':
      return 'identityAbsent'
    case 'HANDLE_TAKEN':
      return 'handleTaken'
    case 'REGISTRATION_REJECTED':
      return 'registrationFailed'
    case 'PASSKEY_CANCELLED':
      return 'passkeyCancelled'
    case 'PRF_UNAVAILABLE':
      return 'prfUnavailable'
    case 'PASSKEY_UNAVAILABLE':
      return 'passkeyUnavailable'
    case 'RECOVERY_NOT_STARTED':
      return 'recoveryNotStarted'
    case 'WRONG_CREDENTIAL':
    case 'WRONG_ACCOUNT':
      return 'wrongPasskey'
    default:
      return 'authFailed'
  }
}
