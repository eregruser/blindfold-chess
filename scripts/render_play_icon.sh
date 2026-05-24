#!/usr/bin/env bash
# Render the Play Store hi-res app icon (512x512 PNG) from play/icon-source.svg.
# Re-run whenever the source SVG changes.
#
# Requires: rsvg-convert (librsvg) OR ImageMagick (`magick`).
set -euo pipefail

cd "$(dirname "$0")/.."

src=play/icon-source.svg
dst=play/icon-512.png

if [[ ! -f "$src" ]]; then
  echo "Missing $src" >&2
  exit 1
fi

if command -v rsvg-convert >/dev/null 2>&1; then
  rsvg-convert -w 512 -h 512 "$src" -o "$dst"
elif command -v magick >/dev/null 2>&1; then
  magick -background none -density 384 "$src" -resize 512x512 "$dst"
else
  echo "Need rsvg-convert or ImageMagick (magick) on PATH." >&2
  exit 1
fi

echo "Wrote $dst ($(stat -c%s "$dst") bytes)"
