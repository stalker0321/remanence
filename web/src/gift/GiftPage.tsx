import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import type { ApiTransport } from '../auth/http'
import type { MemoryKeys } from '../auth/memoryKeys'
import type { MessageKey } from '../i18n/catalog'
import { href } from '../router/route'
import type { SessionStore } from '../session/session'
import { Icon } from '../ui/Primitives'
import { claimCardGift, type GiftDto, type ServicePin } from './claim'
import { parseCode, type ParsedCode } from './cardSeal'
import { DIAL_CODES, defaultCountry, toE164 } from './phone'

type Step = 'code' | 'phone' | 'sms' | 'opening' | 'wrong-code' | 'done' | 'failed' | 'none'

// Survives the trip to sign in and back, and nothing else: never a URL or storage.
let heldCode: ParsedCode | null = null

/** ADR-020: one enters the code from the postcard, then proves the number. */
export function GiftPage({ t, store, keys, transport, pin, language }: {
  t: (key: MessageKey) => string
  store: SessionStore
  keys: MemoryKeys
  transport: ApiTransport
  pin: ServicePin
  language: string
}) {
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [typed, setTyped] = useState('')
  const [code, setCode] = useState<ParsedCode | null>(heldCode)
  const [step, setStep] = useState<Step>(heldCode ? 'phone' : 'code')
  const [phone, setPhone] = useState('')
  const [country, setCountry] = useState(() => defaultCountry(navigator.language))
  const [sms, setSms] = useState('')
  const [proofId, setProofId] = useState<string | null>(null)
  const [gifts, setGifts] = useState<GiftDto[]>([])
  const [invalid, setInvalid] = useState<MessageKey | null>(null)
  const [busy, setBusy] = useState(false)
  const abort = useRef<AbortController | null>(null)
  useEffect(() => () => abort.current?.abort(), [])
  const countries = useMemo(() => {
    const names = new Intl.DisplayNames([language], { type: 'region' })
    return Object.keys(DIAL_CODES).map((id) => ({ id, name: names.of(id) ?? id })).sort((a, b) => a.name.localeCompare(b.name, language))
  }, [language])
  const unlocked = view.phase === 'keys-unlocked'

  const token = (): string | null => store.secretsFor(store.getSnapshot().generation)?.accessToken ?? null

  function takeCode() {
    const parsed = parseCode(typed)
    if (!parsed) { setInvalid('giftCodeInvalid'); return }
    heldCode = parsed
    setCode(parsed)
    setInvalid(null)
    return parsed
  }

  async function open(secret: string, found: GiftDto[]) {
    setStep('opening')
    const controller = new AbortController()
    abort.current = controller
    try {
      const outcome = await claimCardGift({ session: store, keys, transport, pin, secret, gifts: found, signal: controller.signal })
      if (outcome === 'claimed') { heldCode = null; setStep('done') }
      else if (outcome === 'wrong-code') setStep('wrong-code')
      else setStep('failed')
    } catch {
      if (!controller.signal.aborted) setStep('failed')
    }
  }

  async function askSms() {
    const number = toE164(phone, country)
    if (!number) { setInvalid('giftPhoneInvalid'); return }
    const bearer = token()
    if (!bearer) return
    setBusy(true)
    setInvalid(null)
    try {
      const started = await transport.request({
        path: '/v2/phone-proofs', method: 'POST', token: bearer, body: { phone: number, sms_text_version: 'v1' },
      })
      if (started.status === 200) {
        setProofId((started.body as { proof_id: string }).proof_id)
        setSms('')
        setStep('sms')
      } else setInvalid(started.status === 429 ? 'giftRateLimited' : 'giftPhoneInvalid')
    } catch { setInvalid('giftNetwork') } finally { setBusy(false) }
  }

  async function checkSms() {
    const bearer = token()
    if (!bearer || !proofId || !code) return
    setBusy(true)
    setInvalid(null)
    try {
      const checked = await transport.request({
        path: `/v2/phone-proofs/${proofId}/check`, method: 'POST', token: bearer, body: { code: sms.trim() },
      })
      if (checked.status === 200) {
        const found = (checked.body as { gifts: GiftDto[] }).gifts
        setGifts(found)
        if (found.length === 0) setStep('none')
        else await open(code.secret, found)
      } else setInvalid('giftSmsWrong')
    } catch { setInvalid('giftNetwork') } finally { setBusy(false) }
  }

  const error = invalid ? <p role="alert" className="error">{t(invalid)}</p> : null
  // The same three steps as the app: code from the card, number, code from the SMS.
  const stepNumber = step === 'code' || step === 'wrong-code' ? 1 : step === 'phone' && unlocked ? 2 : step === 'sms' ? 3 : null
  const heading = (title: string, lead?: string) => <header className="page-heading">
    {stepNumber ? <p className="gift-step">{t('giftStep').replace('{n}', String(stepNumber))}</p> : null}
    <h1>{title}</h1>{lead ? <p className="page-lead">{lead}</p> : null}
  </header>
  const primary = (label: MessageKey, disabled: boolean) => <div className="actions">
    <button type="submit" className="primary" disabled={disabled} aria-disabled={busy || undefined}>{t(label)}<Icon name="arrow" /></button>
  </div>

  return <section className="card gift-page recipient-picker">
    {step === 'code' || step === 'wrong-code' ? <form className="handle-form" onSubmit={async (event) => {
      event.preventDefault()
      const parsed = takeCode()
      if (!parsed) return
      if (step === 'wrong-code') await open(parsed.secret, gifts)
      else setStep('phone')
    }}>
      {heading(t(step === 'code' ? 'giftCodeTitle' : 'giftWrongCodeTitle'), t(step === 'code' ? 'giftCodeBody' : 'giftWrongCodeBody'))}
      <label>{t('giftCodeLabel')}
        <input className="gift-code-input" value={typed} onChange={(event) => { setTyped(groupCode(event.target.value)); setInvalid(null) }}
          autoComplete="off" autoCapitalize="characters" autoCorrect="off" spellCheck={false} inputMode="text" placeholder="XXX-XXX-XXX" />
      </label>
      {error}
      {primary('giftContinue', typed.replace(/-/g, '').length !== 9)}
    </form> : null}
    {step === 'phone' && code ? (!unlocked
      ? <div className="handle-form">{heading(t('giftSignInTitle'), t('giftSignInBody'))}
        <div className="actions"><a className="button primary" href={href('account')}>{t('giftSignIn')}<Icon name="arrow" /></a></div></div>
      : <form className="handle-form" onSubmit={(event) => { event.preventDefault(); void askSms() }}>
        {heading(t('giftPhoneTitle').replace('{digits}', code.hintDigits), t('giftPhoneBody'))}
        <div className="gift-phone">
          <label>{t('giftCountryLabel')}
            <select value={country} onChange={(event) => setCountry(event.target.value)}>
              {countries.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
          </label>
          <label>{t('giftPhoneLabel')}
            <input value={phone} onChange={(event) => { setPhone(event.target.value); setInvalid(null) }} type="tel" autoComplete="tel" inputMode="tel" />
          </label>
        </div>
        {error}
        {primary('giftSendSms', busy || phone.trim() === '')}
      </form>) : null}
    {step === 'sms' ? <form className="handle-form" onSubmit={(event) => { event.preventDefault(); void checkSms() }}>
      {heading(t('giftSmsTitle'), t('giftSmsBody'))}
      <label>{t('giftSmsLabel')}
        <input className="gift-sms-input" value={sms} onChange={(event) => { setSms(event.target.value.replace(/\D/g, '').slice(0, 8)); setInvalid(null) }} autoComplete="one-time-code" inputMode="numeric" />
      </label>
      {error}
      {primary('giftContinue', busy || sms.trim() === '')}
      <button type="button" className="quiet" onClick={() => setStep('phone')}>{t('giftChangeNumber')}</button>
    </form> : null}
    {step === 'opening' ? <div role="status" className="gift-opening">{heading(t('giftOpening'))}<span className="gift-spinner" aria-hidden="true" /></div> : null}
    {step === 'done' ? <div className="handle-form">{heading(t('giftDoneTitle'), t('giftDoneBody'))}
      <div className="actions"><a className="button primary" href={href('scan')}>{t('scan')}<Icon name="arrow" /></a></div></div> : null}
    {step === 'none' ? <div className="handle-form">{heading(t('giftNoneTitle'), t('giftNoneBody'))}
      <div className="actions"><button type="button" className="primary" onClick={() => setStep('phone')}>{t('giftChangeNumber')}</button></div></div> : null}
    {step === 'failed' ? <div className="handle-form">{heading(t('giftFailedTitle'), t('giftFailedBody'))}
      <div className="actions"><button type="button" className="primary" onClick={() => setStep('phone')}>{t('giftTryAgain')}</button></div></div> : null}
  </section>
}

/** X4T-P9R-K12, as written on the card: capitals, dashes after every three. */
export function groupCode(raw: string): string {
  const plain = raw.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 9)
  return plain.replace(/(.{3})(?=.)/g, '$1-')
}
