# Current milestone

`M2_FRONT_ONLY_ARCHITECTURE_CHECKPOINT`

ADR-012 and the canonical recognition/protocol/milestone contract are the
current documentation checkpoint, based on the `f721d1a` code baseline. This
docs-only checkpoint does not claim FRONT_ONLY production or test
implementation. Legacy two-sided v1 capsules remain strict/readable and must
not be rewritten or given a synthesized BACK.

The next approved implementation contract is the bounded sequence in
`docs/implementation-plan.md`: typed mode/version seam, inner-manifest and
`SenderIndexBundle` dual readers, outgoing/incoming wiring, FRONT_ONLY Create,
FRONT_ONLY Scan, then the explicit recipient chooser. Room v7 must be proven
insufficient before any schema migration is proposed. The P14 physical hardware
blocker remains: CameraX/OpenCV and two-device evidence cannot be claimed until
exercised on hardware.
