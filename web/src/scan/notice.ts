import type { MessageKey } from '../i18n/catalog'
import { CollageError } from '../collage/errors'
import { SyncError } from '../sync/errors'
import { ScanError } from './errors'
import { RecognitionIndexError } from './recognitionIndex'
import { RecognitionEngineError } from './recognitionEngine'

export function explainScan(error: unknown): MessageKey {
  if (error instanceof RecognitionEngineError) {
    switch (error.code) {
      case 'cancelled':
      case 'stale':
        return 'scanCancelled'
      case 'unavailable':
      case 'internal':
        return 'recognitionUnavailable'
      case 'invalid':
      case 'contract':
        return 'integrityFailed'
      case 'decode':
      case 'crop':
      case 'warp':
      case 'features':
      case 'dimensions':
      case 'pixels':
        return 'scanRetake'
      case 'incomplete':
        return 'incompleteIndex'
      default:
        return 'recognitionUnavailable'
    }
  }
  if (error instanceof RecognitionIndexError) {
    if (error.code === 'unavailable') return 'recognitionUnavailable'
    if (error.code === 'stale') return 'scanCancelled'
    return 'integrityFailed'
  }
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
    if (error.code === 'INCOMPLETE') return 'incompleteIndex'
    return 'syncRejected'
  }
  return 'syncRejected'
}
