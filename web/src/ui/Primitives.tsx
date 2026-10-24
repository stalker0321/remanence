import type { MessageKey } from '../i18n/catalog'

export function Icon({ name }: { name: 'arrow' | 'key' | 'scan' | 'postcard' | 'back' | 'check' | 'history' | 'settings' }) {
  return <svg className="ui-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    {name === 'arrow' ? <path d="M4 12h16m-6-6 6 6-6 6" /> : null}
    {name === 'back' ? <path d="M20 12H4m6-6-6 6 6 6" /> : null}
    {name === 'key' ? <><circle cx="8" cy="9" r="4" /><path d="m11 12 9 9m-4-4 3-3m-6 0 3-3" /></> : null}
    {name === 'scan' ? <><path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5" /><rect x="7" y="7" width="10" height="10" rx="1" /><path d="m7 15 3-3 3 3 2-2 2 2" /></> : null}
    {name === 'postcard' ? <><rect x="2" y="5" width="20" height="14" rx="2" /><path d="M12 8v8m3-7h4m-4 4h4M5 15l2-3 3 3" /></> : null}
    {name === 'check' ? <path d="m5 12 4 4L19 6" /> : null}
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
