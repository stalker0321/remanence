import { CollageError } from './errors'
import {
  centerCoverBase,
  type NormalizedCrop,
  correctEditorCrop,
  toCropWindow,
  CROP_ASPECT_TOLERANCE,
  type CropTransform,
  type CropWindowInts,
} from './crop'
import type { PhotoShape, Rect } from './model'
import { markColor, markFits, musicLayers, sceneDecor, sourceRect, type TextMeasurer, type TrackWords } from './prepare'
import {
  ACTIVE_GRAMMAR_IDS,
  applicability,
  geometryKey,
  grammarDef,
  noteState,
  plans as grammarPlans,
  rankBuckets,
  sourceOrderAssignment,
  validatePlan,
  type Plan,
  type PlanInput,
  type PlanMeasure,
} from './planGrammars'
import { markLine } from './model'
import { mountRect } from './mount'
import { layoutCreatorNote, wrapNoteLines, type CreatorNoteFace, type CreatorNoteLayout } from './creatorNote'
import { opaqueColor, roleTextType, styleById, styleIds, type CollageStyle } from './styles'
import type { Scene } from './prepare'
import { solveCropDetailed, type CollageAnalysis } from './analysis/cropSolver'
import { assign, assignmentOk, hasProminentFace, isObjectPhoto, matrixCell } from './analysis/resolve'

/**
 * Creator preview adapter (pure, no DOM): a grammar plan + normalized
 * photos + a bundled style → aspect-preserving per-slot source windows +
 * a creator `Scene` for `paintScene`. `creatorChoices` offers the same
 * pool as Android: one layout per grammar and style.
 *
 * No `ResolvedExpression` is manufactured, no grant is issued, and the
 * admitted `CollageStage` is never used: the scene is a local
 * presentation input only. Every slot keeps the destination aspect
 * after `sourceRect` rounding (existing 5e-3 tolerance) — a mismatch
 * is an explicit refusal, never a stretch. Photo ids must be unique;
 * the caller enforces it and this adapter re-checks it.
 */

export interface CreatorPhoto {
  contentId: string
  width: number
  height: number
  contentHash: string
}

export interface CreatorSlotView {
  photoId: string
  destination: Rect
  sourcePx: Rect
  crop: CropWindowInts
  shape: PhotoShape
  /** Effective transform after the composed domain clamps — what the window actually used. */
  effective: CropTransform
}

export interface CreatorNoteView {
  region: Rect
  state: string
  /** The face size and line box the region was measured at. */
  size: number
  line: number
}

export interface CreatorMusicView {
  region: Rect
  mode: string
}

export interface CreatorDecorView {
  kind: 'carrier' | 'acid-ribbon' | 'print-plane'
  region: Rect
}

export interface CreatorProvenanceView {
  kind: 'brand' | 'sender'
  text: string
  region: Rect
  family: string
  size: number
  weight: number
  italic: boolean
  line: number
  orientation: 'horizontal' | 'vertical-up' | 'vertical-down'
  align: 'left' | 'center' | 'right'
}

export interface CreatorCandidateView {
  /** `g<id>`, as sealed. */
  grammarId: string
  styleId: string
  branch: string
  slots: CreatorSlotView[]
  note: CreatorNoteView | null
  music: CreatorMusicView | null
  provenance: CreatorProvenanceView[]
  decor: CreatorDecorView[]
}

function fail(_message: string): never {
  throw new CollageError('REJECTED')
}

function uniquePhotos(photos: CreatorPhoto[]): Map<string, CreatorPhoto> {
  const byId = new Map<string, CreatorPhoto>()
  for (const photo of photos) {
    if (typeof photo.contentId !== 'string' || photo.contentId.trim() === '') fail('creator photo contentId must not be blank')
    if (
      !Number.isFinite(photo.width) ||
      !Number.isFinite(photo.height) ||
      photo.width <= 0 ||
      photo.height <= 0
    ) {
      fail(`creator photo dimensions must be finite and positive, got ${photo.contentId}`)
    }
    if (byId.has(photo.contentId)) fail(`creator photo contentId must be unique, got ${photo.contentId}`)
    byId.set(photo.contentId, photo)
  }
  return byId
}

/**
 * Aspect-preserving source window for one slot: the stored UI transform
 * is clamped into the frozen control domain FIRST, then the
 * source-bounded editor clamp plus structural/aspect checks
 * (`correctEditorCrop`). Refuses instead of stretching. Returns the
 * effective transform alongside the slot so readout and preview agree.
 */
export function previewSlot(
  tile: { photoId: string; rect: Rect; shape: string },
  photo: CreatorPhoto,
  transform: CropTransform | null,
  solved: NormalizedCrop | null = null,
): CreatorSlotView & { effective: CropTransform } {
  if (tile.rect.width <= 0 || tile.rect.height <= 0) fail(`creator slot has a non-positive destination for ${tile.photoId}`)
  // The analysed crop is solved for the fractional tile; fit it to the mounted cell.
  const base = solved !== null
    ? fitToCell(solved, photo.width, photo.height, tile.rect.width, tile.rect.height)
    : centerCoverBase(photo.width, photo.height, tile.rect.width, tile.rect.height)
  if (!base) fail(`creator slot has impossible dimensions for ${tile.photoId}`)
  const admitted = correctEditorCrop(
    base,
    { width: tile.rect.width, height: tile.rect.height },
    photo.width,
    photo.height,
    transform ?? { z: 1, xPercent: 0, yPercent: 0 },
  )
  if (!admitted.accepted) fail(`creator crop refused for ${tile.photoId}: ${admitted.reason}`)
  const crop = toCropWindow(admitted.window)
  const source = sourceRect(crop, photo.width, photo.height)
  if (!source) fail(`creator crop window degenerates for ${tile.photoId}`)
  const destinationAspect = tile.rect.width / tile.rect.height
  const sourceAspect = source.width / source.height
  if (Math.abs(sourceAspect - destinationAspect) / destinationAspect > CROP_ASPECT_TOLERANCE) {
    fail(`creator source window would stretch ${tile.photoId}`)
  }
  return {
    photoId: tile.photoId,
    destination: { x: tile.rect.x, y: tile.rect.y, width: tile.rect.width, height: tile.rect.height },
    sourcePx: source,
    crop,
    shape: tile.shape as PhotoShape,
    effective: admitted.transform,
  }
}

/**
 * The largest window of the cell's aspect centred on `crop` and inside it
 * when it can be, never outside the source: the solved window keeps its
 * subject while matching the integer cell the wire carries.
 */
export function fitToCell(crop: NormalizedCrop, sourceWidth: number, sourceHeight: number, cellWidth: number, cellHeight: number): NormalizedCrop | null {
  const target = cellWidth / cellHeight
  let width = crop.width
  let height = crop.height
  const aspect = (width * sourceWidth) / (height * sourceHeight)
  if (aspect > target) width = (height * sourceHeight * target) / sourceWidth
  else height = (width * sourceWidth) / (target * sourceHeight)
  if (!(width > 0 && height > 0) || width > 1 || height > 1) return null
  const cx = crop.x + crop.width / 2
  const cy = crop.y + crop.height / 2
  return {
    x: Math.min(1 - width, Math.max(0, cx - width / 2)),
    y: Math.min(1 - height, Math.max(0, cy - height / 2)),
    width,
    height,
  }
}

/** Where an analysed branch puts each photo: the photo per tile and its solved crop. */
export interface BranchPlacement {
  /** Photo id per tile, in tile order. */
  order: string[]
  crops: ReadonlyMap<string, NormalizedCrop>
}

export interface PreviewInput {
  id: string
  sender: string
  photos: CreatorPhoto[]
  note: string | null
  /** A chosen song; `artwork` only when its cover is held for the sheet. */
  music?: { artwork: boolean } | null
}

/** Effective face for the creator's rendered note (family/weight/italic). */
export interface NoteFace {
  font: string
  weight: number
  italic: boolean
}

/**
 * Adapts the injected browser `TextMeasurer` to the plan note
 * contract. Line count is derived from the measured height at the
 * requested line box (`round(height / line)`); the caller must pass a
 * measurer whose height is a whole number of line boxes for wrapped
 * text (the provided canvas measurer lays out whole lines).
 *
 * `noteFace` pins the FACE actually rendered by `creatorScene` (the
 * style note family/weight/italic), so the region is measured with the
 * same typography the note is drawn in. The plan grammar's own requested
 * face (`font`) is ignored when `noteFace` is supplied; T233's pure
 * geometry source is untouched and the size/line density still comes
 * from the grammar's short/regular rows. Without `noteFace` the grammar
 * face is used (unchanged behaviour for the golden-parity adapter tests).
 */
export function plannerFromTextMeasurer(
  measurer: TextMeasurer,
  maxWidth: number,
  maxHeight: number,
  noteFace?: NoteFace,
): PlanMeasure {
  return (text, width, size, line, font) => {
    if (noteFace === undefined) {
      const measured = measurer.measure({ text, family: font, size, weight: 400, italic: false, line, maxWidth: Math.min(width, maxWidth), maxHeight })
      return { height: measured.height, lines: Math.max(1, Math.round(measured.height / line)) }
    }
    // Real creator note: use the SAME word-wrap the painter uses, with an
    // unwrapped line-width primitive from the injected measurer
    // (`softWrap:false` measures the widest literal line, no wrapping).
    const measureLineWidth = (lineText: string): number =>
      measurer.measure({
        text: lineText,
        family: noteFace.font,
        size,
        weight: noteFace.weight,
        italic: noteFace.italic,
        line,
        maxWidth: Number.POSITIVE_INFINITY,
        maxHeight: Number.POSITIVE_INFINITY,
        softWrap: false,
      }).width
    const wrapped = wrapNoteLines(text, Math.min(width, maxWidth), measureLineWidth)
    return { height: wrapped.lines.length * line, lines: wrapped.lines.length }
  }
}

function planInput(input: PreviewInput): PlanInput {
  return {
    id: input.id,
    sender: input.sender,
    photos: input.photos.map((photo) => ({ id: photo.contentId, width: photo.width, height: photo.height })),
    note: input.note,
    music: input.music ?? null,
  }
}

/** One grammar in one bundled style. Grammar 5 in `press` is the default the tests pin. */
export interface ViewChoice {
  grammarId: number
  styleId: string
  /** The recipient's own mark fit check; without it marks keep the grammar's type. */
  markFits?: (mark: CreatorProvenanceView) => boolean
}

const DEFAULT_CHOICE: ViewChoice = { grammarId: 5, styleId: 'press' }

/**
 * Complete deterministic enumeration for one grammar and style: one ordered
 * view per branch, slots in plan (authored) order. `transforms` is keyed by
 * photo content id so manual crops survive branch switches. `measure`
 * overrides the line-height stub with real note metrics. Throws the plan
 * layer's typed refusal for unsupported input.
 */
export function previewCandidates(
  input: PreviewInput,
  transforms?: ReadonlyMap<string, CropTransform>,
  measure?: PlanMeasure,
  choice: ViewChoice = DEFAULT_CHOICE,
): CreatorCandidateView[] {
  const byId = uniquePhotos(input.photos)
  const g = grammarDef(choice.grammarId)
  const d = planInput(input)
  if (!applicability(d, g).ok) fail(`grammar ${g.id} does not apply`)
  return grammarPlans(d, g, noteState(d, g, measure), measure).map((plan) => previewCandidate(plan, byId, transforms, choice, input.sender))
}

export function previewCandidate(
  plan: Plan,
  byId: ReadonlyMap<string, CreatorPhoto>,
  transforms?: ReadonlyMap<string, CropTransform>,
  choice: ViewChoice = DEFAULT_CHOICE,
  senderHandle = '',
  placement: BranchPlacement | null = null,
): CreatorCandidateView {
  const style = styleById(choice.styleId)
  if (!style || style.grammar !== plan.grammarId) fail(`style ${choice.styleId} does not belong to grammar ${plan.grammarId}`)
  // T252: mount the fractional plan rects to canonical INT canvas units at
  // this boundary FIRST — the same component-wise floor the Android producer
  // uses (`AllFamilyCandidateProducer`, `AllFamilyV4Adapter`). The crop and
  // source window are derived from the exact integer cell that is painted.
  // Slots are in authored photo order: an analysed branch may seat a photo in
  // another tile, and the sealed placements follow the authored order.
  const tileOf = (photoId: string, index: number) => {
    if (placement === null) return plan.tiles[index]
    const at = placement.order.indexOf(photoId)
    return at < 0 ? undefined : plan.tiles[at]
  }
  const slots = plan.tiles.map((planned, index) => {
    const photoId = placement === null ? planned.photoId : byId.size === plan.tiles.length ? [...byId.keys()][index]! : planned.photoId
    const tile = tileOf(photoId, index)
    const photo = byId.get(photoId)
    if (!photo || !tile) fail(`creator plan needs photo ${photoId}`)
    const destination = mountRect({ x: tile!.rect.x, y: tile!.rect.y, width: tile!.rect.width, height: tile!.rect.height })
    return previewSlot(
      { photoId, rect: destination, shape: tile!.shape },
      photo as CreatorPhoto,
      transforms?.get(photoId) ?? null,
      placement?.crops.get(photoId) ?? null,
    )
  })
  const grammarMarks: CreatorProvenanceView[] = plan.provenance.map((mark) => {
    const kind = mark.role === 'sender' ? ('sender' as const) : ('brand' as const)
    return {
      kind,
      text: kind === 'brand' ? BRAND_TEXT : senderText(senderHandle),
      region: mountRect(mark.rect),
      family: mark.font,
      size: mark.size,
      weight: mark.weight,
      italic: mark.italic,
      line: mark.size === EDITORIAL_BRAND_YIELD_SIZE ? EDITORIAL_BRAND_YIELD_LINE : markLine(mark.size),
      orientation: mark.orientation as CreatorProvenanceView['orientation'],
      align: mark.align as CreatorProvenanceView['align'],
    }
  })
  return {
    grammarId: `g${plan.grammarId}`,
    styleId: style!.id,
    branch: plan.branch,
    slots,
    music: plan.music === null ? null : { region: mountRect(plan.music.rect), mode: plan.music.mode },
    note: plan.note === null ? null : { region: mountRect(plan.note.rect), state: plan.note.state, size: plan.note.size, line: plan.note.line },
    provenance: choice.markFits === undefined ? grammarMarks : styleMarks(grammarMarks, style!, choice.markFits),
    decor: plan.decor.map((plane) => ({ kind: plane.kind, region: mountRect(plane.rect) })),
  }
}

const BRAND_TEXT = 'Remanence'
const EDITORIAL_BRAND_YIELD_SIZE = 64
const EDITORIAL_BRAND_YIELD_LINE = 70

function senderText(handle: string): string {
  return `From ${handle}`
}

/**
 * Android `AllFamilyV4Adapter.styleMarks` for the bundled styles: each mark
 * takes its style's brand or sender type at the largest size, down to 16
 * (brand) or 14 (sender), that the recipient's fit check accepts; a type
 * that fits nowhere keeps the grammar's mark.
 */
function styleMarks(marks: CreatorProvenanceView[], style: CollageStyle, fits: (mark: CreatorProvenanceView) => boolean): CreatorProvenanceView[] {
  return marks.map((mark) => {
    const brand = mark.kind === 'brand'
    const type = brand ? style.brand : style.sender
    const smallest = Math.min(type.size, brand ? 16 : 14)
    for (let size = type.size; size >= smallest; size--) {
      const styled = { ...mark, family: type.font, size, weight: type.weight, italic: type.italic, line: markLine(size) }
      if (fits(styled)) return styled
    }
    return mark
  })
}

/** The recipient's mark check over a creator mark. */
export function creatorMarkFits(measurer: TextMeasurer): (mark: CreatorProvenanceView) => boolean {
  return (mark) => markFits(measurer, { ...mark, width: mark.region.width, height: mark.region.height })
}

export interface CreatorSceneInput {
  view: CreatorCandidateView
  styleId: string
  senderHandle: string
  noteText: string | null
  photos: ReadonlyMap<string, { width: number; height: number }>
  /** The chosen song's words; required exactly when the view has a song band. */
  track?: TrackWords | null
  /** Measures the song words against the band; required with `track`. */
  measurer?: TextMeasurer
}

/**
 * Local presentation scene for `paintScene` — not a sealed expression.
 * Provenance text is bound here (`Remanence` / `From <handle>`); note
 * typography mirrors the adapter's `textType` (`line = ceil(size*1.12)`)
 * while measurement/fit stays with the injected browser measurer.
 */
export function creatorScene(input: CreatorSceneInput): Scene {
  const style = styleById(input.styleId)
  if (!style) fail(`creator style is not bundled: ${input.styleId}`)
  if (style!.id !== input.view.styleId) fail('creator scene style differs from the view')
  if (input.senderHandle.trim() === '') fail('creator sender handle must not be blank')
  const grammarId = input.view.grammarId
  const photos = input.view.slots.map((slot) => {
    const meta = input.photos.get(slot.photoId)
    if (!meta || meta.width <= 0 || meta.height <= 0) fail(`creator is missing source dimensions for ${slot.photoId}`)
    return {
      contentId: slot.photoId,
      destination: { ...slot.destination },
      source: { ...slot.sourcePx },
      shape: slot.shape,
    }
  })
  // The plan's note region was measured at the grammar's state size and line
  // (e.g. StudySerif 27/30 short or 16/20 regular in grammar 5). Render the
  // creator note at that SAME size/line so the measured region contains it;
  // the style supplies the face and palette.
  const note =
    input.noteText !== null && input.view.note !== null
      ? {
          text: input.noteText,
          region: { ...input.view.note.region },
          family: style!.note.font,
          size: input.view.note.size,
          weight: style!.note.weight,
          italic: style!.note.italic,
          line: input.view.note.line,
          color: opaqueColor(style!.palette.ink),
        }
      : null
  const track = input.track ?? null
  if ((track === null) !== (input.view.music === null)) fail('creator song band and chosen song must match')
  let music: Scene['music'] = null
  let artwork: Scene['artwork'] = null
  if (track !== null && input.view.music !== null) {
    if (!input.measurer) fail('creator song needs a measurer')
    // Without a held cover the band is drawn as text, the same as the
    // recipient will see it when the cover cannot be checked.
    const region = { ...input.view.music.region, mode: input.view.music.mode, type: roleTextType(style!.music), artwork: null }
    const layers = musicLayers(region, grammarId, style!, true, false, track, input.measurer!)
    if (typeof layers === 'string') fail(layers)
    music = (layers as Exclude<typeof layers, string>).music
    artwork = (layers as Exclude<typeof layers, string>).artwork
  }
  return {
    canvasWidth: 360,
    canvasHeight: 640,
    styleId: style!.id,
    grammarId,
    paper: opaqueColor(style!.palette.paper),
    ink: opaqueColor(style!.palette.ink),
    sound: opaqueColor(style!.palette.sound),
    soundInk: opaqueColor(style!.palette.soundInk),
    photos,
    note,
    music,
    artwork,
    provenance: input.view.provenance.map((mark) => ({
      kind: mark.kind,
      text: mark.kind === 'brand' ? BRAND_TEXT : senderText(input.senderHandle),
      region: { ...mark.region },
      family: mark.family,
      size: mark.size,
      weight: mark.weight,
      italic: mark.italic,
      line: mark.line,
      orientation: mark.orientation,
      align: mark.align,
      color: markColor(mark.kind, grammarId, style!),
      tracking: mark.kind === 'brand' ? style!.tracking : 0,
    })),
    decor: input.view.decor.map((plane) => sceneDecor(plane.kind, { ...plane.region }, grammarId, style!)),
  }
}

/** The painted note's wrapped rows, measured the way the recipient measures them. */
export function sceneNoteLayout(scene: Scene, measurer: TextMeasurer): { layout: CreatorNoteLayout; face: CreatorNoteFace } | null {
  if (scene.note === null) return null
  const face: CreatorNoteFace = {
    font: scene.note.family,
    weight: scene.note.weight,
    italic: scene.note.italic,
    size: scene.note.size,
    line: scene.note.line,
  }
  const measureLineWidth = (lineText: string): number =>
    measurer.measure({
      text: lineText,
      family: face.font,
      size: face.size,
      weight: face.weight,
      italic: face.italic,
      line: face.line,
      maxWidth: Number.POSITIVE_INFINITY,
      maxHeight: Number.POSITIVE_INFINITY,
      softWrap: false,
    }).width
  return { layout: layoutCreatorNote(scene.note.text, face, scene.note.region, measureLineWidth), face }
}

export interface ChoicesInput {
  preview: PreviewInput
  transforms?: ReadonlyMap<string, CropTransform>
  measurer: TextMeasurer
  track: TrackWords | null
  /** Seeds the order of the offered layouts; one draft keeps one order. */
  seed: string
  /** Only these styles (tests); every bundled style otherwise. */
  styleIds?: readonly string[]
  /**
   * The photos' analyses (faces, objects, fields), keyed by content id. With
   * one for every photo, photos are matched to tiles and cropped the way
   * Android does it; otherwise they keep the authored order and a centred crop.
   */
  analyses?: ReadonlyMap<string, CollageAnalysis> | null
}

/**
 * The sender's pool, as Android builds it: every applicable grammar, its
 * first readable, valid and geometrically distinct branches under the
 * frozen candidate policy (two per grammar, twelve in all, grammar breadth
 * first), each in every bundled style of that grammar. A layout is offered
 * only when it paints the way the recipient will prepare it — the note
 * fits, the song band fits, the marks fit — and one layout is kept per
 * grammar and style. The order is seeded per draft like Android's
 * `CollageVariantOrder`: one card per grammar first, then its other styles.
 *
 * Photos are placed in the authored order with a centred crop: the site has
 * no photo analysis, so grammar 22 (a detected face) never applies and
 * Android's analysis-led matching is not attempted.
 */
export function creatorChoices(input: ChoicesInput): CreatorCandidateView[] {
  const byId = uniquePhotos(input.preview.photos)
  const analysed = analysedPhotos(input)
  const d = planInput(input.preview)
  if (analysed !== null) d.photos = d.photos.map((photo) => ({ ...photo, hasProminentFace: hasProminentFace(analysed.get(photo.id)!) }))
  const matching = analysed === null ? null : new Matching(input.preview.photos.map((photo) => photo.contentId), analysed)
  const allowed = new Set(input.styleIds ?? styleIds())
  const fits = creatorMarkFits(input.measurer)
  const perGrammar = new Map<number, { styles: CollageStyle[]; branches: Map<string, Branch[]> }>()
  const buckets: Array<[number, string[]]> = []
  for (const gid of ACTIVE_GRAMMAR_IDS) {
    const g = grammarDef(gid)
    if (!applicability(d, g).ok) continue
    const styles = styleIds()
      .sort()
      .map((id) => styleById(id)!)
      .filter((style) => style.grammar === gid && allowed.has(style.id))
    if (styles.length === 0) continue
    const branches = new Map<string, Branch[]>()
    for (const style of styles) {
      const measure = plannerFromTextMeasurer(input.measurer, 640, 640, style.note)
      const chosen: Branch[] = []
      const seen = new Set<string>()
      for (const plan of grammarPlans(d, g, noteState(d, g, measure), measure)) {
        if (chosen.length >= 2) break
        let order: string[] | null = null
        if (matching === null) {
          if (!sourceOrderAssignment(plan).feasible) continue
        } else {
          const assigned = matching.assign(plan)
          if (!assignmentOk(assigned)) continue
          order = assigned.compositionOrder
        }
        if (!validatePlan(plan)) continue
        if (seen.has(geometryKey(plan))) continue
        seen.add(geometryKey(plan))
        chosen.push({ plan, order })
      }
      branches.set(style.id, chosen)
    }
    const first = branches.get(styles[0]!.id) ?? []
    if (first.length === 0) continue
    perGrammar.set(gid, { styles, branches })
    buckets.push([gid, first.map((branch) => branch.plan.branch)])
  }
  // The frozen policy caps how many branches of each grammar compete.
  const depth = new Map<number, number>()
  for (const entry of rankBuckets(buckets)) depth.set(entry.grammarId, (depth.get(entry.grammarId) ?? 0) + 1)

  const drawable: CreatorCandidateView[] = []
  for (const [gid, { styles, branches }] of perGrammar) {
    for (const style of styles) {
      const candidates = (branches.get(style.id) ?? []).slice(0, depth.get(gid) ?? 0)
      for (const branch of candidates) {
        const placement = branch.order === null ? null : matching!.placement(branch.plan, branch.order)
        if (branch.order !== null && placement === null) continue
        const view = tryView(branch.plan, byId, input, style, fits, placement)
        if (view === null) continue
        drawable.push(view)
        break
      }
    }
  }
  return arrange(drawable, input.seed)
}

interface Branch {
  plan: Plan
  /** Photo per tile from the analysed matching; null keeps the authored order. */
  order: string[] | null
}

function analysedPhotos(input: ChoicesInput): ReadonlyMap<string, CollageAnalysis> | null {
  const analyses = input.analyses
  if (!analyses) return null
  for (const photo of input.preview.photos) {
    const analysis = analyses.get(photo.contentId)
    // The analysis must describe this exact photo, or every box is read in the wrong space.
    if (!analysis || analysis.width !== photo.width || analysis.height !== photo.height) return null
  }
  return analyses
}

/**
 * Android's analysed matching: the frozen crop/cost matrix per (photo,
 * tile), the cheapest complete matching, and the envelope-50 crop for each
 * seated photo, all under the product's BALANCED policy.
 */
class Matching {
  private readonly cells = new Map<string, number | null>()
  private readonly anyObject: boolean

  constructor(private readonly ids: string[], private readonly analyses: ReadonlyMap<string, CollageAnalysis>) {
    this.anyObject = ids.some((id) => isObjectPhoto(analyses.get(id)!))
  }

  assign(plan: Plan) {
    const matrix = plan.tiles.map((tile) => this.ids.map((id) => this.cost(id, tile)))
    return assign(plan, this.ids, matrix, false)
  }

  placement(plan: Plan, order: string[]): BranchPlacement | null {
    const crops = new Map<string, NormalizedCrop>()
    for (let index = 0; index < plan.tiles.length; index++) {
      const tile = plan.tiles[index]!
      const id = order[index]!
      const solved = solveCropDetailed(this.analyses.get(id)!, { width: tile.rect.width, height: tile.rect.height, shape: tile.shape, zooms: tile.zooms }, 'BALANCED')
      if (solved.kind !== 'solved') return null
      crops.set(id, solved.crop)
    }
    return { order, crops }
  }

  private cost(id: string, tile: Plan['tiles'][number]): number | null {
    const key = `${id}|${tile.rect.width}|${tile.rect.height}|${tile.shape}|${tile.zooms.join(',')}|${tile.assignmentRole ?? ''}`
    const cached = this.cells.get(key)
    if (cached !== undefined) return cached
    const cell = matrixCell(this.analyses.get(id)!, this.anyObject, {
      slotWidth: tile.rect.width, slotHeight: tile.rect.height, shape: tile.shape, zooms: tile.zooms, assignmentRole: tile.assignmentRole,
    }, 'BALANCED')
    const cost = cell.feasible ? cell.cost! : null
    this.cells.set(key, cost)
    return cost
  }
}

function tryView(
  plan: Plan,
  byId: ReadonlyMap<string, CreatorPhoto>,
  input: ChoicesInput,
  style: CollageStyle,
  fits: (mark: CreatorProvenanceView) => boolean,
  placement: BranchPlacement | null = null,
): CreatorCandidateView | null {
  try {
    const view = previewCandidate(plan, byId, input.transforms, { grammarId: plan.grammarId, styleId: style.id, markFits: fits }, input.preview.sender, placement)
    if (view.provenance.some((mark) => !fits(mark))) return null
    const scene = creatorScene({
      view,
      styleId: style.id,
      senderHandle: input.preview.sender,
      noteText: input.preview.note,
      photos: new Map(input.preview.photos.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
      track: input.track,
      measurer: input.measurer,
    })
    const note = sceneNoteLayout(scene, input.measurer)
    if (note !== null && note.layout.overflow) return null
    return view
  } catch (error) {
    if (error instanceof CollageError) return null
    throw error
  }
}

/** Reliable core and content-fit grammars come first (Android `CollageVariantOrder`). */
const FIRST_TIER = new Set(['g5', 'g18', 'g23', 'g26', 'g27', 'g6', 'g12', 'g20'])

function arrange(views: CreatorCandidateView[], seed: string): CreatorCandidateView[] {
  const byGrammar = new Map<string, CreatorCandidateView[]>()
  for (const view of views) {
    const list = byGrammar.get(view.grammarId) ?? []
    list.push(view)
    byGrammar.set(view.grammarId, list)
  }
  for (const [grammar, list] of byGrammar) {
    list.sort((a, b) => compare(rank(seed, grammar, a.styleId), rank(seed, grammar, b.styleId)) || a.styleId.localeCompare(b.styleId))
  }
  const grammars = [...byGrammar.keys()].sort((a, b) =>
    (FIRST_TIER.has(a) ? 0 : 1) - (FIRST_TIER.has(b) ? 0 : 1) || compare(rank(seed, a), rank(seed, b)) || a.localeCompare(b))
  const deepest = Math.max(0, ...[...byGrammar.values()].map((list) => list.length))
  const out: CreatorCandidateView[] = []
  for (let round = 0; round < deepest; round++) {
    for (const grammar of grammars) {
      const view = byGrammar.get(grammar)![round]
      if (view !== undefined) out.push(view)
    }
  }
  return out
}

function compare(a: bigint, b: bigint): number {
  return a < b ? -1 : a > b ? 1 : 0
}

/** A stable 64-bit FNV-1a rank of the draft seed and the grammar (and style). */
function rank(seed: string, grammar: string, style?: string): bigint {
  const key = style === undefined ? `${seed}\u0000${grammar}` : `${seed}\u0000${grammar}\u0000${style}`
  let hash = 0xcbf29ce484222325n
  for (const byte of new TextEncoder().encode(key)) {
    hash ^= BigInt(byte)
    hash = (hash * 0x100000001b3n) & 0xffffffffffffffffn
  }
  return hash
}
