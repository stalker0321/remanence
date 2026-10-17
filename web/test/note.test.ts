import { describe, expect, it } from 'vitest'
import limits from '../../protocol/fixtures/limits-v1.json'
import { NOTE_MAX_UTF8_BYTES, NoteDraft, noteUtf8Bytes } from '../src/create/note'
import { SessionStore } from '../src/session/session'

describe('create note', () => {
  it('counts UTF-8 bytes and keeps an over-limit draft', () => {
    expect(NOTE_MAX_UTF8_BYTES).toBe(limits.note_max_utf8_bytes)
    expect(noteUtf8Bytes('я')).toBe(2)
    expect(noteUtf8Bytes('🙂')).toBe(4)
    const note = new NoteDraft()
    note.edit('я'.repeat(500))
    expect(note.bytes()).toBe(1000)
    expect(note.fits()).toBe(true)
    expect(note.accepted()).toBe('я'.repeat(500))
    note.edit(`${'я'.repeat(500)}!`)
    expect(note.bytes()).toBe(1001)
    expect(note.fits()).toBe(false)
    expect(note.accepted()).toBeNull()
    expect(note.text()).toBe(`${'я'.repeat(500)}!`)
    note.edit('')
    expect(note.accepted()).toBeNull()
    expect(note.fits()).toBe(true)
  })

  it('drops the draft when the keys lock', () => {
    const session = new SessionStore()
    session.signIn({
      accountId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
      bundleId: '22222222-2222-4222-8222-222222222222',
      handle: 'ada',
      accessToken: 'access-token-value',
      refreshToken: 'refresh-token-value',
      accessExpiresAt: '2026-10-17T00:00:00Z',
      refreshExpiresAt: '2026-11-17T00:00:00Z',
    })
    session.unlock()
    const note = new NoteDraft()
    note.attach(session)
    note.edit('hello')
    session.lock()
    expect(note.text()).toBe('')
    expect(note.accepted()).toBeNull()
  })
})