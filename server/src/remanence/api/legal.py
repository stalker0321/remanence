"""Public beta policies and out-of-app deletion request, no account enumeration."""
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse, Response

router = APIRouter()
SUPPORT = "hryshyn.dev@gmail.com"
HEADERS = {"Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
           "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer"}


def page(title, body, nav="", headers=HEADERS):
    return HTMLResponse(f"""<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{title} — Remanence</title>
<style>body{{max-width:680px;margin:48px auto;padding:0 24px;background:#f8f5ee;color:#202824;font:18px/1.6 system-ui}}a{{color:#355b4b}}h1{{line-height:1.2}}nav{{margin-top:40px}}</style>
<main><h1>{title}</h1>{body}<p>Contact: <a href="mailto:{SUPPORT}">{SUPPORT}</a></p>
<nav><a href="{nav}privacy">Privacy</a> · <a href="{nav}terms">Terms</a> · <a href="{nav}delete-account">Delete account</a></nav></main></html>""", headers=headers)


@router.get("/legal/privacy", response_class=HTMLResponse)
def privacy():
    return page("Remanence privacy policy", """
<p>Beta policy · 12 October 2026. This policy covers the Remanence Android application and its supporting service.</p>
<h2>Account and delivery data</h2><p>We store your account ID, email, unique handle, Google account identifier when you use Google sign-in, public encryption/signing keys and session records. Legacy password accounts use password hashes, not readable passwords. The service stores encrypted capsules and recovery records plus routing metadata: sender, recipient, identifiers, sizes, timestamps and delivery state.</p>
<h2>End-to-end encryption</h2><p>Capsule photographs and notes are encrypted on devices. Remanence does not receive your plaintext private encryption keys or passkey PRF output. Google sign-in authenticates an account; it does not itself decrypt your memories. Your chosen passkey manager handles its own credential storage and synchronization under its policy. Losing all usable recovery methods may make encrypted memories inaccessible.</p>
<h2>Camera, files and sharing</h2><p>Camera access is used to photograph and recognize postcards. You select photographs through Android’s picker. Encrypted data may be cached on your device. Explicit image export creates a readable copy of the currently displayed capsule. Copies exported or received by other people cannot be remotely erased by Remanence.</p>
<h2>Other services</h2><p>Google processes sign-in and, if you choose its password manager, passkey operations. Music search uses MusicBrainz-derived metadata. Optional cover retrieval and opening a music service contact external providers, which may receive the request and your network address. This application does not include advertising or third-party analytics SDKs.</p>
<h2>Optional design and crop feedback</h2><p>Two independent Settings choices are off by default: design feedback and crop feedback. They are not required to use Remanence or accept its terms. Design feedback describes available and actually viewed designs, your selection, photo count, broad note-length category and whether music/cover art is present. Crop feedback describes the selected design, coarse photo aspect, coarse face boxes and an 8×8 importance grid, plus automatic and final crop rectangles, including unchanged crops. These signals help improve design selection and cropping.</p>
<p>Reports contain no photographs, readable notes, song identity, face identity, encryption keys, account/device/capsule/photo identifiers or exact timestamps. They are sent on explicit design selection or a successfully committed crop correction using a separate unauthenticated connection, without a persistent retry queue; failure does not block capsule creation. Nothing is collected before you opt in, and enabling a choice does not backfill earlier actions. Turning either choice off discards its pending data and cancels active local sends; it cannot recall a report already received.</p>
<p>The feedback store contains only a random storage key and the bounded report, without account links or timestamps. Ingestion is rate- and storage-limited and separated from normal request logging. This anonymous format is not a mathematical guarantee of anonymity: infrastructure operators may observe network activity, backups may retain copies, and unusual combinations can be recognizable. Because reports have no account link, we cannot reliably locate an individual’s received reports for account-based deletion. Contact us about retention; do not include personal information in feedback.</p>
<h2>Safety and retention</h2><p>Contact blocks and reports store account metadata and the selected report category, not decrypted capsule media. Service and network logs outside the isolated feedback path may include request/network metadata needed for operations and security. Account and encrypted content data remain until deleted under the service’s deletion or expiry processes. To request deletion, use the link below; we verify ownership before deleting anything. Backups and narrowly necessary security records may require separate expiry. The response to a deletion request will explain any retention that applies.</p>
<p><a href="delete-account">Request account and associated-data deletion</a>. We do not sell your personal data.</p>""")


@router.get("/legal/terms", response_class=HTMLResponse)
def terms():
    return page("Remanence terms of use", """
<p>Version 2026-10-11 · Closed beta. Remanence lets you attach encrypted memories to postcards and share them with chosen recipients.</p>
<p>You must be at least 18 years old to use Remanence.</p>
<p>Only upload or send material you have the right to share. Do not use Remanence for illegal content, child exploitation, threats, harassment, spam, fraud, or non-consensual intimate material. Respect other people’s privacy and intellectual property.</p>
<p>Recipients can block senders and report them in Settings → contact safety. Reports are reviewed by the operator, who may contact the parties and restrict or suspend an abusive account. Encrypted media are not automatically decrypted or uploaded with a report; any additional evidence you choose to provide is voluntary.</p>
<p>Beta software may have defects. Keep your original photographs and use a supported recovery method. We cannot restore encrypted content after all usable recovery credentials are lost. Do not rely on this beta as your only archive.</p>
<p>Blocking stops new deliveries but cannot erase copies already received or exported. Explicitly exporting an image makes it readable outside Remanence.</p>""")


@router.get("/legal/delete-account", response_class=HTMLResponse)
def delete_account():
    return page("Delete your Remanence account", f"""
<p>You can request deletion without installing or opening the app.</p>
<p><a href="mailto:{SUPPORT}?subject=Remanence%20account%20deletion">Email an account-deletion request</a> to <strong>{SUPPORT}</strong>. Include your Remanence handle and send it from the email associated with your account if possible. If you no longer have access to that email, explain that so we can discuss ownership verification.</p>
<p>Never send a password, passkey, recovery code or encryption key. We will verify that you own the account and confirm the processing and retention details before deletion.</p>
<p>The request covers your Remanence profile, login/session records, recovery records, public key records and associated data held by our service. We cannot remotely erase copies already received or exported by others, or passkeys stored in your independent password manager. Any necessary backup/security retention will be explained in our response.</p>""")


# Google Play review: the postcards to scan and the steps. Credentials stay in Play Console.
REVIEW = Path(__file__).parent / "review"
REVIEW_HEADERS = HEADERS | {"Content-Security-Policy": "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
                            "X-Robots-Tag": "noindex"}


@router.get("/review", response_class=HTMLResponse)
def review():
    card = lambda name, caption: f"""<figure style="margin:16px 0 28px"><a href="/review/{name}.jpg"><img src="/review/{name}.jpg" alt="{caption}"
style="width:100%;height:auto;display:block;box-shadow:0 2px 12px rgba(0,0,0,.18)"></a><figcaption style="font-size:15px">{caption} · <a href="/review/{name}.jpg" download>download</a></figcaption></figure>"""
    return page("Remanence review instructions", f"""
<p>Steps for reviewing the Remanence Android app. The test account email, password and recovery code are in the App access instructions in Play Console, not on this page.</p>
<p>You need Android 9 or newer with a screen lock. Remanence opens a postcard from a photo of its front, so show the postcards below on another screen (a laptop or monitor) or print them. Keep the whole picture in the camera frame and avoid glare.</p>
<h2>1. Sign in</h2>
<p>Tap <b>continue with Google</b> and sign in with the test account. When the app asks to confirm with your passkey, choose the Remanence passkey saved in Google Password Manager. If Google asks to unlock Password Manager on this device, enter the PIN from the Play Console instructions. If the passkey is not available, tap <b>use a recovery code instead</b> and enter the recovery code from Play Console.</p>
<h2>2. Open the postcard waiting for you</h2>
<p>A capsule has already been sent to the test account and attached to postcard A. Tap <b>open a postcard</b> and point the camera at postcard A. The capsule opens.</p>
{card("postcard-a", "Postcard A: open the capsule sent to the test account")}
<h2>3. Send a postcard</h2>
<p>Tap <b>make a postcard</b>, choose 3 to 5 photos, then pick a layout. Under <b>who’s it for?</b>, choose <b>send to my own account</b> or enter the handle <b>hryshyn.dev</b> (the developer). Tap <b>attach</b> and photograph postcard B as the paper postcard. If you sent it to your own account, tap <b>open a postcard</b> and scan postcard B to see it as the recipient.</p>
{card("postcard-b", "Postcard B: attach a new capsule to it")}
<h2>Safety</h2>
<p>Blocking and reporting a sender are in Settings → blocking and reports. A recipient only sees a capsule after photographing the matching postcard.</p>""", nav="/legal/", headers=REVIEW_HEADERS)


@router.get("/review/{name}.jpg")
def review_postcard(name: str):
    if name not in {"postcard-a", "postcard-b"}: raise HTTPException(404)
    return Response((REVIEW / f"{name}.jpg").read_bytes(), media_type="image/jpeg",
                    headers={"Cache-Control": "public, max-age=86400", "X-Content-Type-Options": "nosniff", "X-Robots-Tag": "noindex"})
