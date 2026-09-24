# ADR-017: BER1 bounded editorial-rows generator branch (candidate)

Status: Proposed — layout implemented and wired end-to-end behind the additive
ContentManifest v2 expression artifact (ADR-018) in release preparation; NOT
owner-approved, NOT device-verified, NOT shipping

Date: 2026-09-24

## Context

Orchestrator selected BER1 for implementation exploration: one conservative
editorial-rows branch over the G1/G2 contracts (`GeneratorExpression`,
`GeneratorDiscovery`, `docs/hold/generator-boundary.md`, arch
`CANONICAL_PRODUCT_RENDERER_ARCHITECTURE.md` §1–§9). No production
grammar/branch id exists in the tree and Stage-10 research grammars must not
be promoted, so every BER1 identifier below is provisional. The Stage1-A
equal-band experiment (`GeneratorBoundedStack`: full-width bands, no note
band, no letterbox) stays a separate provisional probe; BER1 neither replaces
nor approves it. No schema, renderer, UI, or wire change is made here.

## Decision

BER1 is a photo+note-only branch: music reference present routes elsewhere
(`NotApplicable`); 3–5 ordered photos with an absent/empty/fitting note
resolves; anything else is typed `Incompatible` or `Failed` per G2.

Fixed frame (reference units, integers only): canvas 360×640, margin 16,
gap 12. Content width is 328; a 2-cell row splits to 158+12+158; a 1-cell
row spans 328. Row patterns: 3→2+1, 4→2+2, 5→2+2+1, in authored order, each
photo exactly once. Row heights split the photo area evenly with the
remainder (+1 unit) dealt to the earliest rows.

Note band: a non-empty note reserves a fixed bottom band with an explicit
`noteRegion = (16, 512, 328, 128)` recorded in the frozen model. Photo area
is then y 16..500 (height 484, 12-unit gap above the band). Absent (`null`)
or empty (`""`) note takes no band: photo area is y 16..624 (height 608).
Verified tilings (x, y, w, h per cell, authored order):

- No band — n=3: rows [298, 298]; n=4: rows [298, 298]; n=5: rows
  [195, 195, 194], last cell (16, 430, 328, 194).
- Band — n=3/n=4: rows [236, 236]; n=5: rows [154, 153, 153], cells
  (16, 16, 158, 154), (186, 16, 158, 154), (16, 182, 158, 153),
  (186, 182, 158, 153), (16, 347, 328, 153).

n=5 verdict: the geometry physically fits in both band states (exact tiling,
all heights positive). No hand-wave: the pinch point is recognizability in
the smallest cells, handled by the gate below, not by layout failure.

Fitting: each whole original is aspect-ratio-preserving letterboxed into its
cell (`scale = min(cw/w, ch/h)`, floor to integers), no crop, no mask.
Minimum gate: every letterboxed photo must satisfy `min(lw, lh) >= 48`
units, else the branch yields `Incompatible` for that input (arch §4/§6:
applicable but unresolvable stops, never silently adapts). Checked: 9:16,
16:9, 1:1, 4:3 all pass in every cell (smallest short side 86); 20:1
panorama / 1:20 tall fail (short side 7–16). The 48-unit value is
provisional and calibration-required (arch §7); goldens must pin
near-boundary cases before any approval.

Note fit is measured, never counted: a non-empty note must fit the
328×128 region with the frozen branch font/width/language at the reading
minimum, fully visible — truncation and height growth are forbidden (arch
§2/§5). An unfit note yields `Incompatible` (yields to a future alternate
provider). The v1 wire ceiling stays 1000 UTF-8 bytes and BER1 explicitly
need not fit every legal note; a long-note provider is future work, not a
reason to stretch this branch.

Versioning/determinism: provisional `grammarId "ber1-editorial-rows"`,
`grammarVersion 1`, `branchId "ber1-rows-3-5"`, `canvasVersion 1`,
font/palette versions strictly positive; candidate ids derive from the
resolved-expression canonical hash. Required future schema delta (Stage1-B,
specified here): the frozen model gains `expressionContractVersion`
(default 1, v2 constant 2), a nullable typed `noteRegion (x, y, w, h)`
(null iff note absent/empty), and a per-placement nullable typed
`contentRect` (letterbox inside the cell) — all trailing defaults so every
existing constructor keeps compiling. Version dispatch: the global
`GeneratorExpression.CONTRACT_VERSION` stays 1 (legacy G1 v1 bytes/hashes,
goldens, and G3 staging untouched); v2 bytes are framed by the distinct
magic `GENEX02` (so a v1 parser matching `GENEX01` rejects v2 bytes before
it could misread the version marker as `canvasVersion = 2`) + global 1 +
expressionContractVersion 2, then the v1 fields in v1 order with each
placement gaining a trailing contentRect and a trailing noteRegion after
`paletteVersion`. Encode and freeze are fail-closed: invalid expressions
(including v1 values carrying v2-only fields, and any unsupported version)
throw instead of aliasing a legal encoding. v1 decode rejects any non-null
new field; v2 validation requires `noteRegion` present iff the note is
non-empty and inside the canvas, every `contentRect` present/positive/inside
its placement, authored mapping unchanged, and any other version fails
closed. All bounds additions use Long arithmetic (Int sums can overflow
past the canvas check).

## Explicit non-decisions

- No v1 regression: the existing JPEG+note publish path, explicit v1 decode,
  and recipient behavior are unchanged; BER1 bytes never enter the v1 wire
  (delivery remains boundary gate 3).
- No renderer, crop/CV analysis, selection UI, delivery artifact, or M4.
- No approval of BER1, Stage1-A, or any grammar/branch id for production.
- No G1 overlap rule: cell/noteRegion disjointness is per-grammar policy
  (research layouts overlap deliberately); BER1 renderer goldens pin its own
  non-overlapping arrangement instead of a global constraint.
- Reading-minimum pixel value and font asset are set at the renderer slice
  (frozen and versioned there), not guessed here.

## Consequences

Privacy lifecycle: bytes never enter identity (G1/G3 split); previews live
only in the existing owned staging lifecycle with cleanup on
select/cancel/process-death; no plaintext generated previews in durable
storage. Goldens: bounded PNG per matrix cell below, rendered at the
390×845 shell check size. Objective test matrix (each cell asserts geometry
or typed outcome, plus golden where a candidate resolves):

- n ∈ {3, 4, 5} × aspects {9:16, 16:9, 1:1, 4:3, 20:1, 1:20} × note
  {absent, empty, short-fits, boundary-fit, over-branch-fit, 1000B wire-max}
  → candidate with exact cells above, or `Incompatible` (extreme aspect,
  over-branch-fit note), or `NotApplicable` (music), or `Failed`
  (G1-invalid input).
- Repeatability: identical input → byte-identical candidate and hash across
  runs and seeds; Incompatible/Failed reasons stable.
- v1 parity: BER1-absent recipients decode existing capsules unchanged;
  unknown BER1 versions fail closed before showing private content.
