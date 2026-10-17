export const scanCodes = [
  'AMBIGUOUS',
  'CAMERA_DENIED',
  'CAMERA_UNAVAILABLE',
  'CANCELLED',
  'GRANT_MISMATCH',
  'NO_MATCH',
  'RECOGNITION_UNAVAILABLE',
] as const

export type ScanCode = (typeof scanCodes)[number]

export class ScanError extends Error {
  constructor(readonly code: ScanCode) {
    super(code)
    this.name = 'ScanError'
  }
}
