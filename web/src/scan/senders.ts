import type { ApiTransport } from '../auth/http'
import type { SessionStore } from '../session/session'

const HANDLE = /^[A-Za-z0-9_.]{3,30}$/

/**
 * Public handles for the senders behind a same-postcard choice. A sender the
 * directory cannot name stays null: the chooser then shows a neutral label.
 */
export async function senderHandles(
  session: SessionStore,
  transport: ApiTransport,
  userIds: readonly string[],
  signal: AbortSignal,
): Promise<Map<string, string | null>> {
  const view = session.getSnapshot()
  const secrets = session.secretsFor(view.generation)
  const handles = new Map<string, string | null>()
  await Promise.all([...new Set(userIds)].map(async (userId) => {
    let handle: string | null = null
    if (secrets) {
      try {
        const response = await transport.request({ path: `/v1/directory/users/${userId}`, method: 'GET', token: secrets.accessToken, signal })
        const user = (response.status === 200 ? (response.body as { user?: { user_id?: unknown; handle?: unknown } } | null)?.user : null) ?? null
        if (user && user.user_id === userId && typeof user.handle === 'string' && HANDLE.test(user.handle)) handle = user.handle
      } catch {
        handle = null
      }
    }
    handles.set(userId, handle)
  }))
  return handles
}
