#!/usr/bin/env python3
"""Download pinned Fabric mods from Modrinth into a mods directory for compatibility runs.

    python3 scripts/fetch-mods.py DEST lithium
    python3 scripts/fetch-mods.py DEST sodium iris
    python3 scripts/fetch-mods.py DEST --print-lock sodium   # resolve the newest release, print a lock entry

Versions are pinned in scripts/mods.lock.json (Modrinth version number + SHA-512 of the file). The
script fails when the downloaded file does not match the lock, so a compatibility run always
tests the same bytes. A mod that is not in the lock is resolved to the newest release for the
locked Minecraft version and its lock entry is printed; add it to the lock to pin it.
"""
from __future__ import annotations

import hashlib
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://api.modrinth.com/v2"
LOCK = Path(__file__).resolve().parent / "mods.lock.json"
USER_AGENT = "ultima-ci/1.0 (compatibility matrix; https://github.com/adaybekovt-boop/Ultima-)"


def get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def versions(slug: str, minecraft: str) -> list[dict]:
    query = urllib.parse.urlencode({
        "game_versions": json.dumps([minecraft]),
        "loaders": json.dumps(["fabric"]),
    })
    return json.loads(get(f"{API}/project/{slug}/version?{query}"))


def primary_file(version: dict) -> dict:
    files = version["files"]
    return next((f for f in files if f.get("primary")), files[0])


def resolve(slug: str, minecraft: str, pinned: dict | None) -> dict:
    candidates = versions(slug, minecraft)
    if pinned:
        for version in candidates:
            if version["version_number"] == pinned["version_number"]:
                return version
        raise SystemExit(f"{slug}: pinned version {pinned['version_number']} is not offered for Minecraft {minecraft}")
    releases = [v for v in candidates if v.get("version_type") == "release"] or candidates
    if not releases:
        raise SystemExit(f"{slug}: no Fabric build for Minecraft {minecraft}")
    return max(releases, key=lambda v: v["date_published"])


def main(argv: list[str]) -> int:
    print_lock = "--print-lock" in argv
    argv = [a for a in argv if a != "--print-lock"]
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    destination = Path(argv[0])
    lock = json.loads(LOCK.read_text()) if LOCK.exists() else {"minecraft": "26.2", "mods": {}}
    destination.mkdir(parents=True, exist_ok=True)
    unpinned = []
    for slug in argv[1:]:
        pinned = lock["mods"].get(slug)
        version = resolve(slug, lock["minecraft"], pinned)
        file = primary_file(version)
        expected = file["hashes"]["sha512"]
        if pinned and pinned["sha512"] != expected:
            raise SystemExit(f"{slug}: Modrinth serves a different file than the lock for {version['version_number']}")
        data = get(file["url"])
        actual = hashlib.sha512(data).hexdigest()
        if actual != expected:
            raise SystemExit(f"{slug}: downloaded file hash {actual[:16]}... does not match Modrinth's {expected[:16]}...")
        (destination / file["filename"]).write_bytes(data)
        entry = {"version_number": version["version_number"], "sha512": actual, "filename": file["filename"]}
        print(f"{slug}: {version['version_number']} -> {file['filename']}")
        if not pinned:
            unpinned.append((slug, entry))
    if unpinned or print_lock:
        print("\nUnpinned mods resolved to the newest release. Add to scripts/mods.lock.json:")
        for slug, entry in unpinned:
            print(json.dumps({slug: entry}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
