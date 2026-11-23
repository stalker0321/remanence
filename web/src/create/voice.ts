import { bytesToHex } from '../protocol/bytes'
import { limits } from '../protocol/limits'
import { VOICE_PEAKS } from '../collage/songBand'
import { VOICE_MAX_MS } from '../collage/wire'

/**
 * Recording a voice note for the song band (ADR-021 §4): AAC in MP4, at most
 * a minute, measured here into its length and 48 peaks. The receiver plays
 * the MP4 on the site and on Android, so only AAC is recorded; a browser
 * that cannot record it (Firefox) is not offered recording.
 */

export const VOICE_MIME = 'audio/mp4;codecs=mp4a.40.2'
const BITS_PER_SECOND = 48_000

/** The recorded MP4 and what the band paints of it. */
export interface VoiceRecording {
  readonly bytes: Uint8Array
  readonly sha256Hex: string
  readonly durationMs: number
  /** VOICE_PEAKS amplitudes, 0..255. */
  readonly peaks: Uint8Array
}

export type VoiceFailure = 'unsupported' | 'denied' | 'failed' | 'too-large'

export class VoiceError extends Error {
  constructor(readonly code: VoiceFailure) {
    super(code)
    this.name = 'VoiceError'
  }
}

export function voiceRecordingSupported(): boolean {
  return typeof MediaRecorder !== 'undefined'
    && typeof navigator !== 'undefined'
    && typeof navigator.mediaDevices?.getUserMedia === 'function'
    && MediaRecorder.isTypeSupported(VOICE_MIME)
}

/**
 * The loudest sample of each of VOICE_PEAKS equal slices, scaled so the
 * loudest slice is 255; silence stays flat at 0.
 */
export function voicePeaks(samples: Float32Array): Uint8Array {
  const peaks = new Float32Array(VOICE_PEAKS)
  for (let slice = 0; slice < VOICE_PEAKS; slice++) {
    const start = Math.floor(samples.length * slice / VOICE_PEAKS)
    const end = Math.floor(samples.length * (slice + 1) / VOICE_PEAKS)
    let loudest = 0
    for (let index = start; index < end; index++) loudest = Math.max(loudest, Math.abs(samples[index]!))
    peaks[slice] = loudest
  }
  const top = Math.max(...peaks)
  return Uint8Array.from(peaks, (peak) => top < 1e-4 ? 0 : Math.round(255 * Math.min(1, peak / top)))
}

/** Decodes the MP4 once for its length and peaks; refuses what is not a 1..60 s MP4 that fits one MEDIA artifact. */
export async function measureVoice(bytes: Uint8Array): Promise<VoiceRecording> {
  if (bytes.length < 8 || String.fromCharCode(...bytes.subarray(4, 8)) !== 'ftyp') throw new VoiceError('failed')
  if (bytes.length + limits.artifactOverhead > limits.mediaCiphertext) throw new VoiceError('too-large')
  const context = new OfflineAudioContext(1, 1, 48_000)
  let audio: AudioBuffer
  try {
    audio = await context.decodeAudioData(bytes.slice().buffer)
  } catch {
    throw new VoiceError('failed')
  }
  const durationMs = Math.min(VOICE_MAX_MS, Math.round(audio.duration * 1000))
  if (durationMs < 1) throw new VoiceError('failed')
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource))
  return { bytes, sha256Hex: bytesToHex(digest), durationMs, peaks: voicePeaks(audio.getChannelData(0)) }
}

/**
 * One recording from the microphone: `start` asks for it and records until
 * `stop` or the minute runs out (`onLimit`), then hands back the MP4.
 * `cancel` drops it. The microphone is released either way.
 */
export class VoiceRecorder {
  private stream: MediaStream | null = null
  private recorder: MediaRecorder | null = null
  private chunks: Blob[] = []
  private limit: ReturnType<typeof setTimeout> | null = null
  private finished: Promise<Uint8Array> | null = null

  async start(onLimit: () => void): Promise<void> {
    if (!voiceRecordingSupported()) throw new VoiceError('unsupported')
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch (error) {
      throw new VoiceError(error instanceof DOMException && error.name === 'NotAllowedError' ? 'denied' : 'failed')
    }
    const recorder = new MediaRecorder(this.stream, { mimeType: VOICE_MIME, audioBitsPerSecond: BITS_PER_SECOND })
    this.recorder = recorder
    this.chunks = []
    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) this.chunks.push(event.data)
    }
    this.finished = new Promise((resolve, reject) => {
      recorder.onstop = () => {
        this.release()
        new Blob(this.chunks, { type: 'audio/mp4' }).arrayBuffer().then((buffer) => resolve(new Uint8Array(buffer)), reject)
        this.chunks = []
      }
      recorder.onerror = () => {
        this.release()
        reject(new VoiceError('failed'))
      }
    })
    recorder.start()
    this.limit = setTimeout(onLimit, VOICE_MAX_MS - 250)
  }

  /** Stops and returns the MP4. */
  async stop(): Promise<Uint8Array> {
    if (this.recorder === null || this.finished === null) throw new VoiceError('failed')
    if (this.recorder.state !== 'inactive') this.recorder.stop()
    return this.finished
  }

  cancel(): void {
    if (this.recorder !== null) {
      this.recorder.ondataavailable = null
      this.recorder.onstop = null
      if (this.recorder.state !== 'inactive') this.recorder.stop()
    }
    this.chunks = []
    this.release()
  }

  private release(): void {
    if (this.limit !== null) clearTimeout(this.limit)
    this.limit = null
    for (const track of this.stream?.getTracks() ?? []) track.stop()
    this.stream = null
  }
}
