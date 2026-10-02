# T154 interface — frozen V5 expression/renderer seams for T151/T150

Read-only contract first: implementation follows in this task, but T151
(enumeration) and T150 (create UI) build against this document now. Base
`acf677b`. Frozen sources: `runtime/grammars.js` (`mark`/`brand`/`sender`
`:31-33`, branch plans `:48-124`), `runtime/expansion.js` (`sound` `:21`,
branch plans `:36-150`), `runtime/styles.js` + `styles-extension.js`
(per-group style records), `runtime/expression.js` (`:42,:56,:59`
style-issue semantics), Astra `guide.mjs` motion row (fixed-frame crop
moves source beneath frame) and access row (four direction buttons).

## Frozen facts (verbatim semantics)

- `mark(role,text,r,size,font,orientation,style)` → `{...r, role, text,
  size, font, orientation, line: ceil(size*1.02), ...style}`.
- `brand(...)` text is always the literal `'Remanence'` (kind
  `'remanence'`); `sender(d,...)` text is always `'From ' + d.sender`.
- Orientations: `'horizontal'`, `'vertical-up'`, `'vertical-down'`.
  Alignments observed: default (left), `'right'`, `'center'`.
- Music modes: `text`, `thumbnail`, `inline`, `tile-art`, `tile-text`,
  `sleeve`, `sleeve-empty`, `spine`, `pulse` (`sound(d,r,mode)`; no-artwork
  maps to `text`, except `sleeve` → `sleeve-empty`).
- Shapes: `rect` (default), `round` (active: g11 interlock), `ellipse`
  (only g19 lenses, which is SPLICED inactive — schema-supported, no active
  producer: explicit residual).
- Decor kinds: `carrier`, `acid-ribbon`, `print-plane` (geometry only in
  frozen output; no color field anywhere).
- Mark color: absent from frozen output entirely — resolved at render time
  to the style `ink` role (palette pairs contrast by construction).
- 24 provenance definitions across 14 families (verbatim table below):
  `S` = fully static box+font; `D(vars)` = box uses layout variables the
  enumerator computes.

| # | family (id) | brand box / font | sender box / font | static? |
|---|---|---|---|---|
| 1 | 5 Editorial Rows | (12,4,340,87) 72 StudySerif H | (16,613,328,23) 20 StudySerif H it R | S |
| 2 | 6 Music Transition | (3,8,24,612) 22 Narrow V-UP w700 | (42,h1+60,304,18) 14 StudySans H | D(h1) |
| 3 | 11 Heterogeneous Modules | (10,uh+11,340,49) 46 Gothic H | (16,615,328,20) 16 Gothic H R | D(uh) |
| 4 | 12 Sequential Strips | (2,8,23,570) 18 StudyMono V-DN | (28,615,316,20) 15 StudyMono H R | S |
| 5 | 16 Equal Plates — SPLICED inactive | (10,586,340,47) 44 StudySans H w700 | (337,12,20,230) 15 StudyMono V-DN | INACTIVE |
| 6 | 17 Continuous Field | (6,565,350,75) 72 Narrow H w700 | (10,543,340,19) 15 StudySans H R | S |
| 7 | 18 Mounted Sheet | (10,23,22,555) 19 StudySans V-UP | (44,606,290,20) 16 StudySans H | S |
| 8 | 21 Letter Margin | (12,586,338,49) 43 Gothic H | (10,seam,340,22) 17 Gothic H C | D(seam) |
| 9 | 21 counterpoint | (10,550,141,23) 21 StudySerif H | (10,10,143,29) 24 StudySerif H it | S |
| 10 | 6 spine variant | (12,618,330,19) 17 StudySans H R | (12,bottom-20,330,21) 18 StudySerif H it | D(bottom) |
| 11 | 20 sleeve-bridge | (15,617,330,20) 18 StudySans H R | (14,ph+5,330,28) 23 StudySerif H it | D(ph) |
| 12 | 21 letter-crown | (18,562|617,324,20) 18 StudySerif H R | (18,sy,324,31) 27 StudySerif H it | D(sy,music?) |
| 13 | 24 Pulse | (17,heroH+4,334,34) 31 Gothic H w700 | (18,heroH+6,324,35) 31 StudySerif H it | D(heroH) |
| 14 | 23 Open Windows | (9,heroH+3,342,33) 30 Gothic H w700 | (14,616,332,20) 17 StudySans H | D(heroH) |
| 15 | 24 pulse-object column | (16,hh+11,330,32) 29 Gothic H w700 | (16,614,328,23) 20 StudySerif H it | D(hh) |
| 16 | 25 Offset Prints | (18,616,324,20) 18 StudySerif H R | (18,total+26,324,25) 22 StudySerif H it | D(total) |
| 17 | 25 pair-and-wide | (18,616,326,20) 18 StudySerif H R | (18,photoEnd+5,326,25) 22 StudySerif H it | D(photoEnd) |
| 18 | 26 Ribbon | (12,610,340,30) 28 Gothic H w700 | (18,seam,324,24) 20 StudySerif H it+R | D(seam) |
| 19 | 26 ribbon-turns | (12,610,340,30) 28 Gothic H w700 | (18,hh+8,324,25) 21 StudySerif H it+R | D(hh) |
| 20 | 27 Monument | (12,615,336,21) 19 StudySans H R | (12,end+5,336,23) 20 StudySans H | D(end) |
| 21 | 22 First Light | sound-spine: sender(12,bottom-20,330,21) 18 StudySerif H it; brand(12,618,330,19) 17 StudySans H R | mixed | D(bottom) |
| 22 | 22 human-hinge | sender(18,heroH+6,324,35) 31 StudySerif H it; brand(12,616,336,20) 18 StudySans H R | mixed | D(heroH) |
| 23 | 23 spanning-window | brand(9,heroH+3,342,33) 30 Gothic H w700; sender(14,616,332,20) 17 StudySans H + acid-ribbon (0,heroH,360,39) | mixed | D(heroH) |
| 24 | 21 letter-full-height | sender(10,10,143,29) 24 StudySerif H it; brand(10,550,141,23) 21 StudySerif H; inline music box | mixed | S |
| 25 | 24 pulse-object column | brand(16,hh+11,330,32) 29 Gothic H w700; sender(16,614,328,23) 20 StudySerif H it + acid-ribbon (0,hh+6,360,39) | mixed | D(hh) |
| 26 | 18 mount branches | shared family provenance (row 7) + carrier (6,5,349,627) | mixed | S |
| 27 | 20 Sleeve & Sequence (base) | (332,10,26,620) 24 StudySerif V-DN | (12,music.y-25,306,22) 18 StudySerif H it | D(music) |
| — | 16 / 19 | SPLICED OUT of defs (`grammars.js:21`) — never port | — | INACTIVE |
| — | g5 V4 mutation | `expansion.js:31`: inherited g5 brand size 72→64, line 70, h 75 | — | D(size override) |

(H = horizontal, V-UP/DN = vertical; it = italic; R/C = align right/center.
Rows 21–24 are expansion second-variant sets with computed geometry.)

## New types (exact)

```kotlin
enum class MarkOrientation { HORIZONTAL, VERTICAL_UP, VERTICAL_DOWN }
enum class MarkAlign { LEFT, CENTER, RIGHT }
enum class ProvenanceKind { BRAND, SENDER }
data class ProvenanceMark(kind, text, x, y, width, height, family, size, weight, italic, line, orientation, align)
// line is sealed: ceil(size*1.02) per mark(), except the frozen V4 editorial yield (brand 64 / line 70)
enum class DecorKind { CARRIER, ACID_RIBBON, PRINT_PLANE }
data class SceneDecor(kind, x, y, width, height)
enum class PhotoShape { RECT, ROUND, ELLIPSE }
enum class MusicMode { TEXT, THUMBNAIL, INLINE, TILE_ART, TILE_TEXT, SLEEVE, SLEEVE_EMPTY, SPINE, PULSE }
// expression V4 (additive, trailing defaults; V1/V2/V3 bytes untouched):
EXPRESSION_CONTRACT_V4 = 4
ResolvedExpression += provenance: List<ProvenanceMark>? = null
ResolvedExpression += decor: List<SceneDecor>? = null
Placement += shape: PhotoShape = RECT          // default keeps legacy bytes
MusicRegion.mode: V3 ∈ {text, thumbnail}; V4 ∈ all nine MusicMode values
```

## Seams for T151 (enumeration fills these)

- `BranchProvenance.marksFor(grammarId, branchId): List<ProvenanceMark>?` —
  static rows above are provided (`FrozenProvenance`, this task); dynamic
  (`D(...)`) boxes are computed by the enumerator from its layout variables
  and passed through this same type. T151 must not invent boxes: static
  entries are verbatim, dynamic entries use the frozen formulas.
- Sender text rule: `'From ' + verifiedHandle`; empty/blank handle ⇒ omit
  the sender mark (never fabricate). Brand text is always `'Remanence'`
  (validated literal).
- `CropCorrection(photoContentId, dxPx, dyPx)` data + `validateCropCorrection`
  (window stays inside the sealed crop window; solver re-check required
  before any prepare acceptance): defined, NOT wired into `prepare` (which
  keeps rejecting overrides) — T150's future UI consumes it after a solver
  contract exists.

## Renderer dispatch (implemented here)

Traced rule: NO sheet renderer exists in frozen code/CSS — plans carry
geometry, specimens carry pixels. Implemented mechanics are exact; anything
without a traced rule is flagged R below, never invented silently.
- text/inline/tile-text → title/artist text in region (existing primitive;
  frozen `sound()` geometry already distinguishes them).
- thumbnail/tile-art/sleeve/inline/pulse → cover + text composition when
  the cover verifies (frozen `musicNode` artwork gate); cover boxes are
  exact per `V5Decor.coverSpec` (40×40 default incl. thumbnails, 36 compact
  h<65, inline 39 / g23 37, g11 tile-art FILL×60 cover-fit, g24 tile-art
  50, g20 sleeve 120, g24 pulse 57) with the frozen band gaps/padding
  (`V5Decor.bandLayout`) shared by prepare (copy measurement) and Canvas
  (pixels) via `V5Decor.placeCover`. spine/text/
  tile-text/sleeve-empty → text-only bands (spine never carries an image
  in the frozen DOM). Missing bitmap on a cover-family mode keeps the
  existing fail-closed text behavior.
- Photos: rect fill; round → rounded-rect clip with radius .12 of each axis
  (exact port of `crop.js` `maskPoint`); ellipse → inscribed-ellipse clip.
- Decor: kind + region carried exactly (schema/codec/validated); fills use
  the documented kind→role map ONLY as explicitly provisional (see R1).
- Marks: per-mark font/size/weight/italic via FontRegistry (all five
  families bundled), exact rotation for vertical orientations,
  left/center/right alignment.

## Residuals (explicit, not silent)

- R1 decor fill colors: NO fill rule exists in frozen code/CSS (`plan.decor`
  carries kind+region only). Geometry is exact; fills follow a documented
  provisional kind→role map pending specimen grounding.
- R2 spine/pulse text orientation: spine reads VERTICALLY (`.g6
  .music-spine .music-copy{writing-mode:vertical-rl}`, label 21px nowrap;
  implemented as +90° rotation with swapped-axes measure + single-line
  gate); pulse stays horizontal (no vertical-music rule anywhere for
  pulse). Region-exact; orientation now CSS-traced, still unverified
  against specimens.
- R3 dynamic mark boxes: seam only — T151's pure plan port computes them
  (this interface does NOT port branch geometry; the static table above is
  reference transcription for T151, not implementation).
- R4 sender-text digest membership: SETTLED — mark texts join both digests
  by construction: GENEX04 carries provenance (texts) + shapes + decor so
  `candidateIdFor` (= `canonicalHash(expression)`) binds them, and BEXPR03
  carries the same slots so `expression_hash` binds them. Legacy V1/V2/V3
  bytes unchanged.
- R5 v4 `provenance == null` validates (documented "legacy grammars" case):
- R5 v4 `provenance == null` validates (documented "legacy grammars" case):
  accepted residual, not a requirement gap — every active frozen family
  carries brand/sender, so T151 MUST supply provenance for all 14 active
  `grammarId`s; null exists only so legacy-shaped expressions decode.
- R6 `ellipse` shape: schema + codec + renderer/CLI pass-through only (g19
  is spliced inactive — no active producer). No producer may emit it
  until a grammar lands it; admission carries it as data without a
  dedicated capability flag.
- R7 spine music band: text-only in the frozen DOM (`grammars.js`
  `musicNode` artwork gate lists thumbnail/inline/tile-art/sleeve/pulse —
  spine never carries an image even with artwork present). The renderer
  implements this gate exactly; the dispatch line above reading
  "spine → cover + text" is corrected here. T151 geometry unaffected.
- R8 V4 publish/route gates UNREADY (not safe): `CollageFrozenSelection`
  still refuses non-V3 and `CapsuleRoute` still routes non-V3 into the
  BER1 legacy arm. The V4 BER1 fallback is NOT safe — a v4 expression
  rendered there would misrender (new slots silently ignored). Exact
  owner patches: `tasks/T154/gate-patches.md` (S5 → T150, R7 →
  integration). V4 must not ship until those land; until then v4 stays
  fail-closed by those gates.
