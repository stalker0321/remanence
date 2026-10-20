import { describe, expect, it } from 'vitest'
import { exportComposition, type ExportPorts } from '../src/collage/exportComposition'

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((accept, refuse) => {
    resolve = accept
    reject = refuse
  })
  return { promise, resolve, reject }
}

function ports(overrides: Partial<ExportPorts> = {}) {
  const calls = { encode: 0, create: 0, download: 0, revoke: 0 }
  const urls: string[] = []
  const value: ExportPorts = {
    isCurrent: () => true,
    encode: () => Promise.resolve(new Blob([new Uint8Array([1, 2, 3])])),
    createUrl: () => {
      calls.create += 1
      const url = `blob:t351-${calls.create}`
      urls.push(url)
      return url
    },
    download: () => { calls.download += 1 },
    revokeUrl: () => { calls.revoke += 1 },
    ...overrides,
  }
  return { value, calls, urls }
}

describe('stage composition export fence', () => {
  it('waits for the current encode before creating or starting any download', async () => {
    const gate = deferred<Blob | null>()
    const setup = ports()
    setup.value.encode = () => { setup.calls.encode += 1; return gate.promise }
    const running = exportComposition(setup.value)
    await Promise.resolve()
    expect(setup.calls.encode).toBe(1)
    expect(setup.calls.create).toBe(0)
    expect(setup.calls.download).toBe(0)
    gate.resolve(new Blob([new Uint8Array([4, 5])]))
    await expect(running).resolves.toBe('exported')
    expect(setup.calls.create).toBe(1)
    expect(setup.calls.download).toBe(1)
    expect(setup.calls.revoke).toBe(1)
  })

  it('refuses before encoding when this render is already stale', async () => {
    const setup = ports({ isCurrent: () => false })
    await expect(exportComposition(setup.value)).resolves.toBe('stale')
    expect(setup.calls.encode).toBe(0)
    expect(setup.calls.create).toBe(0)
    expect(setup.calls.download).toBe(0)
  })

  it('drops a late encode when the held sheet was replaced or unmounted', async () => {
    const gate = deferred<Blob | null>()
    let current = true
    const setup = ports({ encode: () => gate.promise, isCurrent: () => current })
    const running = exportComposition(setup.value)
    current = false
    gate.resolve(new Blob([new Uint8Array([6])]))
    await expect(running).resolves.toBe('stale')
    expect(setup.calls.create).toBe(0)
    expect(setup.calls.download).toBe(0)
    expect(setup.calls.revoke).toBe(0)
  })

  it('handles a null blob as an honest empty export with no URL or download', async () => {
    const setup = ports({ encode: () => Promise.resolve(null) })
    await expect(exportComposition(setup.value)).resolves.toBe('empty')
    expect(setup.calls.create).toBe(0)
    expect(setup.calls.download).toBe(0)
  })

  it('handles a thrown encode or SecurityError without URL/download', async () => {
    const setup = ports({ encode: () => Promise.reject(new Error('tainted canvas')) })
    await expect(exportComposition(setup.value)).resolves.toBe('failed')
    expect(setup.calls.create).toBe(0)
    expect(setup.calls.download).toBe(0)
  })

  it('revokes the URL when starting the download throws', async () => {
    const setup = ports({ download: () => { throw new Error('blocked') } })
    await expect(exportComposition(setup.value)).resolves.toBe('failed')
    expect(setup.calls.create).toBe(1)
    expect(setup.calls.download).toBe(1)
    expect(setup.calls.revoke).toBe(1)
  })

  it('checks staleness between URL creation and starting the download', async () => {
    let current = true
    const setup = ports({ isCurrent: () => current })
    setup.value.createUrl = (blob) => {
      setup.calls.create += 1
      current = false
      return `blob:${blob.size}`
    }
    await expect(exportComposition(setup.value)).resolves.toBe('stale')
    expect(setup.calls.download).toBe(0)
    expect(setup.calls.revoke).toBe(1)
  })
})
