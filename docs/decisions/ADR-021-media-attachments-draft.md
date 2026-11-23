# ADR-021 (draft): voice notes, video circles and the letter

Status: proposed, 9 November 2026. The owner set the direction on 9 November
(sections 1 and 2). The wire details are still to be agreed.

## 1. What a postcard carries

A postcard keeps 3 to 5 photos and the short note on the front. On top of that:

- **One audio slot:** a song **or** a voice note. Both sit in the song band
  the layouts already have.
- **A video circle takes the place of one photo.** Its tile is square and
  plainly visible. It does not have to be the largest or the central tile.
  A card with a circle has 2 to 4 photos plus the circle.
- **The letter is the back of the card** (section 2), not a separate thing
  beside it.

Proposed bounds, to confirm:

| Attachment | Length | Format | Size cap (ciphertext) |
| --- | --- | --- | --- |
| Voice | up to 60 s | AAC-LC in MP4 (`audio/mp4`), mono, 48 kbit/s | 1 MiB |
| Circle | up to 30 s | H.264 + AAC in MP4 (`video/mp4`), 480×480, about 1.5 Mbit/s | 8 MiB, the photo cap |
| Letter | 8000 UTF-8 bytes (about 4000 Cyrillic or 8000 Latin characters) | text | inside the content manifest |

The whole capsule stays under the existing 42 MiB cap
(`total_capsule_max_ciphertext_bytes`).

## 2. The letter: the back of the card

One text field. The front shows what fits in the layout's note region, with a
small "continued on the back" mark. Turning the card over shows the back with
the whole text in the handwriting font.

- Wire: `ContentManifest.letter = 8`, v2 only, 1..8000 UTF-8 bytes, not blank.
  It rides inside the AEAD-sealed manifest, so there is no new artifact kind
  and no server change. This part is built.
- Built (9 November, evening): the separate letter field and the "read the
  letter" button are gone. The note field is the one text field, up to the
  letter's 8000 bytes. A text up to 240 characters in up to 4 lines (and
  within the note's 1000 bytes) is just the note. A longer one is sealed whole
  as the letter, and the note (the front) is its start: whole sentences of the
  first paragraph up to 160 characters, or whole words and an ellipsis when
  the first sentence is longer. Site `splitLetter`, Android `LetterSplit`,
  the same tests on both.
- The mark and the back are drawn by the receiver, not sealed in the layout:
  whenever the manifest has a letter, a small turn arrow sits right after the
  note's last word, in the note's ink, and the whole note is the button
  (its accessible name is "continued on the back"). Tapping it turns the card
  over in place; the back is the paper the note is written on with the whole
  text in the note's face, and "the front ↺" turns it back. That paper is the
  last decor plane painted under the middle of the note (a mount, a printed
  panel) and the card's paper where there is none: one style writes dark ink
  on a light mount over dark paper, and on the card's paper its back read at
  1.3:1. Site `noteGround`, Android `noteGround`, the same tests; in the 21
  layouts the site's composer proof walks (three photos and a note), the back
  reads at 5.7:1 or better. No grammar or contract change, so older receivers
  open the front (ending in its first sentences) and, in builds that have it,
  the old letter button.
  "Save picture" still saves the front.
- Why an arrow and not the words (16 November): the first version wrote
  "continued on the back ↻" under the note. The layouts leave 7 to 17 px
  between their elements, so in 11 of the 14 layouts the words ran over the
  signature or a photo (checked on all 1599 reference plans with a note).
  The note region is the only place that is always free, so the mark lives
  inside it: 12 to 18 px, after the last word. When the last line is full,
  it takes the end of that line on a patch of the note's paper. Site
  `letterMarkPlace`, Android `letterMarkPlace`, the same tests.
- Writing a long text is on since 23 November (owner). `remanence.letter.enabled=false`
  on Android and `VITE_SENDS_LETTER=false` on the site turn it off again.

## 3. Media on the wire

### Artifact kind

`ArtifactKind.MEDIA = 4`, ordinal `0`, at most one per capsule. The publish
statement allows 5..8 artifacts (5..7 today). The AAD is the same
`ArtifactAad` with `artifact_kind = 4`. Voice and circle use the same kind;
the manifest says which one it is.

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
  string poster_content_id = 7; // circle only: the photo that holds its first frame
}
// ContentManifest
optional MediaEntry media = 9; // v2 only
```

A voice note excludes `track_snapshot` (field 7): one audio slot.

### The circle is a photo with a video behind it

The circle's tile holds an ordinary photo artifact: the first frame. The
layouts place it like any other photo, constrained to a square tile above a
minimum size. `poster_content_id` ties the video to that photo. So:

- older apps and the image export show the first frame as a photo;
- new apps play the video in that tile when the card is open;
- no new kind of tile in the expression is needed for the circle.

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

### Compatibility

Released Android builds refuse an incoming capsule whose blob list contains an
unknown kind (`IncomingCapsuleRepository`: "unsupported incoming blob kind").
The incoming row keeps `blobs` with the three old kinds and adds a separate
`media: [...]` that only new clients read. Old apps open the card and never
see the media.

## 4. Recording and playback

| | Android | Website |
| --- | --- | --- |
| Voice | `MediaRecorder`, AAC/MP4, 60 s cap | `MediaRecorder` with `audio/mp4` (Chrome, Safari) |
| Circle | CameraX `VideoCapture`, front camera, square crop, 30 s cap | `getUserMedia` + `MediaRecorder` `video/mp4` |
| Playback | decrypt into app-private cache, play, wipe on close | decrypt into a `blob:` URL, revoke on close |

Firefox records only WebM. The site converts a Firefox recording to MP4 in the
browser if that is fast and small enough; otherwise recording is not offered
in Firefox. Playing a received voice note or circle works there either way.

Encryption is the existing single-blob artifact cryptor. An 8 MiB blob fits in
memory on both clients.

## 5. In the layouts

- **Voice:** new song-band modes with a waveform and the length. Older
  receivers reject an unknown band mode, so the receiving side ships before
  senders can record.
  - Look (owner, 23 November; canvas "Голосовое на открытке"): the voice
    takes the song band's own three forms. Plate: the play button in the
    cover square, the waveform where the title was, a microphone and the
    length where the artist was. Caption: play circle, waveform and length on
    one line. Card (layout 6): the waveform along the spine, the length, the
    microphone and the play button at the foot. No word on the card (it would
    be in one language for every receiver); the button's accessible name is
    the receiver's own "listen to the voice note".
  - Wire: no contract bump after all. V4 gains the music modes
    `voice-plate`, `voice-caption` and `voice-card` (no artwork), with the
    same geometry as `plate`, `caption` and `card` — the way those three
    joined V4 on 9 November. Older receivers refuse a mode they do not know,
    exactly as they would refuse a V5, while the expression codec, its
    projection and the server stay as they are. Only cards with a voice note
    use these modes, so song cards keep opening in older builds. A voice mode
    needs `media` with kind VOICE and no `track_snapshot`; a song mode the
    reverse; a manifest with both is refused. A circle (kind 2) is refused as
    unsupported until it is drawn. Site: `wire.ts` `parseVoice`,
    `songBand.ts` `voiceBandLayout`, `prepare.ts` `voiceBandScene` (23
    November).
  - `MediaEntry` gains `bytes peaks = 8`: 48 amplitudes, 0..255, measured by
    the sender. Both platforms draw the same bars from them, and the band is
    whole before the voice blob arrives (the button waits for it).
  - Today's receivers refuse a music region without a track ("music region
    and track snapshot must match", site `prepare.ts`), so the receiving side
    ships in a release before any sender can record.
- **Circle:** a square-tile constraint per grammar. Grammars that cannot give
  one photo a square tile above the minimum are not offered for a card with a
  circle, the way grammars that need a song are not offered without one.
  Where too few qualify, add two or three layouts.
- **Letter:** the "continued on the back" mark in the note region.

The site and Android share one pool of layouts (done on 9 November), so each
of these is made once and checked on both against the same reference plans.

## 6. Export

"Save picture" stays as it is. A new "save postcard" gives a folder with the
sheet image, the letter as text, and the voice or circle file. For stories, a
later option is a video of the card with the circle playing in it.

## 7. Order of work

1. One pool of layouts on Android and the site (done).
2. The letter on the back of the card.
3. Server: the MEDIA kind behind a flag, the migration, the separate `media`
   field on incoming rows, tests. Deploy after the owner agrees.
4. The circle: recording on Android, then the site; the square-tile
   constraint in the layouts.
5. Voice in the audio slot, with the V5 band mode.
6. Folder export.

## 8. Still open

- 60 s voice and 30 s circle: right lengths?
- The minimum size of the circle's square tile. Measured on the 3083
  reference plans (16 November), taking in each plan the tile with the
  longest short side, the one a square would be cut from. Every kind of card
  (3 to 5 tiles, with or without a song or a note) is offered 7 to 13
  layouts today and keeps at least 6 of them at a 140 px minimum, at least 6
  at 160 px and at least 5 at 180 px. With no planner change at all, at
  least 3 layouts per kind of card already have a tile that is square within
  5% and 140 px or larger. Layout 11 already draws round tiles (70 to
  348 px).
- Is 8000 bytes enough for the letter?
- Can a circle travel with a song? Both make sound when the card is open.
