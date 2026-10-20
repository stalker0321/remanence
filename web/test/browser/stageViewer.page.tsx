import { flushSync } from "react-dom"
import { createRoot, type Root } from "react-dom/client"
import { CollageStage } from "../../src/collage/stage"
import type { HeldSheet } from "../../src/collage/memory"
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from "../../src/collage/creatorPreview"
import { styleById } from "../../src/collage/styles"
import type { TextMeasurer } from "../../src/collage/prepare"
import { catalog, type Language, type MessageKey } from "../../src/i18n/catalog"
import "../../src/design/tokens.css"

const NOTE = "T354 stage viewer proof note."
const measurer: TextMeasurer = {
  measure(request) {
    return { width: request.text.length * 10, height: request.line, hasVisualOverflow: false }
  },
}

const originalCreate = URL.createObjectURL.bind(URL)
const objectUrls: string[] = []

function canvasJpeg(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    try {
      canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error("no jpeg")), "image/jpeg", 0.9)
    } catch (error) {
      reject(error)
    }
  })
}

function photoCanvas(width: number, height: number, tint: string, mark: string): HTMLCanvasElement {
  const canvas = document.createElement("canvas")
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext("2d")
  if (!context) throw new Error("no 2d context")
  context.fillStyle = tint
  context.fillRect(0, 0, width, height)
  context.fillStyle = "#f4f2ec"
  context.font = "bold 96px StudySans"
  context.fillText(mark, 60, 160)
  context.fillStyle = mark === "L" ? "#c4554a" : mark === "P" ? "#2e8b57" : "#245a9c"
  context.fillRect(width * 0.15, height * 0.4, width * 0.3, height * 0.35)
  return canvas
}

interface Fixture { held: HeldSheet; urls: string[] }

async function buildHeld(tint: string, labels: string[]): Promise<Fixture> {
  const canvases = [
    photoCanvas(1200, 600, tint, labels[0]!),
    photoCanvas(600, 1200, tint, labels[1]!),
    photoCanvas(900, 900, tint, labels[2]!),
  ]
  const urls = await Promise.all(canvases.map(async (canvas) => originalCreate(await canvasJpeg(canvas))))
  objectUrls.push(...urls)
  const photos = canvases.map((canvas, index) => ({
    contentId: `p${index}`,
    width: canvas.width,
    height: canvas.height,
    contentHash: `${(index + 1).toString(16).padStart(2, "0")}`.repeat(32),
  }))
  const style = styleById("press")!
  const view = previewCandidates({ id: "creator", sender: "sergio", photos, note: NOTE }, undefined, plannerFromTextMeasurer(measurer, 640, 640, style.note))[0]!
  const scene = creatorScene({
    view,
    styleId: "press",
    senderHandle: "sergio",
    noteText: NOTE,
    photos: new Map(photos.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
  })
  return {
    held: {
      scene,
      photos: new Map(photos.map((photo, index) => [photo.contentId, urls[index]!])),
      artwork: null,
    },
    urls,
  }
}

const status = document.createElement("p")
status.id = "stage-viewer-status"
status.textContent = "starting"
document.body.appendChild(status)
const host = document.createElement("div")
document.body.appendChild(host)

let language: Language = "en"
let current: Fixture
let root: Root | null = null
const t = (key: MessageKey): string => catalog[language][key]

function render(): void {
  root ??= createRoot(host)
  flushSync(() => {
    root!.render(<CollageStage held={current.held} t={t} onClose={() => undefined} />)
  })
}

async function main(): Promise<void> {
  const a = await buildHeld("#7a2d3b", ["L", "P", "S"])
  const b = await buildHeld("#2d4b7a", ["2", "3", "4"])
  current = a
  render()
  const api = {
    language(next: string): void {
      language = next === "ru" || next === "uk" ? next : "en"
      render()
    },
    replace(): void {
      current = b
      render()
    },
    unmount(): void {
      root?.unmount()
      root = null
    },
    photoUrls(): string[] {
      return [...current.held.photos.values()]
    },
  }
  ;(window as unknown as { __stageViewer?: unknown }).__stageViewer = api
  const deadline = performance.now() + 30000
  while (performance.now() < deadline) {
    const canvas = document.querySelector(".stage canvas") as HTMLCanvasElement | null
    if (canvas?.dataset.painted === "ready") {
      status.textContent = "ready"
      return
    }
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
  status.textContent = "failed"
}

void main()
