# ADR-021 (draft): voice notes, video circles and the letter

Status: proposed, 9 November 2026. Nothing below is decided until the owner
agrees. The letter (section 2) is already built behind flags.

## 1. What we are adding and the limit

A postcard keeps 3 to 5 photos and the short note drawn on the sheet. On top of
that it may carry:

- one **media attachment**: a song (exists), **or** a voice note, **or** a
  video circle. Never two at once. Carrying everything at once is too much
  content for one card.
- one **letter**: a long note read beside the sheet. It does not use the media
  slot.

Proposed bounds, to confirm:

| Attachment | Length | Format | Size cap (ciphertext) |
| --- | --- | --- | --- |
| Voice | up to 60 s | AAC-LC in MP4 (`audio/mp4`), mono, 48 kbit/s | 1 MiB |
| Circle | up to 30 s | H.264 + AAC in MP4 (`video/mp4`), square 480×480, about 1.5 Mbit/s | 8 MiB, the photo cap |
| Letter | 8000 UTF-8 bytes (about 4000 Cyrillic or 8000 Latin characters) | text | inside the content manifest |

The whole capsule stays under the existing 42 MiB cap
(`total_capsule_max_ciphertext_bytes`). When a circle is attached, the photo
budget shrinks by its size and the client says so before sending.

## 2. The letter (built)

- `ContentManifest.letter = 8`, v2 only, 1..8000 UTF-8 bytes, not blank. It
  rides inside the AEAD-sealed manifest, so there is no new artifact kind and
  no server change.
- Android: `CapsuleLetter` bounds, `ContentManifestCodec` writes and reads the
  letter, the publisher and the first-gift publisher carry it, and
  `CapsuleLetterAction` opens it full screen under the sheet. Writing one is
  behind `-Premanence.letter.enabled=true`.
- Web: the parser accepts field 8 always, so letters sent from Android open on
  the site. The encoder, the send path and a field on the content step sit
  behind `VITE_SENDS_LETTER=true`. Reading is on the live site.
- Older Android builds skip the unknown field and open the sheet without the
  letter. An older web build would refuse the manifest, but the site is always
  current.
- Open: should the sheet show a small "there is a letter" mark? That means a
  grammar change, like the media mark below.

## 3. Media on the wire

### Artifact kind

`ArtifactKind.MEDIA = 4`, ordinal `0`, at most one per capsule. The publish
statement allows 5..8 artifacts (it allows 5..7 today). The AAD is the same
`ArtifactAad` with `artifact_kind = 4`. Voice and circle use the same kind; the
manifest says which one it is.

### Manifest entry

```proto
message MediaEntry {
  enum Kind { KIND_UNSPECIFIED = 0; VOICE = 1; CIRCLE = 2; }
  Kind kind = 1;
  bytes blob_id = 2;        // UUID bytes, the MEDIA artifact
  string mime_type = 3;     // "audio/mp4" | "video/mp4"
  uint32 duration_ms = 4;   // 1..60000 voice, 1..30000 circle
  uint32 width = 5;         // circle only
  uint32 height = 6;        // circle only
}
// ContentManifest
optional MediaEntry media = 9; // v2 only; excludes track_snapshot (field 7)
```

A manifest that carries both `media` and `track_snapshot` is refused. That is
the one-slot rule.

### Server (not built)

All of these must change together:

- `CapsuleBlobKind.MEDIA`. The Postgres enum `capsule_blob_kind` is native, so
  it needs an Alembic migration (`ALTER TYPE ... ADD VALUE 'MEDIA'`).
- `publish_statement.py`: the kind maps, the 5..8 count, the per-kind size cap
  and the ordinal rule.
- `upload_preflight_service.py`, `schemas.py`, `api/capsules.py`: draft
  declarations and upload size checks.
- `incoming_query_service.py`, `recipient_blob_query_service.py` and
  `recipient_material_synced_service.py`: the receive side.
- First gifts: `delivery_v2.proto` artifact kinds and the pending services.

### Compatibility: the blocker to solve first

Released Android builds refuse an incoming capsule whose blob list contains an
unknown kind (`IncomingCapsuleRepository`: "unsupported incoming blob kind").
Depending on how the feed page is handled, that can break more than the one
card. So the server must not show a MEDIA blob to a client that does not
understand it. Options:

1. **Separate field (recommended).** The incoming row keeps `blobs` with the
   three old kinds and adds `media: [...]` that only new clients read. Old
   apps open the sheet and never see the media.
2. A client capability header (`X-Remanence-Features: media`) that decides
   whether MEDIA rows are listed at all.
3. A minimum app version enforced before media goes live.

## 4. Recording and playback

| | Android | Website |
| --- | --- | --- |
| Voice | `MediaRecorder`, AAC/MP4, hold or tap to record, 60 s cap | `MediaRecorder` with `audio/mp4` (Chrome 126+, Safari). Firefox records only WebM/Opus, so it says "record in Chrome, Safari or the app" |
| Circle | CameraX `VideoCapture` on the front camera, square crop, 30 s cap | `getUserMedia` + `MediaRecorder` `video/mp4`; same Firefox limit |
| Playback | decrypt into app-private cache, play, wipe on close | decrypt into a `blob:` URL, revoke on close |

Encryption is the existing single-blob artifact cryptor. An 8 MiB blob fits in
memory on both clients. Streaming encryption can wait.

## 5. Where media sits on the sheet

Two stages:

1. **Beside the sheet first**, like the letter: a "play voice note" action
   under the postcard and a round player over the sheet. No grammar change and
   no expression version change. The image export stays as it is.
2. **In the layout next.** Media takes the layouts' existing music slot (all
   14 grammars have one; 6, 11, 20 and 24 require it). The voice band shows a
   waveform and its length. The circle is a round frame with the first frame
   as a still, and it plays when the card is open. This needs new
   `musicRegion.mode` values, which older receivers reject. So it needs an
   expression contract bump (V5) and the same compatibility care as the wire
   change.

## 6. Export

As the owner asked on 2026-10-31: "save picture" stays as it is, and a new
"save postcard" gives a folder with the sheet image, the letter as text, and
the voice or circle file. For stories, a later option is a video of the card
with the circle playing in it.

## 7. Order of work

1. Server: the MEDIA kind behind a feature flag, the migration, the separate
   `media` field on incoming rows, and tests. Deploy only after the owner
   agrees.
2. Voice on Android, then on the web (record, seal, upload, open, play),
   beside the sheet.
3. Circle on Android, then on the web.
4. The media slot in the layouts (expression V5).
5. Folder export.

## 8. Questions for the owner

- One slot (song or voice or circle) or song plus voice?
- 60 s voice and 30 s circle: right lengths?
- Letter size: is 8000 bytes enough?
- Can a circle replace a photo, so a card has 2 photos + a circle?
- Firefox on the web: say "record in another browser", or skip recording there
  entirely?
