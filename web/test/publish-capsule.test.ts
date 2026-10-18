import { afterEach, describe, expect, it, vi } from 'vitest'
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import type { ApiRequest, ApiResult, ApiTransport, UploadRequest } from '../src/auth/http'
import { utf8, bytesToHex } from '../src/protocol/bytes'
import { nameUuidFromBytes } from '../src/protocol/uuid'
import { publishCapsule } from '../src/sync/publishCapsule'
import {
  buildSealedCapsuleFixture,
  CAPSULE_UUID,
  DRAFT_KEY,
  PHOTO_BLOB_0,
  RECIPIENT_ID,
  SENDER_BUNDLE,
  SENDER_ID,
} from './sealedCapsuleFixture'

type Fixture = Awaited<ReturnType<typeof buildSealedCapsuleFixture>>

function draftOk(capsule: Fixture['capsule'], stored: readonly string[] = []): ApiResult {
  return {
    status: 200,
    body: {
      capsule_id: capsule.capsuleId,
      state: 'DRAFT',
      draft_expires_at: '2026-10-17T00:00:00Z',
      blobs: capsule.blobs.map((blob) => ({ blob_id: blob.blobId, state: stored.includes(blob.blobId) ? 'STORED' : 'DECLARED' })),
    },
  }
}

function finalizeOk(capsule: Fixture['capsule'], locator?: { code: string; hint: string }): ApiResult {
  return {
    status: 201,
    body: {
      capsule_id: capsule.capsuleId,
      state: 'READY',
      ready_at: '2026-10-17T00:00:10Z',
      ...(locator ? { locator_code: locator.code, locator_hint: locator.hint } : {}),
    },
  }
}

interface TransportOptions {
  draft?: ApiResult | ((input: ApiRequest) => ApiResult)
  upload?: (call: number, input: UploadRequest) => ApiResult
  finalize?: ApiResult | ((input: ApiRequest) => ApiResult)
  onDraft?: () => void
}

function makeTransport(capsule: Fixture['capsule'], options: TransportOptions = {}) {
  const calls = { draft: [] as ApiRequest[], uploads: [] as UploadRequest[], finalize: [] as ApiRequest[] }
  const transport: ApiTransport & { uploadBytes(input: UploadRequest): Promise<ApiResult> } = {
    async request(input: ApiRequest): Promise<ApiResult> {
      if (input.path === '/v1/capsules' && input.method === 'POST') {
        calls.draft.push(input)
        options.onDraft?.()
        const draft = options.draft
        return typeof draft === 'function' ? draft(input) : draft ?? draftOk(capsule)
      }
      if (input.path === `/v1/capsules/${capsule.capsuleId}/finalize`) {
        calls.finalize.push(input)
        const finalize = options.finalize
        return typeof finalize === 'function' ? finalize(input) : finalize ?? finalizeOk(capsule)
      }
      throw new Error(`unexpected request ${input.path}`)
    },
    async uploadBytes(input: UploadRequest): Promise<ApiResult> {
      calls.uploads.push(input)
      return options.upload ? options.upload(calls.uploads.length, input) : { status: 204, body: null }
    },
  }
  return { transport, calls }
}

function publish(fixture: Fixture, transport: ApiTransport & { uploadBytes(input: UploadRequest): Promise<ApiResult> }, overrides: Partial<Parameters<typeof publishCapsule>[0]> = {}) {
  return publishCapsule({ capsule: fixture.capsule, session: fixture.session, idempotencyKey: DRAFT_KEY, transport, ...overrides })
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('production publishCapsule', () => {
  it('exports only the production publisher + error (no reader/observer)', async () => {
    const mod = await import('../src/sync/publishCapsule')
    expect(Object.keys(mod).sort()).toEqual(['PublishCapsuleError', 'publishCapsule'])
  })

  it('drives the exact draft/blob/finalize wire from REAL sealer output', async () => {
    const fixture = await buildSealedCapsuleFixture()
    const { transport, calls } = makeTransport(fixture.capsule)
    const published = await publish(fixture, transport)

    expect(published).toEqual({ capsuleId: CAPSULE_UUID, readyAt: '2026-10-17T00:00:10Z', locator: null })

    // Draft: exact current JSON in authored order, canonical base64url digests.
    expect(calls.draft).toHaveLength(1)
    const draft = calls.draft[0]!
    expect(draft.method).toBe('POST')
    expect(draft.path).toBe('/v1/capsules')
    expect(draft.token).toBe('sender-access')
    expect(draft.idempotencyKey).toBe(DRAFT_KEY)
    expect(draft.body).toEqual({
      capsule_id: CAPSULE_UUID,
      recipient_user_id: fixture.capsule.recipientUserId,
      sender_key_bundle_id: SENDER_BUNDLE,
      recipient_key_bundle_id: fixture.capsule.recipientKeyBundleId,
      protocol_version: 1,
      blobs: fixture.capsule.blobs.map((blob) => ({
        blob_id: blob.blobId,
        kind: blob.kind,
        ordinal: blob.kind === 'PHOTO' ? blob.ordinal : null,
        ciphertext_size: blob.ciphertextSize,
        ciphertext_sha256: b64(blob.ciphertextSha256),
      })),
    })

    // Uploads: DECLARED only, exact route/raw body/digest and the derived nameUUID.
    const expectedBlobs = fixture.capsule.blobs
    expect(calls.uploads).toHaveLength(expectedBlobs.length)
    for (let index = 0; index < expectedBlobs.length; index++) {
      const blob = expectedBlobs[index]!
      const call = calls.uploads[index]!
      expect(call.path).toBe(`/v1/capsules/${CAPSULE_UUID}/blobs/${blob.blobId}`)
      expect(call.token).toBe('sender-access')
      expect(call.ciphertextSha256).toEqual(blob.ciphertextSha256)
      expect(call.body).toEqual(blob.ciphertext)
      expect(call.idempotencyKey).toBe(nameUuidFromBytes(utf8(`postmark/blob-upload/v1/${CAPSULE_UUID}/${blob.blobId}`)))
    }

    // Finalize: exact signed statement + envelope, no extra statement_sha256 field.
    expect(calls.finalize).toHaveLength(1)
    const finalize = calls.finalize[0]!
    expect(finalize.path).toBe(`/v1/capsules/${CAPSULE_UUID}/finalize`)
    expect(finalize.method).toBe('POST')
    expect(finalize.token).toBe('sender-access')
    expect('idempotencyKey' in finalize ? finalize.idempotencyKey : undefined).toBeUndefined()
    expect(finalize.body).toEqual({
      signed_publish_statement: {
        statement: b64(fixture.capsule.statement),
        signature: b64(fixture.capsule.signature),
        sender_key_bundle_id: SENDER_BUNDLE,
      },
      recipient_envelope: {
        recipient_key_bundle_id: fixture.capsule.recipientKeyBundleId,
        ciphertext: b64(fixture.capsule.envelopeCiphertext),
        ciphertext_size: fixture.capsule.envelopeCiphertext.length,
        ciphertext_sha256: b64(await sha256(fixture.capsule.envelopeCiphertext)),
      },
    })
  })

  it('matches the documented per-blob nameUUID recipe vector (deterministic id, not security MD5)', () => {
    expect(nameUuidFromBytes(utf8(`postmark/blob-upload/v1/${CAPSULE_UUID}/${PHOTO_BLOB_0}`))).toBe('5df8dfa6-4e40-332b-a6b8-5d75de23d0e5')
  })

  it('skips ONLY server-STORED blobs; all-STORED uploads nothing', async () => {
    const fixture = await buildSealedCapsuleFixture()
    const stored = fixture.capsule.blobs[0]!.blobId
    const partial = makeTransport(fixture.capsule, { draft: draftOk(fixture.capsule, [stored]) })
    await publish(fixture, partial.transport)
    expect(partial.calls.uploads.map((call) => call.path)).not.toContain(`/v1/capsules/${CAPSULE_UUID}/blobs/${stored}`)
    expect(partial.calls.uploads).toHaveLength(fixture.capsule.blobs.length - 1)

    const all = makeTransport(fixture.capsule, { draft: draftOk(fixture.capsule, fixture.capsule.blobs.map((blob) => blob.blobId)) })
    await publish(fixture, all.transport)
    expect(all.calls.uploads).toHaveLength(0)
    expect(all.calls.finalize).toHaveLength(1)
  })

  it('refuses malformed draft receipts without any further request', async () => {
    const fixture = await buildSealedCapsuleFixture()
    const good = draftOk(fixture.capsule).body as { blobs: Array<Record<string, unknown>> }
    const variants: unknown[] = [
      { ...(draftOk(fixture.capsule).body as object), capsule_id: RECIPIENT_ID },
      { ...(draftOk(fixture.capsule).body as object), state: 'READY' },
      { ...(draftOk(fixture.capsule).body as object), draft_expires_at: 'not-a-time' },
      { ...(draftOk(fixture.capsule).body as object), extra: 1 },
      { ...(draftOk(fixture.capsule).body as object), blobs: good.blobs.slice(1) },
      { ...(draftOk(fixture.capsule).body as object), blobs: good.blobs.map((blob, index) => (index === 0 ? { ...blob, blob_id: CAPSULE_UUID } : blob)) },
      { ...(draftOk(fixture.capsule).body as object), blobs: good.blobs.map((blob, index) => (index === 0 ? { blob_id: blob.blob_id, state: 'BOGUS' } : blob)) },
    ]
    for (const body of variants) {
      const { transport, calls } = makeTransport(fixture.capsule, { draft: { status: 200, body } })
      await expect(publish(fixture, transport)).rejects.toMatchObject({ stage: 'draft', category: 'malformed' })
      expect(calls.uploads).toHaveLength(0)
      expect(calls.finalize).toHaveLength(0)
    }
  })

  it('surfaces a READY-draft replay 409 as a typed refusal, never success', async () => {
    const fixture = await buildSealedCapsuleFixture()
    const { transport, calls } = makeTransport(fixture.capsule, { draft: { status: 409, body: { code: 'CAPSULE_STATE_INVALID' } } })
    await expect(publish(fixture, transport)).rejects.toMatchObject({ stage: 'draft', category: 'capsule-state-invalid', status: 409, code: 'CAPSULE_STATE_INVALID' })
    expect(calls.uploads).toHaveLength(0)
  })

  it('stops at the failing stage with a bounded error and no later request', async () => {
    const fixture = await buildSealedCapsuleFixture()
    const badUpload = makeTransport(fixture.capsule, { upload: () => ({ status: 400, body: { code: 'BLOB_HASH_MISMATCH' } }) })
    await expect(publish(fixture, badUpload.transport)).rejects.toMatchObject({ stage: 'upload', category: 'blob', status: 400, code: 'BLOB_HASH_MISMATCH' })
    expect(badUpload.calls.uploads.length).toBeGreaterThan(0)
    expect(badUpload.calls.finalize).toHaveLength(0)

    const conflict = makeTransport(fixture.capsule, { finalize: { status: 409, body: { code: 'FINALIZE_CONFLICT' } } })
    await expect(publish(fixture, conflict.transport)).rejects.toMatchObject({ stage: 'finalize', category: 'finalize-conflict', status: 409, code: 'FINALIZE_CONFLICT' })
    expect(conflict.calls.finalize).toHaveLength(1)
  })

  it('surfaces the negotiated locator pair and rejects malformed/incomplete locator fields', async () => {
    const fixture = await buildSealedCapsuleFixture()
    const ok = makeTransport(fixture.capsule, { finalize: finalizeOk(fixture.capsule, { code: 'AB12Z', hint: 'a•••@•••' }) })
    await expect(publish(fixture, ok.transport)).resolves.toEqual({ capsuleId: CAPSULE_UUID, readyAt: '2026-10-17T00:00:10Z', locator: { code: 'AB12Z', hint: 'a•••@•••' } })

    const bad: Array<Record<string, unknown>> = [
      { capsule_id: CAPSULE_UUID, state: 'READY', ready_at: '2026-10-17T00:00:10Z', locator_code: 'AB12Z' },
      { capsule_id: CAPSULE_UUID, state: 'READY', ready_at: '2026-10-17T00:00:10Z', locator_hint: 'x' },
      { capsule_id: CAPSULE_UUID, state: 'READY', ready_at: '2026-10-17T00:00:10Z', locator_code: 'AB01I', locator_hint: 'x' },
      { capsule_id: CAPSULE_UUID, state: 'READY', ready_at: '2026-10-17T00:00:10Z', locator_code: 'ab12z', locator_hint: 'x' },
      { capsule_id: CAPSULE_UUID, state: 'READY', ready_at: '2026-10-17T00:00:10Z', locator_code: 'AB12Z', locator_hint: '' },
      { capsule_id: CAPSULE_UUID, state: 'READY', ready_at: '2026-10-17T00:00:10Z', locator_code: 'AB12Z', locator_hint: `x`.repeat(255) },
      { capsule_id: CAPSULE_UUID, state: 'READY', ready_at: '2026-10-17T00:00:10Z', locator_code: 'AB12Z', locator_hint: 'a\u0000b' },
      { capsule_id: CAPSULE_UUID, state: 'READY', ready_at: '2026-10-17T00:00:10Z', locator_code: 'AB12Z', locator_hint: 'x', extra: 1 },
    ]
    for (const body of bad) {
      const { transport } = makeTransport(fixture.capsule, { finalize: { status: 201, body } })
      await expect(publish(fixture, transport)).rejects.toMatchObject({ stage: 'finalize', category: 'malformed' })
    }
  })

  it('fails closed before any request on pre-abort and refuses every mid-await session change', async () => {
    // pre-abort makes no request.
    const preFixture = await buildSealedCapsuleFixture()
    const preAbort = makeTransport(preFixture.capsule)
    const preCtl = new AbortController()
    preCtl.abort()
    await expect(publish(preFixture, preAbort.transport, { signal: preCtl.signal })).rejects.toMatchObject({ stage: 'preflight', category: 'cancelled' })
    expect(preAbort.calls.draft).toHaveLength(0)

    const transitions: Array<{ name: string; mutate: (f: Fixture) => void }> = [
      { name: 'lock', mutate: (f) => f.session.lock() },
      { name: 'signout', mutate: (f) => f.session.signOut() },
      { name: 'relogin (generation)', mutate: (f) => { f.session.signOut(); signIn(f.session, SENDER_ID, SENDER_BUNDLE); f.session.unlock() } },
      { name: 'account move', mutate: (f) => { f.session.signOut(); signIn(f.session, '99999999-9999-4999-8999-999999999999', SENDER_BUNDLE); f.session.unlock() } },
      { name: 'bundle move', mutate: (f) => { f.session.signOut(); signIn(f.session, SENDER_ID, '99999999-9999-4999-8999-999999999999'); f.session.unlock() } },
    ]
    for (const { name, mutate } of transitions) {
      const fixture = await buildSealedCapsuleFixture()
      const { transport, calls } = makeTransport(fixture.capsule, { onDraft: () => mutate(fixture) })
      await expect(publish(fixture, transport), name).rejects.toMatchObject({ stage: 'draft', category: 'stale' })
      expect(calls.uploads).toHaveLength(0)
      expect(calls.finalize).toHaveLength(0)
    }

    // mid-await abort (not a throw) is CANCELLED, no further writes.
    const abortFixture = await buildSealedCapsuleFixture()
    const ctl = new AbortController()
    const aborted = makeTransport(abortFixture.capsule, { onDraft: () => ctl.abort() })
    await expect(publish(abortFixture, aborted.transport, { signal: ctl.signal })).rejects.toMatchObject({ stage: 'draft', category: 'cancelled' })
    expect(aborted.calls.uploads).toHaveLength(0)
  })

  it('snapshots mutable caller ciphertext/metadata before the first await', async () => {
    const fixture = await buildSealedCapsuleFixture()
    const capsule = fixture.capsule
    const originalCiphertext = capsule.blobs[0]!.ciphertext.slice()
    const originalDeclared = capsule.blobs[0]!.ciphertextSha256.slice()
    const originalStatement = capsule.statement.slice()
    const { transport, calls } = makeTransport(capsule)
    const pending = publish(fixture, transport)
    capsule.blobs[0]!.ciphertext.fill(0x99)
    capsule.blobs[0]!.ciphertextSha256.fill(0x99)
    capsule.statement.fill(0x99)
    await pending
    expect(bytesToHex(calls.uploads[0]!.body)).toBe(bytesToHex(originalCiphertext))
    expect(bytesToHex(calls.uploads[0]!.ciphertextSha256)).toBe(bytesToHex(originalDeclared))
    const draftBlobs = (calls.draft[0]!.body as { blobs: Array<{ ciphertext_sha256: string }> }).blobs
    expect(draftBlobs[0]!.ciphertext_sha256).toBe(b64(originalDeclared))
    expect((calls.finalize[0]!.body as { signed_publish_statement: { statement: string } }).signed_publish_statement.statement).toBe(b64(originalStatement))
  })

  it('refuses tampered lengths/digests before any HTTP write', async () => {
    const fixture = await buildSealedCapsuleFixture()
    const tamperedBlob = { ...fixture.capsule.blobs[0]!, ciphertext: fixture.capsule.blobs[0]!.ciphertext.slice() }
    tamperedBlob.ciphertext[0] = (tamperedBlob.ciphertext[0]! ^ 0xff) & 0xff
    const capsuleA = { ...fixture.capsule, blobs: [tamperedBlob, ...fixture.capsule.blobs.slice(1)] }
    const a = makeTransport(capsuleA)
    await expect(publish({ ...fixture, capsule: capsuleA }, a.transport)).rejects.toMatchObject({ stage: 'preflight', category: 'contract' })
    expect(a.calls.draft).toHaveLength(0)

    const capsuleB = { ...fixture.capsule, statementSha256: fixture.capsule.statementSha256.slice() }
    capsuleB.statementSha256[0] = (capsuleB.statementSha256[0]! ^ 0xff) & 0xff
    const b = makeTransport(capsuleB)
    await expect(publish({ ...fixture, capsule: capsuleB }, b.transport)).rejects.toMatchObject({ stage: 'preflight', category: 'contract' })
    expect(b.calls.draft).toHaveLength(0)
  })

  it('does not mutate the caller-owned capsule arrays', async () => {
    const fixture = await buildSealedCapsuleFixture()
    const before = {
      statement: fixture.capsule.statement.slice(),
      signature: fixture.capsule.signature.slice(),
      envelope: fixture.capsule.envelopeCiphertext.slice(),
      ciphertext: fixture.capsule.blobs[0]!.ciphertext.slice(),
      digest: fixture.capsule.blobs[0]!.ciphertextSha256.slice(),
    }
    const { transport } = makeTransport(fixture.capsule)
    await publish(fixture, transport)
    expect(bytesToHex(fixture.capsule.statement)).toBe(bytesToHex(before.statement))
    expect(bytesToHex(fixture.capsule.signature)).toBe(bytesToHex(before.signature))
    expect(bytesToHex(fixture.capsule.envelopeCiphertext)).toBe(bytesToHex(before.envelope))
    expect(bytesToHex(fixture.capsule.blobs[0]!.ciphertext)).toBe(bytesToHex(before.ciphertext))
    expect(bytesToHex(fixture.capsule.blobs[0]!.ciphertextSha256)).toBe(bytesToHex(before.digest))
  })
})

function signIn(session: Fixture['session'], accountId: string, bundleId: string): void {
  session.signIn({
    accountId,
    bundleId,
    handle: 'sergio',
    accessToken: 'sender-access',
    refreshToken: 'sender-refresh',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource))
}
