import { describe, expect, it } from 'vitest'
import { FRONT_LEAD_CHARS, splitLetter } from '../src/create/letter'

describe('one text, two sides', () => {
  it('keeps a short text on the front alone', () => {
    expect(splitLetter('  с днём рождения, мам!  ')).toEqual({ front: 'с днём рождения, мам!', letter: null })
    expect(splitLetter('   ')).toEqual({ front: '', letter: null })
  })

  it('puts a long text whole on the back and its first sentences on the front', () => {
    const text = 'Мам, привет. Мы наконец доехали до моря и я думал о тебе весь день. ' + 'Здесь очень тихо, и вода тёплая. '.repeat(12)
    const split = splitLetter(text)
    expect(split.letter).toBe(text.trim())
    expect(split.front).toBe('Мам, привет. Мы наконец доехали до моря и я думал о тебе весь день. Здесь очень тихо, и вода тёплая. Здесь очень тихо, и вода тёплая.')
    expect([...split.front].length).toBeLessThanOrEqual(FRONT_LEAD_CHARS)
  })

  it('cuts a first sentence longer than the front at a word, with an ellipsis', () => {
    const text = 'слово '.repeat(60).trim()
    const split = splitLetter(text)
    expect(split.front.endsWith('слово…')).toBe(true)
    expect([...split.front].length).toBeLessThanOrEqual(FRONT_LEAD_CHARS)
  })

  it('takes the front only from the first paragraph', () => {
    const text = 'Привет!\n' + 'второй абзац. '.repeat(30)
    expect(splitLetter(text).front).toBe('Привет!')
  })

  it('sends a text of many short lines to the back', () => {
    expect(splitLetter('a\nb\nc\nd\ne').letter).toBe('a\nb\nc\nd\ne')
  })
})
