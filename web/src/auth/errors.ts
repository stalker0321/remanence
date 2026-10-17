export const authCodes = [
  'CANCELLED',
  'CLIENT_UPGRADE_REQUIRED',
  'DIRECTORY_REJECTED',
  'EXISTING_ACCOUNT_REQUIRES_LINK',
  'GOOGLE_CANCELLED',
  'GOOGLE_CLIENT_UNAVAILABLE',
  'GOOGLE_REJECTED',
  'HANDLE_INVALID',
  'IDENTITY_ABSENT',
  'HANDLE_TAKEN',
  'ILLEGAL_PHASE',
  'MUSIC_REJECTED',
  'MUSIC_UNAVAILABLE',
  'REGISTRATION_REJECTED',
  'PASSKEY_CANCELLED',
  'PASSKEY_UNAVAILABLE',
  'PRF_UNAVAILABLE',
  'RECOVERY_NOT_STARTED',
  'RECOVERY_REJECTED',
  'SELF_REJECTED',
  'STALE',
  'TRANSPORT_FAILED',
  'WRONG_ACCOUNT',
  'WRONG_CREDENTIAL',
] as const

export type AuthCode = (typeof authCodes)[number]

export class AuthError extends Error {
  constructor(readonly code: AuthCode) {
    super(code)
    this.name = 'AuthError'
  }
}
