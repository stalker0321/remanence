export const scanCodes = [
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
  constructor(readonly code: ScanCode) {
    super(code)
    this.name = 'ScanError'
  }
}
