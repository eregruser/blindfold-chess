#!/usr/bin/env bash
# Render the Play Store feature graphic (1024x500 PNG) from
# play/feature-graphic-source.svg. Re-run whenever the source SVG changes.
#
# Requires: rsvg-convert (librsvg) OR ImageMagick (`magick`).
set -euo pipefail

cd "$(dirname "$0")/.."

src=play/feature-graphic-source.svg
dst=play/feature-graphic-1024x500.png

if [[ ! -f "$src" ]]; then
  echo "Missing $src" >&2
  exit 1
fi

if command -v rsvg-convert >/dev/null 2>&1; then
  rsvg-convert -w 1024 -h 500 "$src" -o "$dst"
elif command -v magick >/dev/null 2>&1; then
  magick -background none -density 192 "$src" -resize 1024x500 "$dst"
else
  echo "Need rsvg-convert or ImageMagick (magick) on PATH." >&2
  exit 1
fi

echo "Wrote $dst ($(stat -c%s "$dst") bytes)"
