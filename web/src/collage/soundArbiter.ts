/**
 * One sound at a time on an open card (ADR-021): the voice note and the video
 * square's sound never play together. Whoever starts sounding claims the
 * card's sound and the previous owner is told to stop.
 */
let current: { owner: object; stop: () => void } | null = null

export function claimSound(owner: object, stop: () => void): void {
  const previous = current
  current = { owner, stop }
  if (previous !== null && previous.owner !== owner) previous.stop()
}

export function releaseSound(owner: object): void {
  if (current?.owner === owner) current = null
}
