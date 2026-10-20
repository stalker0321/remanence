# In-memory presentation navigation

The existing admitted sheet now owns two same-document history layers:
Scan → sheet → original photo. Photo paging replaces the top entry. Native
Back, the photo's Back action, Escape and explicit sheet close share this
navigation. Photo Back keeps the sheet; sheet Back/close uses the existing
session lock and presentation-memory disposal, including URL revocation.

History contains only `__remanencePresentationV1` with a random visit marker,
depth and optional photo ordinal. It contains no account, credential, content
identifier, content bytes, object URL or recovery material. A history entry is
not admission. Forward may reopen a photo only while that same sheet is still
held. After the sheet is released, Forward into its spent layer returns to its
base route. Reload likewise discards old layer markers. Reopening creates a new
visit and truncates the old forward branch. Other history-state fields are
preserved.

Explicit sheet close releases memory immediately. History traversal is
asynchronous; duplicate close requests cannot schedule duplicate traversal.
An account replacement or external lock retires its layers without invoking
an old close callback against the replacement session. If history writes are
unavailable, explicit local close remains available.

The sheet is a labelled modal dialog. While it is visible the underlying App
is inert; focus enters the close action and Tab/Shift+Tab remain within the
sheet. The original-photo viewer retains its own focus containment and returns
to its photo trigger, including a viewer reopened by Forward. On sheet close,
focus returns to a still-available entry control, otherwise to the current
screen's first action (or its main region). No layout or animation redesign.

## Checks and limits

- `test/presentation-history.test.ts`: isolated history state/ownership and
  failure cases; no claim for browser behavior from these alone.
- `test/browser/presentationNavigationCheck.mjs`: real App with real
  SessionStore/PresentationMemory in pinned Chromium, 320×568 and 1280×720.
  Synthetic JPEG content is admitted through existing memory APIs; this is not
  a provider, cryptographic admission, camera-device or backend test.
- Existing viewer, export, composed receive/App, camera/receive lifecycle,
  create/send, static-shell and unchanged independent root probes remain gates.

Native Android Back, iOS/Safari/Firefox, screen-reader speech and physical
keyboard/device behavior are not verified by this Chromium fixture. No first-
open, crypto, recovery, persistence, send-attempt or server policy changed.

References consulted: [WHATWG History interface](https://html.spec.whatwg.org/multipage/nav-history-apis.html#the-history-interface)
for same-document entries/traversal, and [WAI-ARIA modal dialog pattern](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/)
for focus entry, containment and return.
