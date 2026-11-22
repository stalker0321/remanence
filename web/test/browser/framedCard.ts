/**
 * A drawn postcard the frozen postcard matcher (S3) reads like a photographed
 * one: a portrait 2:3 card on a plain ground, inside the centred frame the
 * scan screen draws, so the localizer finds one card and the sender's crop and
 * the receiver's frame show the same picture. One seed is one card; another
 * seed is another card. Synthetic only: no field-accuracy claim.
 */
function mulberry32(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let value = Math.imul(state ^ (state >>> 15), 1 | state)
    value = (value + Math.imul(value ^ (value >>> 7), 61 | value)) ^ value
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296
  }
}

export async function framedCardJpeg(seed: number, width: number, height: number): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = '#101418'
  context.fillRect(0, 0, width, height)
  // The matcher's frame (`frameBounds`); the card takes most of it.
  const frameWidth = Math.min(width * 0.6934, height * 0.78 * 2 / 3)
  const cardWidth = Math.round(frameWidth * 0.86)
  const cardHeight = Math.round(cardWidth * 1.5)
  const left = Math.round((width - cardWidth) / 2)
  const top = Math.round((height - cardHeight) / 2)
  context.save()
  context.beginPath()
  context.rect(left, top, cardWidth, cardHeight)
  context.clip()
  context.fillStyle = '#f2ead8'
  context.fillRect(left, top, cardWidth, cardHeight)
  const random = mulberry32(seed)
  for (let index = 0; index < 260; index++) {
    context.fillStyle = `hsl(${Math.floor(random() * 360)},${30 + Math.floor(random() * 60)}%,${15 + Math.floor(random() * 70)}%)`
    context.fillRect(left + random() * cardWidth, top + random() * cardHeight, 6 + random() * 40, 4 + random() * 30)
  }
  context.restore()
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!blob) throw new Error('encode failed')
  return new Uint8Array(await blob.arrayBuffer())
}
