import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { catalog, languages } from '../src/i18n/catalog'
import { translate } from '../src/i18n/translate'
import { Icon, StepTrail } from '../src/ui/Primitives'
import en from '../src/i18n/locales/en.json'
import ru from '../src/i18n/locales/ru.json'
import uk from '../src/i18n/locales/uk.json'

describe('product presentation, not controller authority', () => {
  it('keeps every new navigation/state explanation available in all three languages', () => {
    expect(Object.keys(ru).sort()).toEqual(Object.keys(en).sort())
    expect(Object.keys(uk).sort()).toEqual(Object.keys(en).sort())
    for (const language of languages) for (const key of Object.keys(en) as (keyof typeof en)[]) {
      expect(translate(language, key).trim().length).toBeGreaterThan(0)
      expect(catalog[language][key]).not.toBeUndefined()
    }
  })

  it('renders one current step without buttons, navigation or implicit progress', () => {
    const html = renderToStaticMarkup(createElement(StepTrail, {
      labels: ['stepRecipient', 'stepCompose', 'stepPostcard'], current: 1,
      t: key => translate('en', key),
    }))
    expect(html.match(/aria-current="step"/g)).toHaveLength(1)
    expect(html.match(/data-complete="true"/g)).toHaveLength(1)
    expect(html).toContain('put it together')
    expect(html).not.toMatch(/<(button|a|input)\b/)
  })

  it('keeps decorative icons out of the accessible name and tab order', () => {
    for (const name of ['arrow', 'key', 'scan', 'postcard', 'back', 'check'] as const) {
      const html = renderToStaticMarkup(createElement(Icon, { name }))
      expect(html).toContain('aria-hidden="true"')
      expect(html).toContain('focusable="false"')
      expect(html).not.toContain('tabindex')
    }
  })

  it('does not assert that NotAllowedError proves a deliberate user cancellation', () => {
    expect(catalog.en.passkeyCancelled).not.toMatch(/dismiss|cancel/i)
    expect(catalog.ru.passkeyCancelled).not.toMatch(/отмен|закрыли/i)
    expect(catalog.uk.passkeyCancelled).not.toMatch(/скас|закрили/i)
  })
})
