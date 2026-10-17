import { catalog, type Language, type MessageKey } from './catalog'

export function translate(language: Language, key: MessageKey): string {
  return catalog[language][key]
}

export function phaseKey(phase: string): MessageKey {
  switch (phase) {
    case 'authenticated':
      return 'phaseAuthenticated'
    case 'keys-unlocked':
      return 'phaseUnlocked'
    case 'postcard-matched':
      return 'phaseMatched'
    case 'presentation-admitted':
      return 'phaseAdmitted'
    default:
      return 'phaseSignedOut'
  }
}
