import type { IntakeFence } from './normalize'

interface IntakeWork {
  readonly port: object
  readonly fence: IntakeFence
}

/** Synchronous admission gate for running AND queued picker handlers.
 * A lease lasts through the caller's final freeze/UI reconciliation, not just
 * through decoding. No send-attempt state or photo bytes are kept here. */
export class PhotoIntakeActivity {
  #work = new Set<IntakeWork>()

  begin(port: object, fence: IntakeFence): IntakeWork {
    const work = { port, fence: { ...fence } }
    this.#work.add(work)
    return work
  }

  owns(work: IntakeWork, port: object | null, live: IntakeFence): boolean {
    return this.#work.has(work) && work.port === port
      && live.mounted && work.fence.mounted
      && live.owner !== null && live.confirmed !== null
      && live.owner === work.fence.owner
      && live.generation === work.fence.generation
      && live.confirmed === work.fence.confirmed
  }

  pending(port: object | null, live: IntakeFence): number {
    let count = 0
    for (const work of this.#work) if (this.owns(work, port, live)) count++
    return count
  }

  finish(work: IntakeWork): void {
    this.#work.delete(work)
  }

  retire(port: object): void {
    for (const work of this.#work) if (work.port === port) this.#work.delete(work)
  }
}
