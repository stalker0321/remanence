import type { MessageKey } from '../i18n/catalog'
import type { SendOutcome } from './sendAttempt'

/** Presentation only. Never infer rollback from a received draft error, and
 * never display a transport/server code or change the attempt's lifecycle. */
export function sendFeedback(outcome: SendOutcome | null): {
  notice: MessageKey
  help: MessageKey | null
} | null {
  if (outcome === null || outcome.kind === 'stale') return null
  if (outcome.kind === 'sent') return { notice: 'sendSent', help: null }
  if (outcome.kind === 'access-denied') return { notice: 'sendLimitReached', help: 'sendLimitHelp' }
  if (outcome.kind === 'access-unavailable') return { notice: 'sendAccessUnavailable', help: 'sendAccessUnavailableHelp' }
  if (outcome.kind === 'unknown') return { notice: 'sendNotConfirmed', help: 'sendUncertainHelp' }
  return outcome.stage === 'draft'
    ? { notice: 'sendDraftFailed', help: 'sendUncertainHelp' }
    : { notice: 'sendNotSent', help: 'sendLocalHelp' }
}
