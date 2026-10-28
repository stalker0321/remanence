#!/usr/bin/env python3
"""Update the Google Play store listing: texts, phone screenshots, feature graphic.

Usage:
  play_listing.py --assets DIR [--apply]

DIR holds listing.md and <lang>/store/*.png plus <lang>/banner-desk.png for
lang in en, ru, uk. Without --apply it only prints what would change. Each
language's screenshots and feature graphic are replaced, not appended.
Credentials: the same service account as play_publish.py.
"""
import argparse
import re
import sys
from pathlib import Path

from play_publish import API, UPLOAD, call, session

LANGS = {"en": "en-US", "ru": "ru-RU", "uk": "uk"}
LIMITS = {"title": 30, "shortDescription": 80, "fullDescription": 4000}


def parse_listing(md: str) -> dict:
    """listing.md: '## <lang>' sections, each with three bold headers in order: title, short, full."""
    texts = {}
    for lang, body in re.findall(r"^## (\w+)\n(.*?)(?=^## |\Z)", md, flags=re.S | re.M):
        parts = re.split(r"^\*\*.+?\*\*\n", body, flags=re.M)[1:]
        if len(parts) != 3:
            sys.exit(f"{lang}: expected title, short and full description, got {len(parts)} parts")
        title, short, full = (p.strip() for p in parts)
        texts[lang] = {"title": title, "shortDescription": short, "fullDescription": full}
        for key, limit in LIMITS.items():
            if len(texts[lang][key]) > limit:
                sys.exit(f"{lang} {key}: {len(texts[lang][key])} > {limit} characters")
    return texts


def upload_image(s, edit, play_lang, kind, path):
    with open(path, "rb") as f:
        call(s, "POST", f"{UPLOAD}/edits/{edit}/listings/{play_lang}/{kind}?uploadType=media",
             data=f, headers={"Content-Type": "image/png"})


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--assets", required=True, type=Path)
    p.add_argument("--apply", action="store_true")
    a = p.parse_args()

    texts = parse_listing((a.assets / "listing.md").read_text())
    plan = {}
    for lang, play_lang in LANGS.items():
        shots = sorted((a.assets / lang / "store").glob("*.png"))
        banner = a.assets / lang / "banner-desk.png"
        if lang not in texts or not 2 <= len(shots) <= 8 or not banner.exists():
            sys.exit(f"{lang}: missing texts, 2-8 screenshots or banner-desk.png")
        plan[play_lang] = (texts[lang], shots, banner)
        t = texts[lang]
        print(f"{play_lang}: title {t['title']!r}, short {len(t['shortDescription'])}, "
              f"full {len(t['fullDescription'])} chars, {len(shots)} screenshots, {banner.name}")
    if not a.apply:
        print("dry run; pass --apply to update Play")
        return

    s = session()
    edit = call(s, "POST", f"{API}/edits")["id"]
    committed = False
    try:
        for play_lang, (text, shots, banner) in plan.items():
            call(s, "PUT", f"{API}/edits/{edit}/listings/{play_lang}", json={"language": play_lang, **text})
            for kind in ("phoneScreenshots", "featureGraphic"):
                call(s, "DELETE", f"{API}/edits/{edit}/listings/{play_lang}/{kind}")
            for shot in shots:
                upload_image(s, edit, play_lang, "phoneScreenshots", shot)
            upload_image(s, edit, play_lang, "featureGraphic", banner)
            print(f"{play_lang}: listing, {len(shots)} screenshots and feature graphic staged")
        call(s, "POST", f"{API}/edits/{edit}:commit")
        committed = True
        print("listing committed")
    finally:
        if not committed:
            s.delete(f"{API}/edits/{edit}", timeout=60)


if __name__ == "__main__":
    main()
