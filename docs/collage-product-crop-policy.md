# Product crop admission

The installed app uses `CropAdmissionPolicy.BALANCED` for both all-family
assignment and final per-slot crop solving, and for the legacy crop source.
Generic solver/source constructors default to `STRICT` so the frozen oracle
and parity fixtures remain explicitly available.

Balanced first runs the unchanged strict search. If it finds a safe base, its
strict envelope check and final crop remain identical. Only when strict search
has no safe base does balanced search the same analyzed windows without hard
vetoes for supporting object/person context, contour tips, protection-field,
edge/saliency, or half-image mass. These are aesthetic preferences: importance,
saliency, structure and object coverage still rank windows, with additional
edge/baseline/tip retention preferences for relaxed search. Extra 12% face
padding is optional in fallback; the complete actual detected face boxes must
still fit inside the crop and shape mask.

Both modes keep finite positive in-bounds crops, supported shape/slot geometry,
at least 25% source-window area, and the existing source-pixel resolution floor.
Missing or dimension-mismatched authoritative analysis does not gain a center
crop fallback. Non-finite analysis fails closed. Authored human/object role
rules and the assignment face-pixel floor remain unchanged. Finite context/tip
boxes extending beyond the image remain interpretable aesthetic regions for
the frozen rejection fixtures; actual face and crop bounds cannot do so.

The envelope shift is evaluated under the policy that admitted the base: strict
for a strict success, balanced for a relaxed success. If a shift fails, that
already-valid base remains. No window is fabricated and no post-hoc strict
check discards a valid relaxed base.

The historical `complete-safe-matching` assignment identifier is unchanged for
expression compatibility. In product balanced mode it does **not** claim
frozen aesthetic-safety parity. Crypto, recognition, recipients, source
identity, frozen grammar geometry, 12-total/2-per-grammar caps, styles, sealed
expression/digest, text-fit and receiver prepare checks are not changed.

Synthetic regression fixtures measure policy behavior, not calibration against
the owner's postcard dataset. Visual crop quality and real detector results
still need judging in the app; passing an analyzed synthetic fixture is not
physical or real-image acceptance.
