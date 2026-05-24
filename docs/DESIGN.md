# Blindfold Chess — Design

A native Android app for practicing blindfold chess hands-free. The user wears headphones,
plays full games against Stockfish offline, and interacts entirely by voice. Starting a
game takes full audio focus — any music or podcast in another app pauses for the duration
of the session, and most apps auto-resume when the game ends.

## Product summary

- **Primary use case.** Practicing blindfold chess during a commute or jog: phone in
  pocket, screen off, headphones on.
- **Mode.** Full games versus Stockfish at adjustable strength. Puzzle and replay modes
  are out of scope for v1.
- **Eyes-free, hands-free.** Every gameplay interaction is reachable from a headset tap
  plus speech. The on-screen Speak/Stop buttons cover users without a headset.
- **Offline-first.** Engine, ASR, and TTS all run on-device. No network calls during
  gameplay.

## Architectural decisions

| Decision | Choice | Rationale |
|---|---|---|
| Platform | Native Android, Kotlin + Jetpack Compose | Best access to `MediaSession`, audio-focus, and foreground-service APIs |
| Engine | Stockfish via JNI/NDK, vendored as a submodule | Strongest available open-source engine, fully offline |
| ASR | Vosk (Kaldi) offline, small English model | No network, no per-request cost, supports grammar-constrained recognition |
| TTS | Android built-in `TextToSpeech` | Offline on modern devices, zero dependency |
| Mic trigger | Headset single-tap; app owns the active `MediaSession` for the game's duration | Most modern BT headsets (Sony WH-1000XM5, AirPods, etc.) do not forward long-press as a key event — it is bound to the headset assistant at firmware level. Single-tap is forwarded as `KEYCODE_MEDIA_PLAY_PAUSE` (some headsets emit `KEYCODE_MEDIA_PLAY` or `KEYCODE_MEDIA_PAUSE` instead — the app accepts all three plus `KEYCODE_HEADSETHOOK`). |
| Audio model during game | Hold `AUDIOFOCUS_GAIN` + stream silent PCM at `USAGE_MEDIA` | Bluetooth/AVRCP routes media-key events to whichever app is currently streaming over A2DP, *not* to whichever app has `MediaSession.setActive(true)`. Without the silent keepalive, `MediaSessionService` delivers headset keys directly to the music app's `PendingIntent`, bypassing the MediaSession framework entirely. The cost is that other media pauses for the session. |
| Move confirmation | None — user says `"undo"` if wrong | Speed matters more than safety for blindfold practice |
| Move notation (input) | Constrained NATO grammar | NATO words are 5+ syllables and unambiguous on Vosk's small model; single-letter files like `"e"` are unreliable |
| Move notation (TTS output) | User-selectable: letter-by-letter, NATO, standard algebraic | Standard gives the most natural game flow; letter / NATO are useful in noisy environments |
| Persistence | Room database, auto-saved per move-pair | Crash-safe, supports resume on launch and per-game history |
| Min SDK | 26 (Android 8) | Required for `AudioFocusRequest`; covers ~all phones from the last several years |
| Native target | `arm64-v8a` only | Covers nearly every modern phone and halves APK size |
| Asset delivery | Play Asset Delivery install-time pack (`:engineassets`) for NNUE + Vosk model | The two assets together are ~176 MB; keeping them out of the base APK lets the upload stay under Play Store's 200 MB limit |

## Architecture

### `ChessGameService` is the heart

A foreground service of type `mediaPlayback|microphone` owns the lifetime of a game:

- **`MediaSessionCompat`** — receives media-button events from the input layer (wired
  headsets) and, via the audio-focus + silent-stream model, from Bluetooth AVRCP. Set
  active with `STATE_PLAYING`, fronted by a notification with `MediaStyle` linked to the
  session token. Media-button events are single-winner; another app cannot tee them out
  without `MEDIA_CONTENT_CONTROL`, which is restricted to system apps.
- **`SessionAudio`** — holds `AUDIOFOCUS_GAIN` and streams silent PCM at
  `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` via an `AudioTrack` writer thread. This is what
  claims AVRCP routing on Bluetooth so that headset taps reach the `MediaSession`
  callback. On transient focus loss (phone call, alarm) the keepalive pauses; on
  regain it resumes and the controller re-announces the engine's last move. On
  permanent loss (user opens another media app) the session ends cleanly.
- **`VoskRecognizer`** — Vosk model unpacked once from the install-time asset pack;
  the recognizer is constructed per listen window with a grammar string scoped to the
  current legal moves plus the fixed command vocabulary.
- **`StockfishEngine`** — Kotlin wrapper over `StockfishJni`. The JNI bridge embeds
  the engine on a worker thread and `dup2`s pipes onto `STDIN` / `STDOUT` so we drive
  `UCIEngine::loop()` from Kotlin via byte-level reads/writes. High-level methods
  (`handshake`, `setPosition`, `goMoveTime`, `perft`, `currentBoard`) use the
  `onSubscription` pattern to avoid races on fast responses.
- **`GameController`** — owns the game state machine
  (`Idle → Loading → WaitingForUser ⇄ Listening → Thinking → WaitingForUser | GameOver`),
  serializes engine I/O through a `Mutex`, dispatches voice commands, and persists
  every move-pair to Room.
- **`TtsManager`** — Android `TextToSpeech` plus an `UtteranceProgressListener` that
  exposes a `suspend speakAndWait` for sequenced announcements (used by the "read
  moves" command).
- **`GameRepository`** — Room DAO + repository pair. Active game is overwritten on
  every move-pair; on completion the row is finalized with a `GameResult` (UserWin,
  UserLoss, Draw, UserResigned, Abandoned).

`MainActivity` (Compose) is screen-on UI only: start / resume / stop, status, scrollable
move history, optional Speak button, plus navigation to Settings sub-screens. The
activity does not need to be alive during gameplay; the service is.

### Voice grammar is the accuracy lever

The most important runtime detail. After every move, `ChessGrammar.legal(legalMoves)`
generates a JSON phrase list scoped to the current position's legal moves plus the fixed
command vocabulary, and feeds it to Vosk. The recognizer can then only output legal
utterances, collapsing the search space from open vocabulary to ~30–40 possibilities.
Accuracy on the small model goes from "mostly mis-parses" to "near-perfect for
in-grammar utterances."

Move utterance shape (the field-tested variant the grammar emits):

```
move        := nato_file rank_word nato_file rank_word
nato_file   := "alpha" | "bravo" | "charlie" | "delta"
             | "echo" | "foxtrot" | "golf" | "hotel"
rank_word   := "one" | "two" | "three" | "four"
             | "five" | "six" | "seven" | "eight"
promotion   := move "promote" ("queen" | "rook" | "bishop" | "knight")
castle      := ("castle king side"  | "short castle")
             | ("castle queen side" | "long castle")
commands    := "repeat" | "undo" | "take back" | "resign" | "new game"
             | "whose turn" | "how many moves" | "list my pieces"
             | "describe board" | "read moves"
             | "what is on" nato_file rank_word
```

Specific shape decisions, each a fix for a measured failure mode of the small Vosk
model:

- **NATO files only.** Single-letter file utterances (`"e"`) are unreliable — the
  recognizer hears `"he"` or `"a"` and the grammar gives no acoustic anchor to prefer
  one over the other. NATO words have 5+ syllables and are unambiguous.
- **No `"to"` connector.** `"to"` and `"two"` are perfect English homophones; a
  grammar containing both `"echo two to echo four"` and `"echo two echo four"` makes
  the recognizer coin-flip on every utterance.
- **Compound castle words decomposed.** `"kingside"` / `"queenside"` appear OOV in
  `vosk-model-small-en-us-0.15`. The grammar uses `"king side"` / `"queen side"` plus
  `"short castle"` / `"long castle"` aliases.

Castle is parsed to a structured marker and resolved to UCI (`e1g1`, `e8g8`, etc.) by
`GameController` using the current `whoseTurn`, so it works regardless of which side
the user plays. Promotion is auto-queen for tap-to-move; voice can specify the piece
explicitly (`"promote queen | rook | bishop | knight"`).

### TTS announcements

Three notation modes selectable in Settings, all per-move:

- **Letter-by-letter** (default): `"e 7 e 5"`.
- **NATO**: `"echo seven echo five"`.
- **Standard algebraic**: `"e five"`, `"knight f three"`, `"castle kingside"`,
  `"e seven promotes to queen, check"`. Requires full game context for piece
  disambiguation and check / mate suffixes — `SanConverter` (via chesslib) builds the
  SAN string by replaying moves; `SanSpeech` phonemizes it.

Verbose mode (off by default) prepends the moving side and appends `"your turn."`:
`"black plays knight f three. your turn."`.

Special states announce as `"check"`, `"checkmate"`, `"stalemate"`, `"captures rook"`,
`"castle kingside"`, `"e eight promotes to queen"`. Short distinct `ToneGenerator`
earcons (`TONE_PROP_BEEP`) play before the `"listening"` confirmation so the user gets
sub-100 ms feedback on tap.

### Headset button behavior

During a game the app holds `AUDIOFOCUS_GAIN`, runs the silent A2DP keepalive, and has
an active `MediaSession` with `STATE_PLAYING`. The audio focus and keepalive together
are what claim AVRCP routing on Bluetooth — `MediaSession.setActive(true)` alone is
**not** sufficient on BT. On wired headsets, key events come in through the input layer
and reach MediaSession routing directly; the silent keepalive is incidental there.

Tap mapping (sent by most BT headsets including WH-1000XM5):

| Gesture | Key event | Action |
|---|---|---|
| Single tap | `KEYCODE_MEDIA_PLAY_PAUSE` / `_PLAY` / `_PAUSE` / `_HEADSETHOOK` | Open listen window |
| Double tap | `KEYCODE_MEDIA_NEXT` | Re-speak last engine move |
| Triple tap | `KEYCODE_MEDIA_PREVIOUS` | Cancel current listening window (silent abort) |
| Long-press | (typically not delivered to app — bound to headset assistant) | Ignored |

The app accepts all four single-tap keycodes because BT headsets toggle between
`KEYCODE_MEDIA_PLAY` and `KEYCODE_MEDIA_PAUSE` on successive taps depending on whether
they think audio is currently "playing" (the silent keepalive looks like playing to
them). Treating only `KEYCODE_MEDIA_PLAY_PAUSE` produces an "every other tap is ignored"
bug.

Don't try to forward single-tap events to the music app via `dispatchMediaKeyEvent` —
the required `MEDIA_CONTENT_CONTROL` permission is restricted to system apps.

A Settings → "Headphone button test" page logs every received `KeyEvent` (action,
keycode, repeat count, timestamp). This is essential because:

- Some TWS earbuds (AirPods, certain Pixel Buds) reroute media buttons to their own
  assistant and never forward to the app — there is no software fix.
- Different headsets send different keycodes for the same gesture (e.g. some
  double-tap as `NEXT`, others as repeated `PLAY_PAUSE`).
- The user needs to verify their specific hardware works before relying on it.

## Persistence

- Active game state is written to Room on every move-pair (crash-safe).
- Completed games go to a history list with timestamp, result, skill level, user color,
  and the full UCI move list.
- On launch, if an unfinished game exists, the main screen shows a "Resume" card. If
  declined or replaced by a fresh "Start game," the existing row is marked
  `Abandoned`.
- Settings → Game history surfaces all completed games, with a detail view that shows
  the move list in SAN.

## Build notes

### Stockfish JNI

- Source: official Stockfish repository, vendored as a git submodule under
  `app/src/main/cpp/stockfish` pinned to a release tag (currently `sf_18`).
- Built via NDK with CMake. `arm64-v8a` only.
- The bridge (`stockfish_bridge.cpp`) embeds the engine on a worker thread and
  `dup2`s pipes onto `STDIN` / `STDOUT` so `UCIEngine::loop()` is drivable from
  Kotlin via byte-level reads/writes. `StockfishJni.kt` is the low-level Kotlin
  surface; `StockfishEngine.kt` adds high-level UCI helpers
  (`handshake`, `setPosition`, `goMoveTime`, `perft`, `currentBoard`).
- Skill level maps to a user-facing scale ("beginner" / "club" / "strong" /
  "master") backed by Stockfish's `Skill Level` UCI option (0–20).

### NNUE network files

- Stockfish 18 evaluation requires two NNUE files: `EvalFile` (~104 MB, "big") and
  `EvalFileSmall` (~3.4 MB). The default filenames are defined in
  `stockfish/src/evaluate.h` and pinned per release.
- Shipped in the `:engineassets` Play Asset Delivery install-time pack
  (`engineassets/src/main/assets/nn-*.nnue`). At runtime, `EngineAssets.kt`
  extracts them to internal storage on first use and the engine loads via
  `setoption name EvalFile value <abs-path>`.
- Not checked into git (gitignored by `*.nnue` pattern). After a fresh clone:
  ```bash
  ./scripts/fetch_nnue.sh
  ```
  Downloads from `tests.stockfishchess.org` into the asset-pack module.
- Base APK size impact: zero — the pack ships separately as install-time delivery from
  Play Store. Dev / debug builds bundle the assets into the debug APK directly so
  Android Studio "Run" works as before.
- AGP `noCompress += "nnue"` avoids re-compressing already-quantized weights.

### Vosk

- `com.alphacephei:vosk-android:0.3.75` (16 KB page-size compatible).
- `vosk-model-small-en-us-0.15` lives in the same `:engineassets` install-time pack
  (`engineassets/src/main/assets/vosk-model-small-en-us-0.15/`), populated by
  `scripts/fetch_vosk.sh`. Vosk's `StorageService.unpack` requires a `uuid` file
  inside the model directory; the fetch script writes one after extraction.
- The recognizer is constructed per listen window with a fresh grammar string derived
  from the current legal moves.

### Permissions

- `RECORD_AUDIO`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- `FOREGROUND_SERVICE_MICROPHONE`
- `POST_NOTIFICATIONS`

The service's runtime `foregroundServiceType` adapts: `mediaPlayback` alone when only
the headphone-test page is open, `mediaPlayback|microphone` when a game is active and
`RECORD_AUDIO` is granted.

## Open risks and mitigations

| Risk | Mitigation |
|---|---|
| TWS earbuds eat media buttons entirely (route to own assistant) | Headphone test screen in Settings; on-screen Speak / Cancel buttons cover the no-headset case |
| Different headsets send different keycodes for the same gesture | Headphone test screen exposes raw events; `KEYCODE_MEDIA_PLAY_PAUSE` / `_PLAY` / `_PAUSE` / `_HEADSETHOOK` all map to single-tap |
| User expects music to keep playing in the background during a game | Onboarding / About explains: starting a game pauses other media for the session (required for Bluetooth headset routing) and most apps auto-resume on game end |
| BT auto-resume behavior varies by app (Spotify yes, YouTube no) | Document in onboarding; nothing the app can do beyond releasing focus cleanly |
| Vosk accuracy outdoors | Constrained grammar handles most of it; can add confidence-threshold fallbacks if field testing surfaces remaining issues |
| Battery drain from the always-on foreground service | Honest `mediaPlayback` type; expect ~10–15 %/hour, acceptable for a typical commute |
| OEM background-killing (Xiaomi, Huawei, OnePlus) | Onboarding step recommending "disable battery optimization for this app" |
| Distinguishing stalemate from checkmate without a rules layer | Currently both are recorded as `UserLoss`; Stockfish doesn't tell us which it is — would need to query check status or add a small rules layer |

## Out of scope for v1

- Puzzle / tactics mode
- Game replay or opening trainer
- Online play
- Multiple languages (English only)
- Wake word (deferred; headset button + on-screen Speak only)
- iOS
- Threefold / fifty-move draw detection (Stockfish handles internally but the app does
  not surface a separate draw outcome)
