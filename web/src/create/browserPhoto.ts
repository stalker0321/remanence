import type { DecodedUpright, PhotoCodec } from './normalize'

/**
 * Production browser adapter for photo normalization (T228).
 *
 * Orientation source (normative):
 * - https://html.spec.whatwg.org/multipage/imagebitmap-and-animations.html
 * - https://developer.mozilla.org/en-US/docs/Web/API/Window/createImageBitmap
 *
 * `createImageBitmap` with `imageOrientation: 'fromImage'` decodes the JPEG
 * and applies the EXIF orientation exactly once — the returned bitmap is
 * already upright, so the canvas stage must NOT transform it again (doing
 * so would double-rotate mirrored orientations). Re-encoding from pixels
 * drops all metadata by construction: no EXIF/APPn marker survives.
 */
export function browserPhotoCodec(doc: Document): PhotoCodec {
  return {
    async decode(bytes: Uint8Array): Promise<DecodedUpright> {
      const copy = bytes.slice()
      let bitmap: ImageBitmap
      try {
        bitmap = await createImageBitmap(
          new Blob([copy.buffer as ArrayBuffer], { type: 'image/jpeg' }),
          { imageOrientation: 'fromImage' },
        )
      } catch {
        throw new Error('decode failed')
      }
      if (bitmap.width < 1 || bitmap.height < 1) {
        try {
          bitmap.close()
        } catch {
          /* best-effort */
        }
        throw new Error('decode failed')
      }
      return {
        width: bitmap.width,
        height: bitmap.height,
        async encodeJpeg(width: number, height: number, quality: number): Promise<Uint8Array> {
          const canvas = doc.createElement('canvas')
          canvas.width = width
          canvas.height = height
          const context = canvas.getContext('2d')
          if (!context) throw new Error('encode failed')
          context.drawImage(bitmap, 0, 0, width, height)
          const blob = await new Promise<Blob | null>((resolve, reject) => {
            canvas.toBlob(
              (result) => {
                if (result) resolve(result)
                else reject(new Error('encode failed'))
              },
              'image/jpeg',
              quality / 100,
            )
          })
          if (!blob) throw new Error('encode failed')
          return new Uint8Array(await blob.arrayBuffer())
        },
        release(): void {
          try {
            bitmap.close()
          } catch {
            /* best-effort */
          }
        },
      }
    },
  }
}
