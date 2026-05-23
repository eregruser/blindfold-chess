# Third-party notices

## Chess piece artwork — Cburnett set

The 12 chess piece vector drawables in `app/src/main/res/drawable/piece_*.xml` are
adapted from the **Cburnett** piece set by Colin M.L. Burnett, originally distributed
as SVGs by the Lichess project (https://github.com/lichess-org/lila under
`public/piece/cburnett/`). The pieces are licensed under
**Creative Commons Attribution-ShareAlike 3.0 Unported** (CC-BY-SA 3.0,
https://creativecommons.org/licenses/by-sa/3.0/).

Modifications: SVGs were converted to Android Vector Drawable XML format via
`scripts/svg_to_vector_drawable.py` (originally derived from `/tmp/svg2vd.py`).

Attribution: Colin M.L. Burnett, https://en.wikipedia.org/wiki/User:Cburnett.

## Stockfish chess engine

Source vendored as a git submodule under `app/src/main/cpp/stockfish` at the `sf_18`
release tag. Licensed under **GNU General Public License v3.0** (GPLv3,
https://www.gnu.org/licenses/gpl-3.0.html).

## Stockfish NNUE network files

Bundled in `app/src/main/assets/nn-*.nnue` (gitignored; fetched via
`scripts/fetch_nnue.sh`). Distributed by the Stockfish project; weights are part of
the engine release and inherit its GPLv3 license.

## Vosk speech recognition

`com.alphacephei:vosk-android:0.3.75` — **Apache License 2.0**
(https://github.com/alphacep/vosk-api/blob/master/COPYING).

## Vosk small English model

Bundled as `app/src/main/assets/vosk-model-small-en-us-0.15/` (gitignored; fetched via
`scripts/fetch_vosk.sh`). Distributed by Alpha Cephei under **Apache License 2.0**.

## chesslib (UCI → SAN conversion)

`com.github.bhlangonijr:chesslib:1.3.6` — used only for converting UCI move strings into
Standard Algebraic Notation for display. Published via JitPack (no Maven Central artifact).
**Apache License 2.0** (https://github.com/bhlangonijr/chesslib/blob/master/LICENSE).
