export const syncCodes = [
  'CANCELLED',
  'HASH_MISMATCH',
  'ILLEGAL_PHASE',
  'INCOMPLETE',
  'OVERSIZED',
  'STALE',
  'SYNC_REJECTED',
] as const

export type SyncCode = (typeof syncCodes)[number]

export class SyncError extends Error {
  constructor(readonly code: SyncCode) {
    super(code)
    this.name = 'SyncError'
  }
}
