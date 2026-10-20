/**
 * Ephemeral, same-document presentation layers. History stores ONLY an opaque
 * visit marker, layer and photo ordinal, never content, URLs, accounts or keys.
 * Closing the sheet retires the visit. Forward into retired layers returns to
 * its base entry; it cannot admit or reconstruct a presentation.
 */
const KEY = '__remanencePresentationV1'
interface Marker { visit: string; depth: 0 | 1 | 2; index: number | null }
export interface LayerState { owner: object; index: number | null }
export interface HistoryPort {
  state(): unknown
  url(): string
  write(mode: 'push' | 'replace', state: unknown): void
  go(delta: number): void
  id(): string
}
const browserHistory: HistoryPort = {
  state: () => history.state as unknown,
  url: () => location.href,
  write: (mode, state) => mode === 'push' ? history.pushState(state, '') : history.replaceState(state, ''),
  go: (delta) => history.go(delta),
  id: () => crypto.randomUUID(),
}
function marker(state: unknown): Marker | null {
  if (typeof state !== 'object' || state === null) return null
  const value = (state as Record<string, unknown>)[KEY]
  if (typeof value !== 'object' || value === null) return null
  const record = value as Record<string, unknown>
  if (typeof record.visit !== 'string' || !/^[0-9a-f-]{36}$/.test(record.visit)) return null
  if (record.depth !== 0 && record.depth !== 1 && record.depth !== 2) return null
  if (record.depth === 2 ? !Number.isInteger(record.index) || Number(record.index) < 0 : record.index !== null) return null
  return record as unknown as Marker
}

export class PresentationHistory {
  #active: { owner: object; close: () => void; visit: string; url: string; tracked: boolean } | null = null
  #desired: { owner: object; close: () => void } | null = null
  #state: LayerState | null = null
  #listeners = new Set<() => void>()
  #moving = false
  #initialized = false

  constructor(private readonly port: HistoryPort = browserHistory) {}
  subscribe = (listener: () => void): (() => void) => {
    this.#listeners.add(listener)
    return () => { this.#listeners.delete(listener) }
  }
  getSnapshot = (): LayerState | null => this.#state

  initialize(): void {
    if (this.#initialized) return
    this.#initialized = true
    // A reloaded document has no live visit, even if history has a marker.
    this.navigated()
  }

  present(owner: object | null, close: () => void): void {
    this.#desired = owner === null ? null : { owner, close }
    if (this.#active?.owner === owner) return
    if (this.#active) this.#retire(true)
    this.#start()
  }

  openPhoto(index: number): void {
    const active = this.#active
    if (!active || this.#moving || !Number.isInteger(index) || index < 0) return
    if (active.tracked) {
      try { this.#write(this.#state?.index === null ? 'push' : 'replace', active.visit, 2, index) }
      catch { return } // Do not show an untracked nested layer after a failed push.
    }
    this.#publish({ owner: active.owner, index })
  }

  selectPhoto(index: number): void {
    if (this.#state?.index === null) return
    this.openPhoto(index)
  }

  closePhoto(): void {
    const active = this.#active
    if (!active || this.#moving || this.#state?.index === null) return
    if (active.tracked && this.#ownsCurrent(2)) {
      this.#move(-1, () => this.#publish({ owner: active.owner, index: null }))
    } else this.#publish({ owner: active.owner, index: null })
  }

  closeSheet(): void {
    if (!this.#active || this.#moving) return
    const close = this.#active.close
    this.#desired = null
    this.#retire(true)
    close()
  }

  /** Called for native popstate/hashchange, never for pushState itself. */
  navigated = (): void => {
    this.#moving = false
    const current = marker(this.port.state())
    const active = this.#active
    if (active) {
      if (active.tracked && current?.visit === active.visit && current.depth > 0 && this.port.url() === active.url) {
        this.#publish({ owner: active.owner, index: current.depth === 2 ? current.index : null })
        return
      }
      this.#desired = null
      this.#retire(false)
      active.close()
    }
    if (current && current.depth > 0) {
      // No matching live owner: skip this spent forward entry, not the route
      // before its base. Reopening later pushes a fresh visit and truncates it.
      this.#move(-current.depth, () => undefined)
      return
    }
    this.#start()
  }

  #ownsCurrent(depth?: number): boolean {
    const current = marker(this.port.state())
    return this.#active !== null && this.port.url() === this.#active.url
      && current?.visit === this.#active.visit && current.depth > 0
      && (depth === undefined || current.depth === depth)
  }
  #write(mode: 'push' | 'replace', visit: string, depth: 0 | 1 | 2, index: number | null): void {
    const previous = this.port.state()
    const fields = typeof previous === 'object' && previous !== null ? previous : {}
    this.port.write(mode, { ...fields, [KEY]: { visit, depth, index } })
  }
  #start(): void {
    if (this.#active || this.#moving || !this.#desired) return
    const { owner, close } = this.#desired
    const active = { owner, close, visit: this.port.id(), url: this.port.url(), tracked: false }
    try {
      this.#write('replace', active.visit, 0, null)
      this.#write('push', active.visit, 1, null)
      active.tracked = true
    } catch { /* Keep explicit close usable if browser history is unavailable. */ }
    this.#active = active
    this.#publish({ owner, index: null })
  }
  #retire(unwind: boolean): void {
    const current = marker(this.port.state())
    const depth = unwind && this.#ownsCurrent() ? current!.depth : 0
    this.#active = null
    this.#publish(null)
    if (depth > 0) this.#move(-depth, () => undefined)
  }
  #move(delta: number, fallback: () => void): void {
    this.#moving = true
    try { this.port.go(delta) }
    catch { this.#moving = false; fallback() }
  }
  #publish(next: LayerState | null): void {
    if (next?.owner === this.#state?.owner && next?.index === this.#state?.index) return
    this.#state = next
    for (const listener of this.#listeners) listener()
  }
}
