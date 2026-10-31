# ADR-020: First gift by phone, keyed by the card

Status: ACCEPTED by the owner (2026-10-31). Replaces the escrow, KMS and
five-character locator parts of `docs/pending-gifts-status.md`.

No cryptography specialist has reviewed this. It uses standard primitives only.
An independent audit is due before wide launch and before any public claim
about encryption of first gifts.

## Product

A person with no account receives a physical postcard. On its back the sender
wrote two things, and is told nothing about what they mean:

```
remanence.to
X4T-P9R-K12
```

1. The recipient opens the site and enters the code. The app is not required.
2. Google sign-in, passkey, handle.
3. The site says the card was sent to a number ending in `12` and asks for it.
4. SMS code. Then they scan the card and it opens.

Rules set by the owner:

- One writes a code, the other enters a code. No choices, no explanations.
- Phone only at launch. Email stays possible in the server model, with no UI.
- A full number must be typed before any SMS is sent.
- SMS cost (10 to 20 cents per person) is acquisition budget.
- First provider: BulkGate, behind an adapter.

## The code

Nine symbols: seven secret, then the last two digits of the phone number.

- Alphabet (28): `0123456789ACDEFHJKMNPQRTVWXY`. Crockford without `B G S Z`,
  which handwriting confuses with `8 6 5 2`.
- Input is case-insensitive and ignores spaces and hyphens. Handwritten
  look-alikes map on input: `O→0`, `I L→1`, `B→8`, `G→6`, `S→5`, `Z→2`, `U→V`.
- Secret entropy is 7 × log2(28) ≈ 33.6 bits.
- The two digits are public. They hold no security and never reach the server.
- The sender's device generates the code. The server never sees it.

The whole code stays on the device. It must never appear in a URL path or
query, because those reach server logs.

## The key

```
card_key  = Argon2id(secret7, salt=card_salt32, t=3, m=64 MiB, p=1, len=32)
ikm       = card_key || server_half32
kek       = HKDF-SHA256(ikm, info="remanence/card-seal/kek/v1" 00 H)
proof     = HKDF-SHA256(ikm, info="remanence/card-seal/claim-proof/v1" 00 H)
sealed    = AES-256-GCM(kek, nonce12, CardSealPlaintextV1, aad=CardSealAadV1)
verifier  = SHA-256(proof)
```

`H` is the SHA-256 of the sender-signed publish statement.

The sender's device generates `server_half32`, and sends the server
`PendingCardSealV1`: KDF parameters, `server_half32`, `sealed`, `verifier`. The
server encrypts that blob at rest under its own key, and releases it only after
SMS proof. It is deleted when the gift is claimed, cancelled or expires.

**The claim.** After SMS proof the device derives the key, checks it against the
key commitment `KC` that the sender signed, and seals the ordinary
`ClaimedEnvelopePlaintextV2` to its own account key. It uploads that envelope
with `proof`. The server compares `SHA-256(proof)` with `verifier`, signs the
claim binding, and stores the envelope.

From here everything is the existing path. Recipient admission still checks the
sender signature, the service signature, `D` and `KC`, and nothing about it
depends on how the envelope was made.

## Finding the gift

There is no lookup code. The server finds gifts by an HMAC tag of the verified
number, and only after SMS proof. Several gifts on one number are all returned;
the device tries the code on each, so the server does not learn which card this is.

## Threats

| Who | Has | Result | Test |
|---|---|---|---|
| Postal worker, or anyone holding the card | code | No server half: no SMS access | `test_code_without_proof_gets_nothing` |
| Holder of the phone, no card | server half | Cannot derive the key or pass `verifier`, so cannot claim or burn the gift | `test_proof_without_code_cannot_claim` |
| Server operator, or a full server compromise, before claim | server half, sealed key | Must brute-force the code, about 33.6 bits at one Argon2id per try | `test_server_never_receives_key_or_code` |
| Database leak alone | encrypted seal blob | Also needs the application key | `test_seal_at_rest_needs_the_app_key` |
| Anyone, after claim | nothing | Half and sealed key are deleted | `test_claim_deletes_the_seal` |
| Someone probing numbers | a number | Same response whether or not a gift exists; no SMS without a gift | `test_unknown_number_sends_nothing_and_looks_the_same` |
| SMS bombing and pumping | a gift, many accounts | Limits per number and per sender, a country allowlist, a daily ceiling | `test_limits_count_the_number_not_the_account` |
| Guessing the SMS code | an account | Five guesses per attempt, attempts limited per number | `test_guess_budget` |

**Known limits, stated plainly:**

- The operator can read one targeted gift by paying for about 2^33.6 Argon2id
  computations (at one second each, roughly 430 core-years, and far less on
  rented hardware built for this). Mass reading is out of reach. So "end-to-end
  encrypted" must not be claimed for unclaimed first gifts.
- The operator could also serve a malicious web page to a recipient who types
  the code. The Android app does not have that problem.
- A compromised sender device, or a recipient who photographs and shares both
  the card and their SMS, is out of scope.

Privacy: the server stores the phone number encrypted at rest plus an HMAC tag,
and deletes the number on claim, cancel or expiry. The privacy policy and Data
safety must be updated in the release that turns this on.

## Reused and replaced

Reused unchanged: the sender-signed statement, service-signed binding, claim
challenge and intent, recipient admission, feeds, blob storage, address registry.

Replaced: the KMS vault (`vault_name = card-seal-v1` under an application key),
the provider-owned SMS flow (the server generates the code, the provider only
carries it), public gift lookup, and code retirement.

The old escrow code stays until the new path is proven on a device, then gets
removed in its own commit. Production and beta refuse it at startup.
