import type { Ref } from 'react'
import type { MessageKey } from '../i18n/catalog'

export function Icon({ name }: { name: 'arrow' | 'key' | 'scan' | 'postcard' | 'back' | 'check' | 'history' | 'settings' | 'home' }) {
  return <svg className="ui-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    {name === 'arrow' ? <path d="M4 12h16m-6-6 6 6-6 6" /> : null}
    {name === 'back' ? <path d="M20 12H4m6-6-6 6 6 6" /> : null}
    {name === 'key' ? <><circle cx="8" cy="9" r="4" /><path d="m11 12 9 9m-4-4 3-3m-6 0 3-3" /></> : null}
    {name === 'scan' ? <><path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5" /><rect x="7" y="7" width="10" height="10" rx="1" /><path d="m7 15 3-3 3 3 2-2 2 2" /></> : null}
    {name === 'postcard' ? <><rect x="2" y="5" width="20" height="14" rx="2" /><path d="M12 8v8m3-7h4m-4 4h4M5 15l2-3 3 3" /></> : null}
    {name === 'check' ? <path d="m5 12 4 4L19 6" /> : null}
    {name === 'home' ? <path d="M4 11 12 4l8 7v9h-5v-6H9v6H4z" /> : null}
    {name === 'history' ? <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></> : null}
    {name === 'settings' ? <><path d="M4 7h10m4 0h2M4 17h4m4 0h8" /><circle cx="16" cy="7" r="2" /><circle cx="10" cy="17" r="2" /></> : null}
  </svg>
}

/** Presentation of existing state only. It never advances a controller. */
export function StepTrail({ labels, current, t }: { labels: readonly MessageKey[]; current: number; t: (key: MessageKey) => string }) {
  return <ol className="step-trail">{labels.map((label, index) => <li key={label} aria-current={index === current ? 'step' : undefined} data-complete={index < current || undefined}>
    <span className="step-number" aria-hidden="true">{index < current ? <Icon name="check" /> : index + 1}</span><span>{t(label)}</span>
  </li>)}</ol>
}

/**
 * Google's own sign-in button: white, grey outline, the four-colour G and
 * Google's approved wording. The brand rules allow no restyling into the
 * app's own buttons.
 */
export function GoogleButton({ label, busy, onClick, className, ref }: { label: string; busy?: boolean; onClick: () => void; className?: string; ref?: Ref<HTMLButtonElement> }) {
  return <button ref={ref} type="button" className={`google-button${className ? ` ${className}` : ''}`} aria-disabled={busy} onClick={onClick}>
    <svg className="google-g" viewBox="0 0 48 48" aria-hidden="true" focusable="false">
      <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
      <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
      <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
      <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
    </svg>
    <span>{label}</span>
  </button>
}
