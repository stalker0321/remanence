#!/usr/bin/env python3
"""Read-only ELF LOAD audit of every 64-bit native library in an APK or AAB.

Not a runtime test and not a replacement for APK zipalign / bundletool checks.
"""
import argparse
import json
import struct
import zipfile


def inspect_elf(data):
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise ValueError("not little-endian ELF64")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or count == 0: raise ValueError("invalid program headers")
    segments = []
    for index in range(count):
        kind, _, file_offset, virtual, _, _, _, alignment = struct.unpack_from("<IIQQQQQQ", data, offset + entry_size * index)
        if kind == 1:
            segments.append({"alignment": alignment, "compatible": alignment >= 16384 and (virtual - file_offset) % 16384 == 0})
    if not segments: raise ValueError("no LOAD segments")
    return segments


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact")
    args = parser.parse_args()
    libraries = []
    with zipfile.ZipFile(args.artifact) as archive:
        for name in archive.namelist():
            if name.endswith(".so") and any(f"/lib/{abi}/" in "/" + name for abi in ("arm64-v8a", "x86_64")):
                segments = inspect_elf(archive.read(name))
                libraries.append({"library": name, "segments": segments})
    ok = bool(libraries) and all(segment["compatible"] for row in libraries for segment in row["segments"])
    print(json.dumps({"status": "PASS" if ok else "FAIL", "check": "ELF_LOAD_16K_ONLY", "libraries": libraries}, indent=2))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
