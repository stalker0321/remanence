import { describe, expect, it } from 'vitest'
import { voicePeaks } from '../src/create/voice'

describe('voice recording', () => {
  it('measures 48 peaks, the loudest slice at 255 and silence flat', () => {
    const samples = new Float32Array(4800)
    for (let index = 0; index < samples.length; index++) samples[index] = Math.sin(index / 7) * (index < 2400 ? 0.2 : 0.8)
    const peaks = voicePeaks(samples)
    expect(peaks).toHaveLength(48)
    expect(Math.max(...peaks)).toBe(255)
    expect(peaks[0]).toBeLessThan(peaks[47]!)
    expect([...voicePeaks(new Float32Array(4800))]).toEqual(new Array(48).fill(0))
    expect(voicePeaks(new Float32Array(10))).toHaveLength(48)
  })
})
