import type { CSSProperties } from 'react'
import { css } from './paint'
import type { Scene, SceneText } from './prepare'

/**
 * The letter on the back of the card (ADR-021 §2). The back is the paper the
 * note is written on, in the note's own face, and holds the whole text; the front
 * keeps its start and a small turn mark at the end of the note. Nothing here
 * is sealed: a receiver draws the mark and the back whenever the manifest
 * carries a letter, so older receivers simply see the front.
 */

/** The mark's square in canvas px; `backed` when it sits over the end of a full line. */
export interface LetterMarkPlace {
  x: number
  y: number
  size: number
  backed: boolean
}

/**
 * The layouts leave 7 to 17 px between their elements, so the mark has no
 * room of its own: it lives inside the note region, right after the last
 * word. When the last line is full it takes the end of that line on a patch
 * of paper. Android's `letterMarkPlace` is the same rule.
 */
export function letterMarkPlace(note: SceneText): LetterMarkPlace {
  const region = note.region
  const size = Math.min(18, Math.max(12, Math.round(note.size * 1.1)))
  const gap = Math.max(4, Math.round(note.size * 0.3))
  const rows = note.noteLayout?.lines.filter((row) => row.text !== '') ?? []
  const last = rows.length > 0 ? rows[rows.length - 1]! : null
  const right = region.x + region.width
  const lineEnd = last === null ? right : region.x + last.width
  const baseline = last === null ? region.y + note.size : last.baseline
  const backed = lineEnd + gap + size > right
  const x = backed ? right - size : lineEnd + gap
  const centre = baseline - note.size * 0.3
  const y = Math.min(Math.max(centre - size / 2, region.y), Math.max(region.y, region.y + region.height - size))
  return { x, y, size, backed }
}

/**
 * The colour the note is written on. Some layouts lay the note on a plane of
 * their own (a mount, a printed panel), painted over the paper; the last such
 * plane under the middle of the note is its ground. Android's `noteGround` is
 * the same rule.
 */
export function noteGround(scene: Scene): number {
  const region = scene.note?.region
  if (!region) return scene.paper
  const x = region.x + region.width / 2
  const y = region.y + region.height / 2
  let ground = scene.paper
  for (const decor of scene.decor) {
    const plane = decor.region
    if (x >= plane.x && x < plane.x + plane.width && y >= plane.y && y < plane.y + plane.height) ground = decor.background
  }
  return ground
}

/** The whole note turns the card; the mark at its end says so. */
export function LetterMark({ scene, label, onTurn }: { scene: Scene; label: string; onTurn: () => void }) {
  const note = scene.note
  if (note === null) return null
  const region = note.region
  const place = letterMarkPlace(note)
  const hit: CSSProperties = {
    left: `${(region.x / scene.canvasWidth) * 100}%`,
    top: `${(region.y / scene.canvasHeight) * 100}%`,
    width: `${(region.width / scene.canvasWidth) * 100}%`,
    height: `${(region.height / scene.canvasHeight) * 100}%`,
    color: css(note.color),
  }
  const mark: CSSProperties = {
    left: `${((place.x - region.x) / region.width) * 100}%`,
    top: `${((place.y - region.y) / region.height) * 100}%`,
    width: `${(place.size / region.width) * 100}%`,
    height: `${(place.size / region.height) * 100}%`,
    background: place.backed ? css(noteGround(scene)) : undefined,
    boxShadow: place.backed ? `-0.9cqw 0 0.9cqw 0.5cqw ${css(noteGround(scene))}` : undefined,
  }
  return (
    <button type="button" className="letter-mark" style={hit} aria-label={label} title={label} onClick={onTurn}>
      <svg style={mark} viewBox="0 0 24 24" aria-hidden="true">
        <path d="M2.5 18a9 9 0 0 1 18 0" />
        <path d="M15.5 14l5 4 3-5.5" />
      </svg>
    </button>
  )
}

export function CardBack({ scene, letter, title, frontLabel, onTurn }: { scene: Scene; letter: string; title: string; frontLabel: string; onTurn: () => void }) {
  const face = scene.note
  const style: CSSProperties = {
    background: css(noteGround(scene)),
    color: css(face?.color ?? scene.ink),
    fontFamily: face ? `${face.family}, serif` : 'StudySerif, serif',
    fontStyle: face?.italic ? 'italic' : 'normal',
  }
  return (
    <section className="card-back" style={style} aria-label={title}>
      <div className="card-back-text">{letter}</div>
      <button type="button" className="card-back-turn" style={{ color: style.color }} onClick={onTurn}>
        <span aria-hidden="true">↺</span> {frontLabel}
      </button>
    </section>
  )
}
