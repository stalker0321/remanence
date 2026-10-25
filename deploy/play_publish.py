#!/usr/bin/env python3
"""Upload a signed AAB to a Google Play testing track.

Usage:
  play_publish.py --check                       read-only: list tracks and their versions
  play_publish.py --upload app.aab [--track internal] [--notes "..."] [--draft]

Production is refused on purpose; promotion to production stays manual in
Play Console. Credentials: the service-account JSON at
~/.local/share/remanence-play-signing/play-service-account.json (never in git).
"""
import argparse
import os
import sys

import requests
from google.auth.transport.requests import Request
from google.oauth2 import service_account

PACKAGE = "dev.hryshyn.remanence"
KEY = os.path.expanduser("~/.local/share/remanence-play-signing/play-service-account.json")
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
TESTING_TRACKS = {"internal", "alpha", "beta"}  # alpha = closed testing, beta = open testing


def session():
    creds = service_account.Credentials.from_service_account_file(
        KEY, scopes=["https://www.googleapis.com/auth/androidpublisher"])
    creds.refresh(Request())
    s = requests.Session()
    s.headers["Authorization"] = f"Bearer {creds.token}"
    return s


def call(s, method, url, **kw):
    r = s.request(method, url, timeout=600, **kw)
    if r.status_code >= 400:
        sys.exit(f"{method} {url.split(PACKAGE)[-1]} -> {r.status_code}: {r.text[:500]}")
    return r.json() if r.content else {}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--check", action="store_true")
    p.add_argument("--upload")
    p.add_argument("--track", default="internal")
    p.add_argument("--notes", default="")
    p.add_argument("--draft", action="store_true", help="create the release as a draft instead of rolling it out to testers")
    a = p.parse_args()
    if a.track not in TESTING_TRACKS:
        sys.exit(f"refusing track {a.track!r}: only {sorted(TESTING_TRACKS)} are allowed here")

    s = session()
    edit = call(s, "POST", f"{API}/edits")["id"]
    committed = False
    try:
        for t in call(s, "GET", f"{API}/edits/{edit}/tracks").get("tracks", []):
            releases = [f"{r.get('status')}:{','.join(r.get('versionCodes', []))}" for r in t.get("releases", [])]
            print(f"track {t['track']}: {' '.join(releases) or '-'}")
        if not a.upload:
            return
        with open(a.upload, "rb") as f:
            bundle = call(s, "POST", f"{UPLOAD}/edits/{edit}/bundles?uploadType=media",
                          data=f, headers={"Content-Type": "application/octet-stream"})
        code = str(bundle["versionCode"])
        print(f"uploaded versionCode {code} ({bundle.get('sha256', '')[:12]})")
        release = {"versionCodes": [code], "status": "draft" if a.draft else "completed"}
        if a.notes:
            release["releaseNotes"] = [{"language": "en-US", "text": a.notes[:500]}]
        call(s, "PUT", f"{API}/edits/{edit}/tracks/{a.track}", json={"track": a.track, "releases": [release]})
        call(s, "POST", f"{API}/edits/{edit}:commit")
        committed = True
        print(f"released {code} to {a.track} ({release['status']})")
    finally:
        if not committed:
            requests.delete(f"{API}/edits/{edit}", headers=s.headers, timeout=60)


if __name__ == "__main__":
    main()
