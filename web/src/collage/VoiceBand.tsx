import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { css } from './paint'
import type { Scene } from './prepare'

/**
 * The voice note's band is its button (ADR-021 §5): the whole band plays and
 * pauses the sound. The card draws the play glyph; while the sound plays, a
 * pause glyph in the same form covers it. The sound is an object URL the held
 * sheet revokes on close.
 */
export function VoiceBand({ scene, sound, listen, pause }: { scene: Scene; sound: string; listen: string; pause: string }) {
  const audio = useRef<HTMLAudioElement>(null)
  const [playing, setPlaying] = useState(false)
  useEffect(() => {
    const element = audio.current
    return () => element?.pause()
  }, [sound])
  const music = scene.music
  const band = music?.band
  if (!music || !band) return null
  const region = music.region
  const hit: CSSProperties = {
    left: `${(region.x / scene.canvasWidth) * 100}%`,
    top: `${(region.y / scene.canvasHeight) * 100}%`,
    width: `${(region.width / scene.canvasWidth) * 100}%`,
    height: `${(region.height / scene.canvasHeight) * 100}%`,
  }
  const play = band.play
  const glyph: CSSProperties = {
    left: `${((play.x - region.x) / region.width) * 100}%`,
    top: `${((play.y - region.y) / region.height) * 100}%`,
    width: `${(play.width / region.width) * 100}%`,
    height: `${(play.height / region.height) * 100}%`,
  }
  const ink = css(music.color)
  const ground = css(music.background)
  const label = playing ? pause : listen
  return (
    <button
      type="button"
      className="voice-band"
      style={hit}
      aria-label={label}
      aria-pressed={playing}
      title={label}
      onClick={() => {
        const element = audio.current
        if (!element) return
        if (element.paused) void element.play().catch(() => setPlaying(false))
        else element.pause()
      }}
    >
      <audio
        ref={audio}
        src={sound}
        preload="auto"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={(event) => {
          event.currentTarget.currentTime = 0
          setPlaying(false)
        }}
      />
      {playing ? (
        <svg style={glyph} viewBox="0 0 24 24" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
          {band.playStyle === 'circle' ? <circle cx="12" cy="12" r="12" fill={ink} /> : null}
          {band.playStyle === 'ring' ? <circle cx="12" cy="12" r="11.3" fill={ground} stroke={ink} strokeWidth="1.4" /> : null}
          {band.playStyle === 'glyph' ? <rect width="24" height="24" fill={ground} /> : null}
          <PauseBars size={band.playStyle === 'glyph' ? 20 : 9} fill={band.playStyle === 'circle' ? ground : ink} />
        </svg>
      ) : null}
    </button>
  )
}

/** Two bars in a centred square of `size` units of the 24-unit box. */
function PauseBars({ size, fill }: { size: number; fill: string }) {
  const top = 12 - size / 2
  const bar = size * 0.3
  return (
    <>
      <rect x={12 - size * 0.4} y={top} width={bar} height={size} rx={bar * 0.25} fill={fill} />
      <rect x={12 + size * 0.1} y={top} width={bar} height={size} rx={bar * 0.25} fill={fill} />
    </>
  )
}
