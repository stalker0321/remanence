# Grammar-5 geometry plans (T233 prerequisite)

`planGrammar5.ts` is a faithful port of the Android `FrozenGrammarPlans`
grammar-5 subset (`baseEditorial` + `closing`/`bands`/`recipes` +
`sourceOrderAssignment` + the grammar-5 `noteState`/`applicability` rows).
Scope: 3–5 photos, music:none, note text present/absent. Output is
geometry plan descriptors only — tiles, text region, provenance inputs,
branch identity. It is NOT a frozen expression, NOT a crop solver, and
carries no analysis vectors.

Parity evidence: `web/test/plan-grammar5.test.ts` compares every
in-scope recorded plan (10 reference cases, 130+ plans) tile-by-tile
against `web/test/g5-plans-reference.txt`, the grammar-5 slice of
Android `v5_plans_reference.txt` recorded by the frozen `ref_plans.js`
runtime. That JS runtime is not real Android ciphertext and not a
shipped-vector claim.

Documented coverage gap: reference cases with music text/cover are
applicability-checked only. Their geometry needs closing-with-music
(which shifts every tile rect); the port refuses music input with a
typed `REJECTED` error instead of inventing it.

Connection note for the next package: a real preview/manual-crop/
freeze adapter must preserve aspect per placement and keep geometry
parity separate from the still-missing analysis (E07 envelope-50).
Do not advertise frozen-ready or all-14-family coverage from this
module.
