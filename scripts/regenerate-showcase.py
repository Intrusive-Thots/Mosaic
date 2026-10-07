#!/usr/bin/env python3
"""Download the showcase sources and rebuild the docs/images comparisons.

Source pictures are copyrighted. They are written only to showcase-sources/,
which is gitignored. The committed pictures are the generated mosaics.
"""

import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "docs" / "showcase" / "sources.tsv"
OUTPUT = ROOT / "showcase-sources"
USER_AGENT = "MosaicShowcase/1.0 (non-commercial demonstration; local regeneration)"
TARGET_EDGE = 1920
TILE_EDGE = 512


def scaled(url: str, edge: int) -> str:
    base, _, query = url.partition("?")
    if "/scale-to-width-down/" not in base:
        base = f"{base}/scale-to-width-down/{edge}"
    return f"{base}?{query}" if query else base


def extension(url: str, content_type: str) -> str:
    kind = content_type.split(";", 1)[0].strip().lower()
    if kind == "image/png" or url.lower().split("?", 1)[0].endswith(".png"):
        return ".png"
    if kind == "image/webp":
        return ".webp"
    return ".jpg"


def download(url: str, destination: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        content_type = response.headers.get("Content-Type", "")
        if not content_type.startswith("image/"):
            raise RuntimeError(f"{url} returned {content_type or 'no content type'}")
        payload = response.read()
        if len(payload) < 2000:
            raise RuntimeError(f"{url} returned only {len(payload)} bytes")
        suffix = extension(url, content_type)
    destination.with_suffix(suffix).write_bytes(payload)


def read_sources() -> list[tuple[str, str, str]]:
    rows = []
    for line in SOURCES.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("role\t") or line.startswith("#"):
            continue
        role, title, url = line.split("\t")
        rows.append((role, title, url))
    targets = [row for row in rows if row[0] == "target"]
    tiles = [row for row in rows if row[0] == "tile"]
    if len(targets) != 1 or len(tiles) < 80:
        raise SystemExit(f"Expected 1 target and at least 80 tiles, found {len(targets)} and {len(tiles)}")
    return targets + tiles


def convert_webp(path: Path) -> None:
    if path.suffix.lower() != ".webp":
        return
    png = path.with_suffix(".png")
    try:
        from PIL import Image

        with Image.open(path) as image:
            image.save(png, "PNG")
    except ImportError:
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(path), str(png)], check=True)
    path.unlink()


def fetch_all(rows: list[tuple[str, str, str]]) -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for old in OUTPUT.glob("*"):
        if old.is_file() and not any(old.name.startswith(f"{index:03d}-") for index in range(len(rows))):
            old.unlink()
    for index, (role, _title, url) in enumerate(rows):
        edge = TARGET_EDGE if role == "target" else TILE_EDGE
        existing = list(OUTPUT.glob(f"{index:03d}-*"))
        if existing and existing[0].stat().st_size > 2000:
            print(f"keep {existing[0].name}")
            continue
        for attempt in existing:
            attempt.unlink()
        destination = OUTPUT / f"{index:03d}-part"
        last_error = "unknown error"
        for _attempt in range(3):
            try:
                download(scaled(url, edge), destination)
                saved = next(OUTPUT.glob(f"{index:03d}-part.*"))
                saved.rename(OUTPUT / f"{index:03d}-{role}{saved.suffix}")
                print(f"got {index:03d} {role}")
                last_error = ""
                break
            except Exception as error:  # noqa: BLE001 - the next retry reports the URL
                last_error = str(error)
        if last_error:
            raise SystemExit(f"Could not download {url}: {last_error}")
    for path in OUTPUT.glob("*.webp"):
        convert_webp(path)


def main() -> None:
    rows = read_sources()
    fetch_all(rows)
    gradle = ROOT / "gradlew"
    completed = subprocess.run(
        [str(gradle), ":engine:showcase", "--offline", "--no-daemon"],
        cwd=ROOT,
        check=False,
    )
    if completed.returncode != 0:
        raise SystemExit(completed.returncode)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
