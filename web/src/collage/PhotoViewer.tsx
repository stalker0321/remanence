import { useEffect, useRef, type TouchEvent } from "react"
import type { MessageKey } from "../i18n/catalog"
import type { HeldSheet } from "./memory"
import { stepIndex, swipeStep } from "./viewerNavigation"

const FOCUSABLE = "button:not([disabled]), [href], [tabindex]:not([tabindex=\"-1\"])"

export function PhotoViewer({
  held,
  index,
  t,
  onIndex,
  onClose,
}: {
  held: HeldSheet
  index: number
  t: (key: MessageKey) => string
  onIndex: (index: number) => void
  onClose: () => void
}) {
  const panel = useRef<HTMLDivElement>(null)
  const closeButton = useRef<HTMLButtonElement>(null)
  const touch = useRef<{ x: number; y: number } | null>(null)
  const ids = held.scene.photos.map((item) => item.contentId)
  const count = ids.length
  const url = ids[index] ? held.photos.get(ids[index]!) ?? null : null

  useEffect(() => {
    closeButton.current?.focus()
  }, [])

  useEffect(() => {
    const onKey = (event: globalThis.KeyboardEvent): void => {
      if (event.key === "Escape") {
        event.preventDefault()
        onClose()
        return
      }
      if (event.key === "ArrowRight") {
        event.preventDefault()
        onIndex(stepIndex(index, count, 1))
        return
      }
      if (event.key === "ArrowLeft") {
        event.preventDefault()
        onIndex(stepIndex(index, count, -1))
        return
      }
      if (event.key !== "Tab") return
      const root = panel.current
      if (!root) return
      const nodes = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((element) => element.getClientRects().length > 0)
      if (nodes.length === 0) return
      const first = nodes[0]!
      const last = nodes[nodes.length - 1]!
      const active = document.activeElement
      if (event.shiftKey && active === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && active === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener("keydown", onKey, true)
    return () => document.removeEventListener("keydown", onKey, true)
  }, [count, index, onClose, onIndex])

  if (url === null) return null

  const start = (event: TouchEvent<HTMLDivElement>): void => {
    const point = event.touches[0]
    if (point) touch.current = { x: point.clientX, y: point.clientY }
  }
  const end = (event: TouchEvent<HTMLDivElement>): void => {
    const origin = touch.current
    touch.current = null
    if (!origin) return
    const point = event.changedTouches[0]
    if (!point) return
    const step = swipeStep(origin.x, origin.y, point.clientX, point.clientY)
    if (step !== 0) onIndex(stepIndex(index, count, step))
  }

  return (
    <div className="viewer" role="dialog" aria-modal="true" aria-label={t("photoViewer")}>
      <div className="viewer-panel" ref={panel}>
        <div className="viewer-frame" onTouchStart={start} onTouchEnd={end}>
          <img className="viewer-image" src={url} alt="" draggable={false} />
        </div>
        <p className="viewer-counter" aria-live="polite">{t("photoViewer")} {index + 1} / {count}</p>
        <div className="viewer-actions">
          <button type="button" disabled={index === 0} onClick={() => onIndex(stepIndex(index, count, -1))}>{t("previousPhoto")}</button>
          <button type="button" disabled={index === count - 1} onClick={() => onIndex(stepIndex(index, count, 1))}>{t("nextPhoto")}</button>
          <button ref={closeButton} type="button" className="primary" onClick={onClose}>{t("back")}</button>
        </div>
      </div>
    </div>
  )
}
