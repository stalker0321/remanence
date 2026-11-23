import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { roundRadii } from './geometry'
import type { Scene, ScenePhoto } from './prepare'
import { claimSound, releaseSound } from './soundArbiter'
import type { ManifestVideo } from './wire'

/**
 * The video square over its poster's tile (ADR-021): viewfinder corners, a
 * red dot and the length. While the card is open it loops without sound, the
 * way a live photo moves; pressing it plays from the start with sound (and
 * stops the voice note), pressing again or reaching the end goes back to the
 * silent loop. With reduced motion it stays on the poster the card painted
 * until pressed. The film is an object URL the held sheet revokes on close.
 */
export function VideoSquare({ scene, video, film, watch, mute }: {
  scene: Scene
  video: ManifestVideo
  film: string
  watch: string
  mute: string
}) {
  const element = useRef<HTMLVideoElement>(null)
  const owner = useRef({})
  const [sound, setSound] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const still = useReducedMotion()
  const photo = scene.photos.find((item) => item.contentId === video.posterContentId)

  useEffect(() => {
    const target = element.current
    const self = owner.current
    if (target && !still) void target.play().catch(() => undefined)
    return () => {
      target?.pause()
      releaseSound(self)
    }
  }, [film, still])

  if (!photo) return null

  function quiet(target: HTMLVideoElement): void {
    target.muted = true
    releaseSound(owner.current)
    setSound(false)
    if (still) {
      target.pause()
      target.currentTime = 0
    } else {
      void target.play().catch(() => undefined)
    }
  }

  function press(): void {
    const target = element.current
    if (!target) return
    if (sound) {
      quiet(target)
      return
    }
    claimSound(owner.current, () => quiet(target))
    target.currentTime = 0
    target.muted = false
    setSound(true)
    void target.play().catch(() => quiet(target))
  }

  const length = clock(sound ? elapsed : video.durationMs)
  const label = sound ? mute : watch
  return (
    <button
      type="button"
      className="video-square"
      style={{ ...tileBox(scene, photo), ...tileClip(scene, photo) }}
      aria-label={label}
      aria-pressed={sound}
      title={label}
      onClick={press}
    >
      <video
        ref={element}
        src={film}
        style={filmBox(photo, video.posterSide)}
        muted
        loop={!sound}
        playsInline
        preload="auto"
        aria-hidden="true"
        onTimeUpdate={(event) => setElapsed(event.currentTarget.currentTime * 1000)}
        onEnded={(event) => quiet(event.currentTarget)}
      />
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        <path d="M7 19V7H19M81 7H93V19M93 81V93H81M19 93H7V81" />
        <circle cx="21.5" cy="14" r="2.6" />
        {still && !sound ? <path className="video-square-play" d="M44 38L62 50L44 62Z" /> : null}
      </svg>
      <span className="video-square-length">{length}</span>
      {sound ? <span className="video-square-progress" style={{ width: `${Math.min(100, (elapsed / video.durationMs) * 100)}%` }} /> : null}
    </button>
  )
}

function useReducedMotion(): boolean {
  const query = typeof window === 'undefined' || typeof window.matchMedia !== 'function'
    ? null
    : window.matchMedia('(prefers-reduced-motion: reduce)')
  const [reduced, setReduced] = useState(query?.matches ?? false)
  useEffect(() => {
    if (!query) return
    const update = () => setReduced(query.matches)
    query.addEventListener('change', update)
    return () => query.removeEventListener('change', update)
  }, [query])
  return reduced
}

function clock(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function tileBox(scene: Scene, photo: ScenePhoto): CSSProperties {
  const tile = photo.destination
  return {
    left: `${(tile.x / scene.canvasWidth) * 100}%`,
    top: `${(tile.y / scene.canvasHeight) * 100}%`,
    width: `${(tile.width / scene.canvasWidth) * 100}%`,
    height: `${(tile.height / scene.canvasHeight) * 100}%`,
  }
}

/** The tile's own shape, as the card paints the photo under it. */
function tileClip(scene: Scene, photo: ScenePhoto): CSSProperties {
  if (photo.shape === 'ellipse') return { clipPath: 'ellipse(50% 50% at 50% 50%)' }
  if (photo.shape !== 'round') return {}
  const tile = photo.destination
  const radii = roundRadii(scene.grammarId, tile.width, tile.height)
  const across = [0, 2, 4, 6].map((index) => `${((radii[index] ?? 0) / tile.width) * 100}%`)
  const down = [1, 3, 5, 7].map((index) => `${((radii[index] ?? 0) / tile.height) * 100}%`)
  return { borderRadius: `${across.join(' ')} / ${down.join(' ')}` }
}

/** The film sits where the poster's crop puts the first frame, so nothing jumps when it starts. */
function filmBox(photo: ScenePhoto, side: number): CSSProperties {
  const source = photo.source
  return {
    left: `${(-source.x / source.width) * 100}%`,
    top: `${(-source.y / source.height) * 100}%`,
    width: `${(side / source.width) * 100}%`,
    height: `${(side / source.height) * 100}%`,
  }
}
