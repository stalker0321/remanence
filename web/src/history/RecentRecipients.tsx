import { useEffect, useRef, useState } from 'react'
import type { ApiTransport } from '../auth/http'
import type { MessageKey } from '../i18n/catalog'
import type { SessionStore } from '../session/session'
import { recentRecipients, type RecentRecipient } from './api'

/** No request until explicitly expanded; a suggestion is never confirmation. */
export function RecentRecipients({ store, transport, t, disabled, onSelect }: {
  store: SessionStore; transport: ApiTransport; t: (key: MessageKey) => string;
  disabled: boolean; onSelect: (row: RecentRecipient) => void
}) {
  const owner = useRef(store.getSnapshot())
  const request = useRef<AbortController | null>(null)
  const mounted = useRef(false)
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState<RecentRecipient[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [loading, setLoading] = useState(false)
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; request.current?.abort() }
  }, [])
  const alive = () => mounted.current && store.getSnapshot().generation === owner.current.generation
    && store.getSnapshot().accountId === owner.current.accountId
  async function load() {
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setLoading(true)
    setFailed(false)
    try {
      const data = await recentRecipients(store, transport, controller.signal)
      if (alive() && request.current === controller) setRows(data)
    } catch {
      if (alive() && request.current === controller) setFailed(true)
    } finally {
      if (alive() && request.current === controller) { request.current = null; setLoading(false) }
    }
  }
  return <aside className="recent" data-testid="recent-recipients">
    <button type="button" aria-label={t('recentTitle')} aria-expanded={open} disabled={disabled} onClick={() => {
      setOpen(!open)
      if (!open && rows === null && !loading) void load()
    }}>{t('recentTitle')}</button>
    {open ? <div>
      <p>{t('recentBody')}</p>
      {loading ? <p role="status">{t('recentLoading')}</p> : null}
      {failed ? <div role="alert"><p>{t('recentUnavailable')}</p><button type="button" disabled={loading || disabled} onClick={() => { void load() }}>{t('historyRetry')}</button></div> : null}
      {rows?.length === 0 && !failed && !loading ? <p>{t('recentEmpty')}</p> : null}
      <ul>{rows?.map((row) => <li key={row.user_id}><button type="button" disabled={disabled || loading || failed} onClick={() => { if (alive()) onSelect(row) }}>@{row.handle}</button></li>)}</ul>
    </div> : null}
  </aside>
}
