#!/usr/bin/env bash
#
# Download and extract the small English Vosk model into the engineassets
# asset-pack module where AssetManager / VoskRecognizer expects it. Run this
# once after fresh clone (the extracted model is gitignored — too large for git).

set -euo pipefail

ASSETS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/engineassets/src/main/assets"
MODEL_NAME="vosk-model-small-en-us-0.15"
ARCHIVE_URL="https://alphacephei.com/vosk/models/${MODEL_NAME}.zip"

mkdir -p "$ASSETS_DIR"

if [[ -d "$ASSETS_DIR/$MODEL_NAME" ]]; then
    echo "✓ $MODEL_NAME already present in assets/"
    exit 0
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

echo "→ Downloading ${MODEL_NAME}.zip (~40MB)..."
curl -fL --progress-bar -o "$tmpdir/model.zip" "$ARCHIVE_URL"

echo "→ Extracting..."
unzip -q "$tmpdir/model.zip" -d "$tmpdir"

mv "$tmpdir/$MODEL_NAME" "$ASSETS_DIR/"

# Vosk's StorageService.sync requires a 'uuid' file inside the model directory —
# it diffs this on each launch to decide whether to re-extract from APK. The
# upstream model archive doesn't ship one, so we add it ourselves.
echo "$MODEL_NAME" > "$ASSETS_DIR/$MODEL_NAME/uuid"

echo "✓ Model installed at $ASSETS_DIR/$MODEL_NAME"
echo "  ($(du -sh "$ASSETS_DIR/$MODEL_NAME" | cut -f1) extracted)"
