import { AuthError } from './errors'

const ALLOWED = /^[a-z0-9_.]{3,30}$/

/** Same fold as the server: one leading @, ASCII A-Z only, then the allowed pattern. */
export function normalizeHandle(raw: string): string {
  const without = raw.startsWith('@') ? raw.slice(1) : raw
  let folded = ''
  for (const char of without) {
    folded += char >= 'A' && char <= 'Z' ? String.fromCharCode(char.charCodeAt(0) + 32) : char
  }
  if (!ALLOWED.test(folded)) throw new AuthError('HANDLE_INVALID')
  return folded
}
