import { bytesToHex } from '../protocol/bytes'
import { limits } from '../protocol/limits'
import { VIDEO_MAX_MS } from '../collage/wire'

/**
 * Recording the video square (ADR-021 §4): the front camera, cropped to a
 * 480×480 square on a canvas and recorded as H.264 + AAC in MP4 for at most
 * 30 seconds. The first frame becomes the poster: an ordinary photo of the
 * card whose tile the video plays in. Only in-app capture; never a file from
 * the gallery. A browser that cannot record MP4 (Firefox) is not offered it.
 */

export const VIDEO_SIDE = 480
const FRAME_RATE = 30
const VIDEO_BITS = 1_200_000
const AUDIO_BITS = 64_000
const CANDIDATE_TYPES = [
  'video/mp4;codecs=avc1.42E01F,mp4a.40.2',
  'video/mp4;codecs=avc1.42E01E,mp4a.40.2',
  'video/mp4;codecs=avc1,mp4a.40.2',
]

/** The recorded MP4, its poster and what the card needs to know of it. */
export interface VideoRecording {
  readonly bytes: Uint8Array
  readonly sha256Hex: string
  readonly durationMs: number
  readonly size: number
  /** The first frame as a square JPEG; it joins the photos as the video's tile. */
  readonly poster: Blob
}

export type VideoFailure = 'unsupported' | 'denied' | 'failed' | 'too-large' | 'too-short'

export class VideoError extends Error {
  constructor(readonly code: VideoFailure) {
    super(code)
    this.name = 'VideoError'
  }
}

function recordingType(): string | null {
  if (typeof MediaRecorder === 'undefined') return null
  return CANDIDATE_TYPES.find((type) => MediaRecorder.isTypeSupported(type)) ?? null
}

export function videoRecordingSupported(): boolean {
  return typeof navigator !== 'undefined'
    && typeof navigator.mediaDevices?.getUserMedia === 'function'
    && typeof HTMLCanvasElement !== 'undefined'
    && typeof HTMLCanvasElement.prototype.captureStream === 'function'
    && recordingType() !== null
}

/** Refuses what is not an MP4 of 1..30 s that fits one VIDEO artifact. */
export async function measureVideo(bytes: Uint8Array, durationMs: number, poster: Blob): Promise<VideoRecording> {
  if (bytes.length < 8 || String.fromCharCode(...bytes.subarray(4, 8)) !== 'ftyp') throw new VideoError('failed')
  if (bytes.length + limits.artifactOverhead > limits.videoCiphertext) throw new VideoError('too-large')
  const length = Math.min(VIDEO_MAX_MS, Math.round(durationMs))
  if (length < 1000) throw new VideoError('too-short')
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource))
  return { bytes, sha256Hex: bytesToHex(digest), durationMs: length, size: VIDEO_SIDE, poster }
}

/**
 * One take from the front camera. `start` opens the camera and shows it in
 * `preview` (mirrored, as a selfie); `record` starts the take and stops it
 * itself just before 30 s (`onLimit`); `stop` hands back the MP4, its length
 * and the first frame. `close` drops everything and frees the camera.
 */
export class VideoRecorder {
  private stream: MediaStream | null = null
  private recorder: MediaRecorder | null = null
  private chunks: Blob[] = []
  private limit: ReturnType<typeof setTimeout> | null = null
  private frame: number | null = null
  private finished: Promise<Uint8Array> | null = null
  private startedAt = 0
  private poster: Promise<Blob> | null = null
  private readonly canvas = document.createElement('canvas')

  async start(preview: HTMLVideoElement): Promise<void> {
    if (!videoRecordingSupported()) throw new VideoError('unsupported')
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'user', width: { ideal: 720 }, height: { ideal: 720 } },
        audio: { echoCancellation: true, noiseSuppression: true },
      })
    } catch (error) {
      throw new VideoError(error instanceof DOMException && error.name === 'NotAllowedError' ? 'denied' : 'failed')
    }
    preview.srcObject = this.stream
    preview.muted = true
    preview.playsInline = true
    await preview.play().catch(() => undefined)
    this.canvas.width = VIDEO_SIDE
    this.canvas.height = VIDEO_SIDE
    const context = this.canvas.getContext('2d')
    if (context === null) throw new VideoError('failed')
    // The square the card shows: the middle of the camera's frame, mirrored like the preview.
    const draw = () => {
      const width = preview.videoWidth
      const height = preview.videoHeight
      if (width > 0 && height > 0) {
        const side = Math.min(width, height)
        context.save()
        context.translate(VIDEO_SIDE, 0)
        context.scale(-1, 1)
        context.drawImage(preview, (width - side) / 2, (height - side) / 2, side, side, 0, 0, VIDEO_SIDE, VIDEO_SIDE)
        context.restore()
      }
      this.frame = requestAnimationFrame(draw)
    }
    draw()
  }

  record(onLimit: () => void): void {
    const type = recordingType()
    if (this.stream === null || type === null) throw new VideoError('unsupported')
    const tracks = [...this.canvas.captureStream(FRAME_RATE).getVideoTracks(), ...this.stream.getAudioTracks()]
    const recorder = new MediaRecorder(new MediaStream(tracks), { mimeType: type, videoBitsPerSecond: VIDEO_BITS, audioBitsPerSecond: AUDIO_BITS })
    this.recorder = recorder
    this.chunks = []
    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) this.chunks.push(event.data)
    }
    this.finished = new Promise((resolve, reject) => {
      recorder.onstop = () => {
        this.release()
        new Blob(this.chunks, { type: 'video/mp4' }).arrayBuffer().then((buffer) => resolve(new Uint8Array(buffer)), reject)
        this.chunks = []
      }
      recorder.onerror = () => {
        this.release()
        reject(new VideoError('failed'))
      }
    })
    this.poster = new Promise((resolve, reject) => {
      this.canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new VideoError('failed'))), 'image/jpeg', 0.92)
    })
    recorder.start()
    this.startedAt = performance.now()
    this.limit = setTimeout(onLimit, VIDEO_MAX_MS - 250)
  }

  /** Stops the take and returns the MP4, its length and its first frame. */
  async stop(): Promise<{ bytes: Uint8Array; durationMs: number; poster: Blob }> {
    if (this.recorder === null || this.finished === null || this.poster === null) throw new VideoError('failed')
    const durationMs = performance.now() - this.startedAt
    if (this.recorder.state !== 'inactive') this.recorder.stop()
    const [bytes, poster] = await Promise.all([this.finished, this.poster])
    return { bytes, durationMs, poster }
  }

  close(): void {
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
    if (this.frame !== null) cancelAnimationFrame(this.frame)
    this.frame = null
    for (const track of this.stream?.getTracks() ?? []) track.stop()
    this.stream = null
  }
}
