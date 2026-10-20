import { useEffect, useRef, useState } from 'react'
import type { ApiTransport } from '../auth/http'
import type { MessageKey } from '../i18n/catalog'
import type { SessionStore } from '../session/session'
import { fetchSendAccess, type AccessResult } from './api'

/** Keyed by owner/generation in App. Read-only, no relation to send admission. */
export function SendAccessSection({ store, transport, t }: {
  store: SessionStore; transport: ApiTransport; t: (key: MessageKey) => string
}) {
  const owner = useRef(store.getSnapshot())
  const mounted = useRef(false)
  const request = useRef<AbortController | null>(null)
  const [state, setState] = useState<AccessResult | { kind: 'loading' | 'unavailable' }>({ kind: 'loading' })
  const alive = () => mounted.current && store.getSnapshot().generation === owner.current.generation
    && store.getSnapshot().accountId === owner.current.accountId && store.secretsFor(owner.current.generation) !== null
  async function load() {
    if (!alive()) return
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setState({ kind: 'loading' })
    try {
      const result = await fetchSendAccess(store, transport, controller.signal)
      if (alive() && request.current === controller) setState(result)
    } catch {
      if (alive() && request.current === controller) setState({ kind: 'unavailable' })
    } finally {
      if (request.current === controller) request.current = null
    }
  }
  useEffect(() => {
    mounted.current = true
    void load()
    return () => { mounted.current = false; request.current?.abort() }
    // Owner/generation key makes a session transition a new component.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  if (state.kind === 'hidden') return null
  const access = state.kind === 'available' ? state.access : null
  return <section className="card settings-group send-access" data-testid="send-access" aria-busy={state.kind === 'loading'}>
    <h2>{t('accessTitle')}</h2>
    {state.kind === 'loading' ? <p role="status">{t('accessLoading')}</p> : null}
    {state.kind === 'unavailable' ? <p role="alert">{t('accessUnavailable')}</p> : null}
    {access ? <>
      {access.mode === 'record' ? <p data-testid="access-record">{t('accessRecord')}</p>
        : access.lifetime ? <><p data-testid="access-lifetime">{t('accessLifetime')}</p><p>{t('accessLifetimeScope')}</p></>
          : <><dl className="settings-facts">
            <dt>{t('accessSelf')}</dt><dd data-testid="access-self">{access.free.self.remaining} / 2 {t('accessRemaining')}</dd>
            <dt>{t('accessOther')}</dt><dd data-testid="access-other">{access.free.other.remaining} / 2 {t('accessRemaining')}</dd>
          </dl><p>{t('accessSeparate')}</p></>}
      <p>{t('accessAuthority')}</p>
    </> : null}
    <button type="button" data-testid="access-refresh" aria-disabled={state.kind === 'loading'} onClick={() => {
      // Keep keyboard focus during a read; aria-disabled is backed by this
      // synchronous request guard, including repeated Enter before React paints.
      if (request.current === null && state.kind !== 'loading') void load()
    }}>
      {t(state.kind === 'unavailable' ? 'accessRetry' : 'accessRefresh')}
    </button>
  </section>
}
