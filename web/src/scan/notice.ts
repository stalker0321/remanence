import type { MessageKey } from '../i18n/catalog'
import { CollageError } from '../collage/errors'
import { SyncError } from '../sync/errors'
import { ScanError } from './errors'

export function explainScan(error: unknown): MessageKey {
  if (error instanceof ScanError) {
    switch (error.code) {
      case 'EMPTY':
        return 'emptyIndex'
      case 'NO_MATCH':
        return 'noMatch'
      case 'AMBIGUOUS':
        return 'ambiguousMatch'
      case 'BODY_MISSING':
        return 'bodyMissing'
      case 'RECOGNITION_UNAVAILABLE':
        return 'recognitionUnavailable'
      case 'GRANT_MISMATCH':
        return 'keysLocked'
      case 'CANCELLED':
      case 'STALE':
        return 'scanCancelled'
      default:
        return 'cameraUnavailable'
    }
  }
  if (error instanceof CollageError) {
    if (error.code === 'STALE') return 'scanCancelled'
    if (error.code === 'GRANT') return 'keysLocked'
    return 'integrityFailed'
  }
  if (error instanceof SyncError) {
    if (error.code === 'HASH_MISMATCH') return 'integrityFailed'
    if (error.code === 'STALE' || error.code === 'CANCELLED') return 'scanCancelled'
    return 'syncRejected'
  }
  return 'syncRejected'
}
