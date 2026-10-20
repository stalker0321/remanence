import { describe, expect, it } from 'vitest'
import { PhotoIntakeActivity } from '../src/create/intakeActivity'
import type { IntakeFence } from '../src/create/normalize'

const fence = (): IntakeFence => ({ owner: 'owner-a', generation: 1, confirmed: {}, mounted: true })

describe('owned pending/queued photo intake activity', () => {
  it('counts immediately, and releasing one queued operation cannot release another', () => {
    const activity = new PhotoIntakeActivity()
    const port = {}
    const live = fence()
    const one = activity.begin(port, live)
    const two = activity.begin(port, live)
    expect(activity.pending(port, live)).toBe(2)
    activity.finish(one)
    expect(activity.pending(port, live)).toBe(1)
    activity.finish(one)
    expect(activity.pending(port, live)).toBe(1)
    activity.finish(two)
    expect(activity.pending(port, live)).toBe(0)
  })

  it.each(['owner', 'generation', 'confirmed', 'mounted'] as const)('fences %s without disturbing the replacement work', (field) => {
    const activity = new PhotoIntakeActivity()
    const port = {}
    const old = fence()
    const one = activity.begin(port, old)
    const live = { ...old }
    if (field === 'owner') live.owner = 'owner-b'
    else if (field === 'generation') live.generation++
    else if (field === 'confirmed') live.confirmed = {}
    else live.mounted = false
    expect(activity.owns(one, port, live)).toBe(false)
    expect(activity.pending(port, live)).toBe(0)
    if (!live.mounted) return
    const two = activity.begin(port, live)
    activity.finish(one)
    expect(activity.owns(two, port, live)).toBe(true)
    expect(activity.pending(port, live)).toBe(1)
  })

  it('old StrictMode/unmounted intake cannot own or retire the replacement port', () => {
    const activity = new PhotoIntakeActivity()
    const oldPort = {}, nextPort = {}
    const live = fence()
    const old = activity.begin(oldPort, live)
    const next = activity.begin(nextPort, live)
    expect(activity.owns(old, nextPort, live)).toBe(false)
    activity.retire(oldPort)
    activity.finish(old)
    expect(activity.pending(nextPort, live)).toBe(1)
    expect(activity.owns(next, nextPort, live)).toBe(true)
    activity.retire(nextPort)
    expect(activity.pending(nextPort, live)).toBe(0)
  })

  it('captures scalar context, and empty/locked contexts never authorize pending work', () => {
    const activity = new PhotoIntakeActivity()
    const port = {}
    const live = fence()
    activity.begin(port, live)
    live.generation++
    expect(activity.pending(port, live)).toBe(0)
    expect(activity.pending(null, fence())).toBe(0)
    const missing = { ...fence(), owner: null, confirmed: null }
    activity.begin(port, missing)
    expect(activity.pending(port, missing)).toBe(0)
  })
})
