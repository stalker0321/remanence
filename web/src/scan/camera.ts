import { ScanError } from './errors'

export interface StillCapture {
  bytes: Uint8Array
  width: number
  height: number
}

export interface CameraSession {
  preview: HTMLVideoElement
  capture(): Promise<StillCapture>
  stop(): void
}

export interface CameraPort {
  open(signal?: AbortSignal): Promise<CameraSession>
}

/** Local preview and still. Nothing in this module talks to the API. */
export function browserCamera(doc: Document): CameraPort {
  return {
    async open(signal) {
      if (signal?.aborted) throw new ScanError('CANCELLED')
      const media = globalThis.navigator?.mediaDevices
      if (typeof media?.getUserMedia !== 'function') throw new ScanError('CAMERA_UNAVAILABLE')
      let stream: MediaStream
      try {
        stream = await media.getUserMedia({
          audio: false,
          video: { facingMode: { ideal: 'environment' } },
        })
      } catch (error) {
        if (named(error, 'NotAllowedError') || named(error, 'SecurityError')) throw new ScanError('CAMERA_DENIED')
        throw new ScanError('CAMERA_UNAVAILABLE')
      }
      if (signal?.aborted) {
        stopTracks(stream)
        throw new ScanError('CANCELLED')
      }
      const preview = doc.createElement('video')
      preview.autoplay = true
      preview.muted = true
      preview.playsInline = true
      preview.srcObject = stream
      try {
        await preview.play()
      } catch {
        stopTracks(stream)
        throw new ScanError('CAMERA_UNAVAILABLE')
      }
      return {
        preview,
        async capture() {
          const width = preview.videoWidth
          const height = preview.videoHeight
          if (width < 1 || height < 1) throw new ScanError('CAMERA_UNAVAILABLE')
          const canvas = doc.createElement('canvas')
          canvas.width = width
          canvas.height = height
          const context = canvas.getContext('2d')
          if (!context) throw new ScanError('CAMERA_UNAVAILABLE')
          context.drawImage(preview, 0, 0, width, height)
          const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
          if (!blob) throw new ScanError('CAMERA_UNAVAILABLE')
          return { bytes: new Uint8Array(await blob.arrayBuffer()), width, height }
        },
        stop() {
          preview.srcObject = null
          stopTracks(stream)
        },
      }
    },
  }
}

export class LocalScan {
  #capture: StillCapture | null = null
  #session: CameraSession | null = null

  held(): StillCapture | null {
    return this.#capture
  }

  async shoot(camera: CameraPort, signal?: AbortSignal): Promise<StillCapture> {
    this.release()
    const session = await camera.open(signal)
    if (signal?.aborted) {
      session.stop()
      throw new ScanError('CANCELLED')
    }
    this.#session = session
    try {
      const capture = await session.capture()
      this.#capture = capture
      return capture
    } catch (error) {
      this.release()
      throw error
    }
  }

  release(): void {
    this.#session?.stop()
    this.#session = null
    this.#capture = null
  }
}

function stopTracks(stream: MediaStream): void {
  for (const track of stream.getTracks()) track.stop()
}

function named(error: unknown, name: string): boolean {
  return typeof error === 'object' && error !== null && 'name' in error && error.name === name
}
