#!/usr/bin/env bash
#
# Download the NNUE network files for Stockfish 18 (sf_18) into
# app/src/main/assets/ where the app's EngineAssets helper expects them.
#
# Run this once after fresh clone (the .nnue files are gitignored because
# they're ~108MB combined and don't belong in git history).

set -euo pipefail

ASSETS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/src/main/assets"
mkdir -p "$ASSETS_DIR"

# Filenames match EvalFileDefaultNameBig / EvalFileDefaultNameSmall in
# stockfish/src/evaluate.h for the sf_18 tag.
BIG="nn-c288c895ea92.nnue"
SMALL="nn-37f18f62d772.nnue"
BASE_URL="https://tests.stockfishchess.org/api/nn"

fetch() {
    local name="$1"
    local target="$ASSETS_DIR/$name"
    if [[ -s "$target" ]]; then
        echo "✓ $name already present ($(stat -c%s "$target" 2>/dev/null || stat -f%z "$target") bytes)"
        return
    fi
    echo "→ Downloading $name ..."
    curl -fL --progress-bar -o "$target" "$BASE_URL/$name"
    echo "✓ Saved $target ($(stat -c%s "$target" 2>/dev/null || stat -f%z "$target") bytes)"
}

fetch "$BIG"
fetch "$SMALL"

echo
echo "Done. Both networks are in $ASSETS_DIR/"
