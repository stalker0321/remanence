# Ordered postcards: plan

Status: plan for discussion, 9 November 2026. Nothing is built. This is the
third direction in the roadmap: the person does not need a postcard of their
own. Remanence makes the front, prints it and mails it, and the memory is bound
to it.

## The flow

1. **Who it is for.** A handle, or a phone number for a first gift.
2. **The front.** Generated from a short description and a style preset ("a
   watercolour of a summer garden", "a night tram, risograph"), or chosen from
   a ready set. Four variants to pick from, and the person can regenerate.
3. **Inside.** Photos, words and a song, exactly as today. The layout is chosen
   the same way.
4. **Address and payment.** The postal address, then Stripe Checkout.
5. **We print and mail it.** A print partner prints the front and posts it.
   The recipient scans it as they would any postcard.

## Why it fits the product

- **There is no "photograph your postcard" step.** We hold the exact print
  file, so the recognition fingerprint is computed from it on the sender's
  device before sending. That removes the hardest step for new users.
- A generated front is rich in texture, which is good for recognition. Plain
  store-bought cards are the weak case today.
- The card still explains itself: the back carries `remanence.to` and, for a
  first gift, the code printed in the handwriting font, or left blank for the
  sender to write.

To check first: does a fingerprint made from the digital file match a camera
photo of the printed card under real light? This needs one test print from the
chosen partner, measured with the existing recognition code before anything
else is built.

## Pieces

| Piece | Choice to make | Notes |
| --- | --- | --- |
| Image generation | fal.ai, a FLUX-class model | Price per image to check (a few cents at last look). Text-to-image only: **the person's own photos never go to fal**, because that would break the end-to-end promise. Prompt and output moderation are on. Check the model's licence allows commercial printing. |
| Print and mail | Gelato or Prodigi (both have a postcard API and EU production) | A6/A5 at 300 dpi with 3 mm bleed. Price per card with postage to check; not looked up tonight. Choose by a test print. |
| Payment | Stripe Checkout and webhooks | Test mode works now. Live mode needs the company (KvK), like BulkGate. EU VAT through Stripe Tax or OSS. |
| Address | Server-side, encrypted with a key not kept in the database, deleted after delivery plus a short dispute window | This is new personal data and must go into the privacy policy. |
| Orders | Server table plus an admin page: paid, sent to print, shipped, delivered, refunded | Webhook-driven, idempotent. |

## What happens to the capsule

The capsule is created and sealed as today, with the fingerprint taken from the
print file. It stays **held** until the partner reports the card as shipped,
so the recipient cannot open it before the card exists. A cancelled or
refunded order cancels the capsule, the same way a sender cancels an
unclaimed first gift.

## Phases

1. **Prove recognition on a printed card.** Generate a front, order one test
   card, and match a camera photo of it against the fingerprint from the file.
   Without this, nothing else matters.
2. **Front generation in the app and on the site.** Prompt, presets and four
   variants, behind a flag, with a fake generator in tests and fal in
   production. Needs a fal API key.
3. **Orders without money.** Address, the order table and the admin page. The
   partner sandbox prints nothing.
4. **Stripe test mode, then live** once the KvK exists.
5. **Privacy policy and Play Data safety** for addresses and the generated
   images. Published in the same release.

## What can be built before any keys

- The print-file pipeline: the generated image to A6 at 300 dpi with bleed,
  and the fingerprint computed from the same image, in shared code.
- The order model, the state machine and the admin page against a fake partner
  and fake payments.
- The UI for generating and picking a front against a fake generator that
  returns the four images.

## Questions for the owner

- Generated fronts only, or also a ready catalogue of designs?
- Should the sender write the first-gift code by hand on a card that is mailed
  to them first, or should we print it?
- Which countries at launch? This decides the partner and the postage.
- Price per card and the margin?
