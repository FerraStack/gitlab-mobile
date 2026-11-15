#!/usr/bin/env python3
"""Convert recorded Roborazzi README screenshots into docs/screenshots/{light,dark}/*.webp."""

from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src" / "test" / "screenshots"
DOCS = ROOT / "docs" / "screenshots"

SCREENS = {
    "home": "home.webp",
    "project": "project.webp",
    "readme": "readme.webp",
    "code": "code.webp",
    "file": "file.webp",
    "merge_requests": "merge-requests.webp",
    "merge_request": "merge-request.webp",
    "changes": "changes.webp",
    "pipeline": "pipeline.webp",
    "release": "release.webp",
}


def convert_to_webp(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    try:
        from PIL import Image  # type: ignore

        with Image.open(src) as image:
            image.save(dst, format="WEBP", quality=90, method=6)
        return
    except ImportError:
        pass

    cwebp = shutil.which("cwebp")
    if cwebp:
        subprocess.run([cwebp, "-quiet", "-q", "90", str(src), "-o", str(dst)], check=True)
        return

    raise RuntimeError("Install Pillow (`pip install pillow`) or `cwebp` to convert PNGs to WebP.")


def main() -> int:
    missing: list[str] = []
    converted = 0
    for theme in ("light", "dark"):
        for src_key, dst_name in SCREENS.items():
            png = SRC / f"readme_{src_key}_{theme}.png"
            if not png.is_file():
                missing.append(str(png.relative_to(ROOT)))
                continue
            convert_to_webp(png, DOCS / theme / dst_name)
            converted += 1

    if missing:
        print(
            "Missing recorded README screenshots:\n  " + "\n  ".join(missing),
            file=sys.stderr,
        )
        print(
            'Record them first with:\n  ./gradlew :app:recordRoborazziDebug --tests "com.cboyxd.gitlab.ReadmeScreenshotTest"',
            file=sys.stderr,
        )
        return 1

    print(f"Updated {converted} WebP screenshots in {DOCS.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
