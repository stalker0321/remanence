import { useEffect, useRef, useState } from 'react'
import type { ApiTransport } from '../auth/http'
import type { Language, MessageKey } from '../i18n/catalog'
import { href } from '../router/route'
import type { SessionStore } from '../session/session'
import { cancelOutgoing, HistoryError, historyKey, outgoing, type HistoryItem, type HistoryStatus } from './api'
import { Icon } from '../ui/Primitives'

type Translate = (key: MessageKey) => string
const statuses: Record<HistoryStatus, MessageKey> = {
  DRAFT: 'historyDraft', ABORTED: 'historyAborted', READY: 'historyReady', OPENED: 'historyOpenedState',
  REVOKED: 'historyRevokedState', PENDING: 'historyPending', CLAIMED: 'historyClaimed',
  CANCELLED: 'historyCancelledState', EXPIRED: 'historyExpired', UNSUPPORTED: 'historyUnsupported',
}
/** Mounted with an owner/generation key: private rows never survive a session change. */
export function HistoryPage({ store, transport, t, language }: {
  store: SessionStore; transport: ApiTransport; t: Translate; language: Language
}) {
  const owner = useRef(store.getSnapshot())
  const heading = useRef<HTMLHeadingElement>(null)
  const request = useRef<AbortController | null>(null)
  const mounted = useRef(false)
  const [rows, setRows] = useState<HistoryItem[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(owner.current.accountId !== null && owner.current.phase !== 'signed-out')
  const [failed, setFailed] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [notice, setNotice] = useState<MessageKey | null>(null)
  const signedIn = owner.current.accountId !== null && owner.current.phase !== 'signed-out'
  const alive = () => mounted.current && store.getSnapshot().generation === owner.current.generation
    && store.getSnapshot().accountId === owner.current.accountId

  async function load(next: string | null) {
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setLoading(true)
    setFailed(false)
    // A refresh must not leave stale cancellation buttons or active codes visible.
    if (next === null) { setRows([]); setCursor(null) }
    try {
      const page = await outgoing(store, transport, next, controller.signal)
      if (!alive() || request.current !== controller) return
      if (next !== null && (page.next_cursor === next || page.items.some((item) => rows.some((old) => historyKey(old) === historyKey(item))))) throw new HistoryError('invalid')
      setRows(next === null ? page.items : [...rows, ...page.items])
      setCursor(page.next_cursor)
    } catch {
      if (alive() && request.current === controller) setFailed(true)
    } finally {
      if (alive() && request.current === controller) { request.current = null; setLoading(false) }
    }
  }
  useEffect(() => {
    mounted.current = true
    heading.current?.focus()
    if (signedIn) void load(null)
    return () => { mounted.current = false; request.current?.abort() }
    // This component is keyed by the entire session generation in App.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function cancel(row: HistoryItem) {
    // Ref guard also fences double clicks before React commits disabled buttons.
    if (request.current || !alive()) return
    const controller = new AbortController()
    request.current = controller
    setCancelling(true)
    setNotice('historyCancelling')
    try {
      await cancelOutgoing(store, transport, row, controller.signal)
      if (!alive()) return
      setNotice('historyCancelled')
    } catch (error) {
      if (!alive()) return
      setNotice(error instanceof HistoryError && error.kind === 'conflict' ? 'historyConflict' : 'historyUnknown')
    } finally {
      if (alive()) {
        request.current = null
        setCancelling(false)
        heading.current?.focus()
        void load(null)
      }
    }
  }
  return <section className="card history" data-testid="history-page">
    <header className="page-heading"><h1 ref={heading} tabIndex={-1}>{t('historyTitle')}</h1>
    <p className="page-lead">{t('historyBody')}</p></header>
    {signedIn ? <div className="history-toolbar"><button type="button" disabled={loading || cancelling} onClick={() => { setNotice(null); void load(null) }}>{t('historyRefresh')}</button><a href={href('create')}>{t('make')}<Icon name="arrow" /></a></div> : null}
    {!signedIn ? <div className="empty-state"><Icon name="postcard" /><p>{t('historyAuth')}</p><div className="actions"><a className="button primary" href={href('account')}>{t('signInToHistory')}<Icon name="arrow" /></a></div></div> : <>
      {notice ? <p role="status" data-testid="history-result">{t(notice)}</p> : null}
      {loading ? <p role="status">{t('historyLoading')}</p> : null}
      {failed ? <div role="alert"><p>{t('historyUnavailable')}</p><button type="button" disabled={loading || cancelling} onClick={() => { void load(cursor) }}>{t('historyRetry')}</button></div> : null}
      {!loading && !failed && rows.length === 0 ? <div className="empty-state"><Icon name="postcard" /><h2>{t('historyEmptyTitle')}</h2><p data-testid="history-empty">{t('historyEmpty')}</p><div className="actions"><a className="button primary" href={href('create')}>{t('make')}<Icon name="arrow" /></a></div></div> : null}
      <ol className="history-list" aria-busy={loading || cancelling}>
        {rows.map((row) => <HistoryRow key={historyKey(row)} row={row} t={t} language={language}
          disabled={loading || cancelling || failed} alive={alive} onCancel={() => { void cancel(row) }} />)}
      </ol>
      {cursor && !failed ? <button type="button" disabled={loading || cancelling} onClick={() => { void load(cursor) }}>{t('historyMore')}</button> : null}
    </>}
  </section>
}

function HistoryRow({ row, t, language, disabled, alive, onCancel }: {
  row: HistoryItem; t: Translate; language: Language; disabled: boolean; alive: () => boolean; onCancel: () => void
}) {
  const [confirm, setConfirm] = useState(false)
  const [copy, setCopy] = useState<MessageKey | null>(null)
  const keep = useRef<HTMLButtonElement>(null)
  const cancel = useRef<HTMLButtonElement>(null)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  useEffect(() => { if (confirm) keep.current?.focus() }, [confirm])
  const date = (value: string) => <time dateTime={value}>{new Date(value).toLocaleString(language, { dateStyle: 'medium', timeStyle: 'short' })}</time>
  const dates: [MessageKey, string | null][] = [
    ['historyCreated', row.created_at], ['historyPublished', row.published_at], ['historyExpires', row.expires_at],
    ['historyOpened', row.first_opened_at], ['historyRevoked', row.revoked_at], ['historyDeadline', row.cancel_deadline],
  ]
  const code = row.locator.code
  return <li data-history-key={historyKey(row)}>
    <details onToggle={(event) => { if (!event.currentTarget.open) { setConfirm(false); setCopy(null) } }}>
      <summary><span>{row.recipient.is_self ? t('historySelf') : row.recipient.handle ? `@${row.recipient.handle}` : row.recipient.hint ?? t('historyRecipient')}</span>
        <span className="history-state">{t(statuses[row.status])}</span><span>{date(row.created_at)}</span></summary>
      {code ? <div className="history-code"><p>{t('historyCode')}: <code>{code}</code></p>
        <button type="button" disabled={disabled} onClick={async () => {
          if (!alive() || disabled) return
          try {
            await navigator.clipboard.writeText(code)
            if (mounted.current && alive()) setCopy('historyCopied')
          } catch { if (mounted.current && alive()) setCopy('historyCopyFailed') }
        }}>{t('historyCopy')}</button></div>
        : <p>{t(row.locator.status.startsWith('RETIRED_') ? 'historyRetiredCode' : 'historyNoCode')}</p>}
      {copy ? <p role="status">{t(copy)}</p> : null}
      {row.cancel_action ? <>
        <p>{t('historyPolicy')}</p>
        {!confirm ? <button type="button" ref={cancel} disabled={disabled} onClick={() => setConfirm(true)}>{t('historyCancel')}</button>
          : <fieldset disabled={disabled}><legend>{t('historyConfirm')}</legend><div className="actions">
            <button type="button" ref={keep} onClick={() => { setConfirm(false); setTimeout(() => cancel.current?.focus(), 0) }}>{t('historyKeep')}</button>
            <button type="button" onClick={onCancel}>{t('historyConfirmCancel')}</button>
          </div></fieldset>}
      </> : null}
      <details className="history-dates"><summary>{t('historyDates')}</summary><dl>{dates.map(([label, value]) => value ? <div key={label}><dt>{t(label)}</dt><dd>{date(value)}</dd></div> : null)}</dl></details>
    </details>
  </li>
}
