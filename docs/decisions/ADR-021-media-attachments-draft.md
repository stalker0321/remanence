# ADR-021 (draft): voice notes, video circles and the letter

Status: proposed, 9 November 2026. The owner set the direction on 9 November
(sections 1 and 2). The wire details are still to be agreed.

## 1. What a postcard carries

A postcard keeps 3 to 5 photos and the short note on the front. On top of that:

- **One audio slot:** a song **or** a voice note. Both sit in the song band
  the layouts already have.
- **A video square takes the place of one photo.** (It was a "circle" in the
  first drafts; on 23 November the owner chose a square, see section 3a.)
  Its tile is square and plainly visible. It does not have to be the largest
  or the central tile. A card with a video has 2 to 4 photos plus the video.
  A card may carry a voice note and a video together; only one of them sounds
  at a time.
- **The letter is the back of the card** (section 2), not a separate thing
  beside it.

Proposed bounds, to confirm:

| Attachment | Length | Format | Size cap (ciphertext) |
| --- | --- | --- | --- |
| Voice | up to 60 s | AAC-LC in MP4 (`audio/mp4`), mono, 48 kbit/s | 1 MiB |
| Video square | up to 30 s | H.264 + AAC in MP4 (`video/mp4`), square (480 on the site, the camera's SD square on Android, 240..1080), about 1.2 Mbit/s | 6 MiB |
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

### Server (deployed 23 November; `media_enabled` turned on the same day with the site's recording)

- `ArtifactKind.MEDIA = 4`, ordinal -1 (like the manifests), at most one;
  it sorts after the photos in the canonical artifact order. The publish
  statement takes 5..8 artifacts.
- Limits (`limits-v1.json`, all three platforms): `media_count_max = 1`,
  `encrypted_media_max_ciphertext_bytes = 1048609` (1 MiB of voice plus the
  AEAD overhead). The server sees only ciphertext, so this one cap covers
  every kind of media; a circle will raise it.
- `CapsuleBlobKind.MEDIA`: migration `0021_media_blob_kind` adds the enum
  label in its own commit (as 0005 did), widens the kind/ordinal check and
  adds `uq_capsule_blobs_one_media_per_capsule`. The downgrade refuses while
  media rows exist and drops and rebuilds the partial indexes around the type
  swap.
- `media_enabled` (default off): a draft that declares MEDIA is refused
  while it is off. Reading media never needs the flag.
- Old clients: released Android builds parse the incoming page with
  `ignoreUnknownKeys = false` and refuse the WHOLE page over one blob of an
  unknown kind (and the site checks that the statement's artifacts equal the
  listed blobs). So a card with media is listed only to a client that asks
  for it with `GET /v1/capsules/incoming?media=voice`; every other client
  never sees it, and it waits there for the update instead of breaking the
  inbox. A client that asks gets the MEDIA blob in the ordinary `blobs` list.
- Blob download and the synced acknowledgement take the MEDIA kind.
- Not yet: first gifts (`delivery_v2`, the pending services) and the
  sender's history; a pending send with media is not offered.

### 3a. The video square (23 November)

Look (owner, canvas "Видео на открытке", board Г): a square, the October idea
of page "Капсула из тайлов" — viewfinder corners, a red dot and the length in
the corner; the tile keeps the style's own photo shape (rect, round or
ellipse). While the card is open it loops without sound like a live photo; a
press plays it from the start with sound, a second press or the end returns
to the silent loop. With reduced motion (and on Android in battery saver) it
rests on the poster until pressed. The voice note and the video claim the
card's one sound from each other. Songs do not play in the app. Capture is
in the app only (front camera, mirrored; the back camera where there is no
front one), never a file from the gallery.

Wire (server 408e5686, web 389e50e0):

- A separate kind, `ArtifactKind.VIDEO = 5`, ordinal -1, at most one, after
  MEDIA in the canonical order, so a voice note and a video can travel
  together and the server can tell them apart: `video_count_max = 1`,
  `encrypted_video_max_ciphertext_bytes = 6291489` (6 MiB plus the AEAD
  overhead); the statement takes 5..9 artifacts. Migration
  `0022_video_blob_kind` as 0021. `video_enabled` gates drafts (on in prod
  since 23 November).
- `GET /v1/capsules/incoming?media=video` asks for voice notes and videos;
  `media=voice` (Android 54, 55) never sees a card with a video.
- `ContentManifest.video = 10`, a `MediaEntry` of kind VIDEO (renamed from
  CIRCLE): the VIDEO blob, `video/mp4`, 1..30000 ms, `width == height` in
  240..1080, and `poster_content_id`, the photo that holds the first frame.
  That photo must be square. Field 9 holds only a voice note.
- Blob id tag byte 0x04.
- The poster is seated only in a tile square within 5% and at least 140
  units on its short side: Android's matrix gives the poster no cost in any
  other tile, the site's matching does the same, and without analysis the
  site moves the poster to the largest such tile. On the reference photo
  sets 3 to 7 grammars stay offered for every kind of card.

## 4. Recording and playback

| | Android | Website |
| --- | --- | --- |
| Voice | `MediaRecorder`, AAC/MP4, 60 s cap | `MediaRecorder` with `audio/mp4` (Chrome, Safari) |
| Video square | CameraX `VideoCapture` + `Preview` in one `UseCaseGroup` with a 1:1 `ViewPort` (the recording itself comes out square, 720×720 on the emulator), SD quality, 1.2 Mbit/s, mirrored front camera, 29.75 s cap; the first frame via `MediaMetadataRetriever` as a 480 JPEG joins the photos as a file URI | `getUserMedia`, the middle square drawn mirrored at 480×480 on a canvas, `captureStream` + microphone into `MediaRecorder` H.264/AAC MP4; the first frame from the canvas joins the photos |
| Playback | decrypt into app-private cache, play, wipe on close | decrypt into a `blob:` URL, revoke on close |

Site, receiving (23 November): the incoming list asks with `media=voice` and
takes one signed MEDIA blob; opening the card downloads and decrypts the voice
note with the photos (it must be the one MEDIA blob the manifest names, and an
MP4), and the held sheet keeps it as an `audio/mp4` object URL revoked on
close. The whole band is the button ("listen to the voice note" / "pause the
voice note"); while it plays a pause glyph in the band's own form covers the
play button the card draws. `stageVoiceCheck.mjs` plays a real AAC tone in all
three forms. A first gift carries no media yet.

Android, receiving (23 November): the incoming list asks with `media=voice`;
`CapsuleArtifactKind.MEDIA` joins the layout (at most one, ordinal -1, after
the photos), the blob cache, the prefetch (after the photos) and the
presentation gate, which requires the manifest to name exactly the one signed
MEDIA binding. `PreparedPresentationMaterial.loadVoice` decrypts it (an MP4)
when the card opens; the sound stays in memory, the platform player reads it
through a `MediaDataSource` (nothing is written to disk) and it is zeroed when
the card closes. The band is one button with the same pause glyph as the site
and keeps playing while the card is turned to its letter.

Site, sending (23 November): the content step's "sound" section has two tabs,
song and voice. Voice: tap to record, tap to stop, at most a minute (the
recorder stops itself just before 60 s); then listen, record again or delete.
A recording replaces the chosen song and choosing a song drops the recording.
Only AAC in MP4 (`audio/mp4;codecs=mp4a.40.2`) is recorded, at 48 kbit/s, so
the receivers' players need nothing else; a browser without an AAC encoder
(Firefox, Chrome on Linux) gets a note that this browser cannot record. The
page decodes the recording once for its length and 48 peaks (the loudest
sample of each slice, the loudest slice at 255). The layouts are planned as
for a song without a cover; a plate becomes `voice-plate` and layout 6's spine
`voice-card` (`voice-caption` is not chosen by the sender yet). The freeze
binds the MP4's SHA-256, length and peaks; the sealer checks the bytes against
it, encrypts them as the MEDIA artifact with blob id tag byte 0x03 and writes
manifest field 9. `createSendCheck.mjs` sends a voice card through the real
App to a synthetic server and decrypts the MEDIA blob back to the recording.

Android, sending (23 November): the content step's sound section has the same
two tabs. `VoiceRecorder` records AAC/MP4 (mono, 44.1 kHz, 48 kbit/s) into the
app's cache with `RECORD_AUDIO` asked for on the first tap, stops itself just
before a minute, samples the level every 50 ms for the peaks (the loudest
level of each of 48 slices, the loudest slice at 255), reads the file into
memory and deletes it; `MediaMetadataRetriever` gives the length.
`RecordedVoice` holds it for the draft and is zeroed when replaced. A
recording drops the song and a song drops the recording; either drops the
offered and frozen layouts. `AllFamilyV4Adapter.seal(voice = true)` seals a
plate as `voice-plate` and the spine as `voice-card`, without a cover; the
layout host prepares them with the voice words, and the layout proofs carry
the sound's SHA-256. `CapsulePublisher` encrypts the MP4 as the one MEDIA
artifact (tag byte 0x03, ordinal -1) after the photos and writes manifest
field 9; the outbox takes `OutboxArtifactKind.MEDIA` and 5..8 blobs. A gift
to a phone number offers no recording and refuses one at publication.

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
    `songBand.ts` `voiceBandLayout`, `prepare.ts` `voiceBandScene`; Android:
    `ContentManifestCodec` `parseVoice`, `SongBand.voiceLayout` (the same
    pixels, `SongBandTest`), `CollageRenderer.prepare(voice = …)` and the
    bars and microphone in `CollageCanvas` (23 November). The proto gains
    `MediaEntry` and `ContentManifest.media = 9`.
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
