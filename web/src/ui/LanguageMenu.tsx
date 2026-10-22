import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import type { Language, MessageKey } from '../i18n/catalog'
import { languageOptions, wrapIndex } from './languageOptions'
import './settings.css'

export interface LanguageMenuProps {
  readonly language: Language
  readonly onSelect: (language: Language) => void
  readonly t: (key: MessageKey) => string
  readonly compact?: boolean
}

/**
 * T332 — reusable, quiet language popover.
 *
 * A single translated trigger button opens a listbox of the supported languages,
 * each offered under its own name, with a clear current selection, Escape and
 * outside-pointer close, arrow-key navigation and focus return to the trigger.
 * It persists nothing itself: `onSelect` is the caller's existing language write
 * (`writeLanguage` + `setLanguage` + `document.lang`), unchanged.
 */
export function LanguageMenu({ language, onSelect, t, compact = false }: LanguageMenuProps) {
  const options = useMemo(() => languageOptions(), [])
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([])
  const listId = useId()

  useEffect(() => {
    if (!open) return
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target
      if (rootRef.current && target instanceof Node && !rootRef.current.contains(target)) setOpen(false)
    }
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      const active = document.activeElement
      if (event.key === 'Escape' && rootRef.current && active instanceof Node && rootRef.current.contains(active)) {
        setOpen(false)
        triggerRef.current?.focus()
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  // Move focus onto the selected option when the list opens.
  useEffect(() => {
    if (!open) return
    const selected = options.findIndex((option) => option.code === language)
    optionRefs.current[selected >= 0 ? selected : 0]?.focus()
  }, [open, language, options])

  function choose(next: Language): void {
    setOpen(false)
    onSelect(next)
    triggerRef.current?.focus()
  }

  function onTriggerKeyDown(event: KeyboardEvent<HTMLButtonElement>): void {
    // Enter/Space activate the button natively; ArrowDown opens like a menu.
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setOpen(true)
    }
  }

  function onOptionKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number): void {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const step = event.key === 'ArrowDown' ? 1 : -1
      optionRefs.current[wrapIndex(index + step, options.length)]?.focus()
    }
  }

  return (
    <div className="language-menu" ref={rootRef}>
      <button
        ref={triggerRef}
        type="button"
        className="quiet language-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-label={t('language')}
        onClick={() => setOpen((value) => !value)}
        onKeyDown={onTriggerKeyDown}
      >
        {compact ? language.toUpperCase() : t('language')}
      </button>
      {open ? (
        <div className="language-list" role="listbox" id={listId} aria-label={t('language')}>
          {options.map((option, index) => (
            <button
              key={option.code}
              ref={(element) => {
                optionRefs.current[index] = element
              }}
              type="button"
              role="option"
              aria-selected={option.code === language}
              className="quiet language-option"
              onClick={() => choose(option.code)}
              onKeyDown={(event) => onOptionKeyDown(event, index)}
            >
              {option.name}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  )
}
