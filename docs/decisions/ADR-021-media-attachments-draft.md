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
- To change: the separate letter field and the "read the letter" button that
  were built on 9 November go. The note field becomes the one text field; the
  layouts take the first part and mark the rest. The mark is drawn by the
  layout, so it is a grammar change, and older receivers open the front
  without the mark or the back.
- Writing a letter stays behind `remanence.letter.enabled` on Android and
  `VITE_SENDS_LETTER` on the site until the back of the card is built.

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

- **Voice:** a new song-band mode with a waveform and the length. Older
  receivers reject an unknown band mode, so this needs an expression contract
  bump (V5) shipped to receivers before senders use it.
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
- The minimum size of the circle's square tile.
- Is 8000 bytes enough for the letter?
- Can a circle travel with a song? Both make sound when the card is open.
