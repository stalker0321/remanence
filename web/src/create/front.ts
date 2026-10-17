import type { RecipientSnapshot } from './recipient'
import type { CameraPort, CameraSession, StillCapture } from '../scan/camera'
import { ScanError } from '../scan/errors'

export interface FrontStill extends StillCapture {
  userId: string
  keyBundleId: string
  directoryVersion: string
}

/**
 * One local picture for an already confirmed recipient.
 * Closing it stops the camera and zeroes the bytes. It is not uploaded and it is not a fingerprint.
 */
export class FrontHold {
  #epoch = 0
  #session: CameraSession | null = null
  #still: FrontStill | null = null

  still(): FrontStill | null {
    return this.#still
  }

  async open(camera: CameraPort, signal?: AbortSignal): Promise<CameraSession> {
    if (signal?.aborted) throw new ScanError('CANCELLED')
    this.close()
    const epoch = this.#epoch
    const session = await camera.open(signal)
    if (signal?.aborted || epoch !== this.#epoch) {
      session.stop()
      throw new ScanError('CANCELLED')
    }
    this.#session = session
    return session
  }

  async take(snapshot: RecipientSnapshot): Promise<FrontStill> {
    const session = this.#session
    if (!session) throw new ScanError('CAMERA_UNAVAILABLE')
    const picture = await session.capture()
    if (this.#session !== session) {
      picture.bytes.fill(0)
      throw new ScanError('CANCELLED')
    }
    this.#clearStill()
    const still: FrontStill = {
      bytes: picture.bytes,
      width: picture.width,
      height: picture.height,
      userId: snapshot.userId,
      keyBundleId: snapshot.keyBundleId,
      directoryVersion: snapshot.directoryVersion,
    }
    this.#still = still
    return still
  }

  retain(snapshot: RecipientSnapshot | null): void {
    const still = this.#still
    if (!still) return
    if (
      snapshot &&
      snapshot.userId === still.userId &&
      snapshot.keyBundleId === still.keyBundleId &&
      snapshot.directoryVersion === still.directoryVersion
    ) {
      return
    }
    this.close()
  }

  close(): void {
    this.#epoch += 1
    this.#session?.stop()
    this.#session = null
    this.#clearStill()
  }

  #clearStill(): void {
    this.#still?.bytes.fill(0)
    this.#still = null
  }
}
