export const scanCodes = [
  'ADMISSION_AUTH_REQUIRED',
  'ADMISSION_DENIED',
  'ADMISSION_UNAVAILABLE',
  'AMBIGUOUS',
  'BODY_MISSING',
  'CAMERA_DENIED',
  'CAMERA_UNAVAILABLE',
  'CANCELLED',
  'EMPTY',
  'GRANT_MISMATCH',
  'NO_MATCH',
  'RECOGNITION_UNAVAILABLE',
  'STALE',
] as const

export type ScanCode = (typeof scanCodes)[number]

export class ScanError extends Error {
  /** AMBIGUOUS only: the capsules the same postcard could open, for the recipient to choose. */
  constructor(readonly code: ScanCode, readonly choices: readonly string[] = []) {
    super(code)
    this.name = 'ScanError'
  }
}
