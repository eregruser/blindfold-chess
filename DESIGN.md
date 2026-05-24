# Blindfold Chess — Android App

A native Android app for practicing blindfold chess hands-free while commuting or jogging. The user wears headphones, plays full games against Stockfish offline, and interacts entirely by voice. Starting a game takes full audio focus — any music or podcast in another app pauses for the duration of the session, and most apps auto-resume when the game ends.

This document is the design contract. Read it fully before scaffolding. When implementation choices conflict with this doc, ask before deviating.

---

## Product summary

- **Primary use case:** practicing blindfold chess during commute or jogging, phone in pocket, screen off, headphones on.
- **Mode:** full games versus Stockfish at adjustable strength. Puzzle and replay modes are explicitly out of scope for v1.
- **Eyes-free, hands-free:** every gameplay interaction is via headset button + voice. No screen interaction during a game.
- **Offline-first:** chess engine, ASR, and TTS all run on-device. No network calls required for gameplay.

## Core interaction loop

1. User starts a game (voice or screen-on setup), picks color and engine strength. The foreground service takes `AUDIOFOCUS_GAIN`, begins a silent A2DP keepalive stream, and sets its `MediaSession` active — together this routes headset buttons to the app instead of any music app.
2. Other media apps lose focus and pause. The keepalive stream is inaudible; the app is silent unless announcing a move or playing an earcon. (Required for Bluetooth — see Headset button behavior.)
3. User single-taps the headset button → earcon ("ready") → app listens.
4. User speaks a move in long algebraic (`"e2 to e4"`) or NATO (`"echo 2 echo 4"`), selectable in settings.
5. Recognizer validates against legal-move grammar:
   - **Legal:** play immediately, no confirmation.
   - **Illegal or unparseable:** TTS says "illegal" or "didn't catch that", reopen mic ~3 s.
6. Engine thinks → TTS announces engine's reply.
7. User can issue queries via other taps (see Headset button behavior): repeat last move, undo, etc. More complex queries (`what's on f3`, `list my pieces`, `describe board`, `whose turn`, `how many moves`) are spoken during a listening window after a single-tap, alongside moves.
8. When the user ends the game (voice "resign" or screen action), the service releases audio focus and the `MediaSession`. Headset buttons revert to controlling whatever music app the user reopens. Most major music/podcast apps (Spotify, Pocket Casts) auto-resume on focus regained; some (YouTube) do not.

## Architectural decisions (fixed)

| Decision | Choice | Rationale |
|---|---|---|
| Platform | Native Android, Kotlin + Jetpack Compose | Best access to MediaSession, AudioFocus, and foreground service APIs |
| Engine | Stockfish via JNI/NDK | Strongest engine, fastest, fully offline |
| ASR | Vosk (Kaldi) offline | No network, no per-request cost, supports constrained grammar |
| TTS | Android built-in `TextToSpeech` | Offline on modern devices, zero dependency |
| Mic trigger | Headset single-tap; app owns `MediaSession` for game duration | Most modern BT headsets (Sony WH-1000XM5, AirPods, etc.) do not forward long-press as a key event — long-press is bound to the assistant at firmware level. Single-tap is forwarded as `KEYCODE_MEDIA_PLAY_PAUSE` (some headsets emit `KEYCODE_MEDIA_PLAY` instead — app accepts both). |
| Audio model during game | Hold `AUDIOFOCUS_GAIN` + stream silent PCM at `USAGE_MEDIA` for the game duration | Bluetooth/AVRCP routes media-key events to the app currently streaming over A2DP, not to whichever app has `MediaSession.setActive(true)`. Verified in Phase 1: without the silent keepalive, `MediaSessionService` delivers the key straight to the music app's `PendingIntent` and bypasses the MediaSession framework entirely. Cost: other media pauses for the session. |
| Move confirmation | None — user says "undo" if wrong | Faster flow; speed > safety per user preference |
| Move notation | Long algebraic default, NATO phonetic toggle | Most robust for constrained-grammar ASR |
| TTS verbosity | Minimal by default, "describe board" on demand | Configurable in settings |
| Persistence | Every game saved to history list, PGN export | Allows review after sessions |
| Min SDK | 26 (Android 8) | Required for `AudioFocusRequest`; broad device coverage |

## Architecture

### Foreground service is the heart

`ChessGameService` (foreground service, `mediaPlayback` type) owns:
- `MediaSessionCompat` — receives media-button events from the input layer (wired headsets) and, when combined with `SessionAudio` below, from Bluetooth/AVRCP. `setActive(true)` + `STATE_PLAYING` + a notification with `MediaStyle` linked to the session token. Media button events are single-winner; another app cannot tee them out without `MEDIA_CONTENT_CONTROL`, which is restricted to system apps.
- `SessionAudio` — for the duration of a game holds `AUDIOFOCUS_GAIN` and streams silent PCM at `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC` via an `AudioTrack` writer thread. This is what claims AVRCP routing on Bluetooth so headset buttons reach our `MediaSession` callback. Other media apps pause for the duration. Released on game end.
- `VoskRecognizer` wrapper — model loaded once at service start.
- `MoveParser` — recognized text → UCI move string.
- `StockfishJni` — native engine instance, UCI command pipe.
- `TtsManager` — Android `TextToSpeech` + small phrase cache + earcons.
- `GameRepository` — Room database, active game persisted every move.

`MainActivity` (Jetpack Compose) is screen-on UI only: start/resume, history list, settings, optional 2D board view for debugging. Activity does not need to be alive during gameplay; the service is.

### Voice grammar is the accuracy lever

The single most important implementation detail. After every move, regenerate a JSGF grammar from the current legal moves + fixed command vocabulary, and feed it to Vosk. The recognizer can then only output legal utterances. This collapses the search space from open-vocabulary to ~30–40 possibilities and produces dramatic accuracy gains over open ASR.

Example grammar shape (long algebraic):

```
move := square "to" square (promotion)?
square := file digit
file := "a" | "b" | ... | "h"
digit := "one" | "two" | ... | "eight"
promotion := "promote to" ("queen" | "rook" | "bishop" | "knight")
commands := "repeat" | "undo" | "resign" | "describe board"
          | "what's on" square | "list my pieces" | "whose turn"
          | "how many moves" | "take back"
```

NATO mode swaps `file` for `"alpha" | "bravo" | ... | "hotel"`. That is the only change.

Special move encodings:
- Castling: `"castle kingside"` / `"castle queenside"` → `O-O` / `O-O-O`.
- Promotion: `"e7 to e8 promote to queen"` → `e7e8q`.
- En passant: handled automatically by legal-move generation; user speaks the move like any other.

### TTS announcements

- **Minimal (default):** `"e7 e5"` or `"echo 7 echo 5"` depending on notation mode.
- **Verbose (toggleable):** `"Black plays e7 to e5. Your turn."`
- **Special states:** announce `"check"`, `"checkmate"`, `"stalemate"`, `"captures rook"`, castling as `"castle kingside / queenside"`, promotions as `"e8 promotes to queen"`.
- **Earcons:** short distinct tones for "listening started" and "listening ended". Faster feedback than speech.

### Headset button behavior

**Model:** during a game the app holds `AUDIOFOCUS_GAIN`, runs a silent A2DP keepalive stream, and has an active `MediaSession` with `STATE_PLAYING`. The audio focus + keepalive part is what claims AVRCP routing on Bluetooth — `MediaSession.setActive(true)` alone is **not** sufficient on BT.

Phase 1 finding (with concrete logcat evidence): when our session was active and YouTube was playing over A2DP, `MediaSessionService` delivered the key event directly to `com.google.android.youtube`'s media-button `PendingIntent` via `PendingIntentHolder` / `tempAllowlistTargetPkgIfPossible`, completely bypassing the MediaSession framework. The fix is to become the active media source on the BT stack: take full audio focus and stream silent PCM at `USAGE_MEDIA`. After that, AVRCP routes to us and the `MediaSession.Callback.onMediaButtonEvent` fires normally.

On wired headsets, key events come in through the input layer and reach `MediaSession` routing directly — the silent keepalive is incidental there. Either way, while the session is active any other media app is paused. On game end, focus is released; most music/podcast apps auto-resume.

**Tap mapping during a game (sent by most BT headsets including Sony WH-1000XM5):**

| Gesture | Key event | Action |
|---|---|---|
| Single tap | `KEYCODE_MEDIA_PLAY_PAUSE` | Start listening for a move or command |
| Double tap | `KEYCODE_MEDIA_NEXT` | Repeat last engine move |
| Triple tap | `KEYCODE_MEDIA_PREVIOUS` | Cancel current listening window (silent abort) |
| Long-press | (typically not delivered to app — bound to headset assistant) | Ignored; do not depend on it |

**Implementation notes:**
- Do **not** attempt to forward single-tap events to the music app via `dispatchMediaKeyEvent` or similar. `MEDIA_CONTENT_CONTROL` is required and is restricted to system apps. Trying to coexist with the music app's button semantics is a dead end.
- Detecting double-tap vs single-tap by `KEYCODE_MEDIA_PLAY_PAUSE` timing is **not needed** because most BT headsets send distinct keycodes per gesture (single → `PLAY_PAUSE` or `PLAY`, double → `NEXT`, triple → `PREVIOUS`). Use the keycodes, not tap-timing heuristics.
- Accept `KEYCODE_MEDIA_PLAY_PAUSE` (85), `KEYCODE_MEDIA_PLAY` (126), and `KEYCODE_MEDIA_PAUSE` (127) as "single tap" — observed in Phase 1 that BT headsets toggle between `PLAY` and `PAUSE` on successive taps depending on whether they think audio is currently playing. Treating only `PLAY` produces an "every other tap is ignored" bug. Also accept `KEYCODE_HEADSETHOOK` for compatibility with older wired sets.
- If a wired headset with only one button is connected, it will send `PLAY_PAUSE` for every press regardless of count. For v1, document that BT headsets with distinct gesture mapping are recommended; wired single-button support can be a later enhancement using tap-timing.

A settings screen must include a "headphone button test" page that logs every received `KeyEvent` (action, keycode, timestamp). This is essential because:
- Some TWS earbuds (AirPods, certain Pixel Buds) reroute media buttons to their own assistant and never forward to the app — there is no software fix.
- Different headsets send different keycodes for the same gesture (e.g. some double-tap as `NEXT`, others as repeated `PLAY_PAUSE`).
- The user needs to verify their specific hardware works before starting a game in the field.

## Persistence

- Active game state is written to Room on every move (crash-safe).
- Completed games go to a history list with timestamp, result, opening (if recognizable), and full PGN.
- Export to PGN file via Storage Access Framework / share sheet.
- On launch, if an unfinished game exists, offer "resume game?" — via voice if the user opens the app via assistant intent.

## Build notes

### Stockfish JNI
- Source: official Stockfish repository, vendored as a git submodule under
  `app/src/main/cpp/stockfish` pinned to a release tag (currently `sf_18`).
- Build via NDK with CMake. `arm64-v8a` only for v1 (covers nearly every modern phone
  and halves APK size); add `armeabi-v7a` later if a tester actually needs it.
- The bridge (`stockfish_bridge.cpp`) embeds the engine on a worker thread and
  `dup2`'s pipes onto `STDIN`/`STDOUT` so we can drive `UCIEngine::loop()` from
  Kotlin via byte-level reads/writes. `StockfishJni.kt` is the low-level Kotlin
  surface; `StockfishEngine.kt` provides high-level UCI methods (`handshake`,
  `setPosition`, `goMoveTime`, ...) with `onSubscription`-based response awaits.
- Skill level mapped to user-facing scale: "beginner" / "club" / "strong" /
  "master", or numeric 1–20 (Stockfish `Skill Level` UCI option).

### NNUE network files
- Stockfish 18 evaluation requires two NNUE files: `EvalFile` (~104 MB, "big") and
  `EvalFileSmall` (~3.4 MB). The default filenames are defined in
  `stockfish/src/evaluate.h` and pinned per release.
- Shipped in the `:engineassets` Play Asset Delivery install-time pack
  (`engineassets/src/main/assets/nn-*.nnue`). At runtime, `EngineAssets.kt`
  extracts them to internal storage on first use; the engine loads via
  `setoption name EvalFile value <abs-path>`.
- **Not** checked into git (gitignored by `*.nnue` pattern) — they're large
  binaries fetched separately. After a fresh clone:
  ```bash
  ./scripts/fetch_nnue.sh
  ```
  This downloads from `tests.stockfishchess.org` into the asset pack module.
- Base APK size impact: zero — the pack ships separately as install-time
  delivery from Play Store. Dev/debug builds bundle the assets into the debug
  APK directly so Android Studio "Run" works as before.
- AGP `noCompress += "nnue"` to avoid re-compressing already-quantized weights.

### Vosk
- Use the Alpha Cephei Android demo as starting reference.
- Small English model (~50 MB) bundled as asset or downloaded on first launch (offer both; bundled is simpler).
- Recognizer instance is created per-game; grammar swapped per-move.

### Permissions
- `RECORD_AUDIO`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- `POST_NOTIFICATIONS`

## Implementation phases

Build these in order. Do not skip ahead — each phase de-risks the next.

### Phase 1 — Skeleton (highest risk, smallest scope) — DONE

Implemented:
- Kotlin + Compose project, min SDK 26, compile/target SDK 35.
- Foreground service `ChessGameService` with `mediaPlayback` type and `MediaStyle` notification linked to the session token.
- `MediaSessionCompat` set active when the user starts a mock game (or opens the headphone-test page), released on stop.
- `SessionAudio`: holds `AUDIOFOCUS_GAIN` and runs a silent A2DP `AudioTrack` writer thread for the duration of the session.
- `TtsManager`: speaks short phrases through the session's audio focus (no per-utterance focus request — session-level focus covers it).
- Keycode mapping (on `ACTION_DOWN`, `repeat == 0`): `PLAY_PAUSE` / `PLAY` / `HEADSETHOOK` → TTS "listening"; `NEXT` → "repeat"; `PREVIOUS` → "cancel".
- Settings → "Headphone button test" — scrollable list of every received `KeyEvent` (action, keycode, repeat count, timestamp). Auto-acquires the same session focus while open so events actually reach us over BT.

**Findings that change later phases:**
1. **AVRCP bypasses MediaSession on Bluetooth.** `setActive(true)` is not enough — without holding `AUDIOFOCUS_GAIN` + streaming silent PCM, BT delivers keys straight to the currently-playing music app's `PendingIntent`. Implications recorded in the architectural-decisions table and Headset button behavior section above.
2. **The "music keeps playing in the background" UX from earlier drafts is gone.** Other media pauses for the whole session. Auto-resume on game end is per-app (Spotify yes, YouTube no).
3. **Keycode variation:** the target test headset emitted `KEYCODE_MEDIA_PLAY` (126) and `KEYCODE_MEDIA_PAUSE` (127) on alternating taps (toggling based on its internal play/pause state, since our silent keepalive looks like "playing"), not the expected `KEYCODE_MEDIA_PLAY_PAUSE` (85). App accepts all three; the headphone-test page is the recommended way to verify any new hardware before relying on it.
4. **TTS audio focus is session-level, not per-utterance.** The earlier Phase 1 prompt asked for `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` per `speak()`; that was the right model only if we were trying to coexist with background music, which (per finding 1) we can't.

### Phase 2 — Engine integration
- Stockfish built via NDK, JNI wrapper, UCI pipe.
- Simple on-screen text input to play a full game versus the engine.
- Engine strength selector.

**Exit criterion:** can play a complete legal game against Stockfish via text input.

### Phase 3 — Voice input
- Vosk integration with static full-board grammar.
- Long algebraic parser → UCI.
- Play full game eyes-free indoors.

**Exit criterion:** complete game played against Stockfish using only voice, screen off, in a quiet room.

### Phase 4 — Voice polish
- Dynamic legal-move grammar regenerated each move.
- NATO phonetic mode.
- Illegal move handling, query commands (`what's on`, `list my pieces`, `repeat`, etc.).

### Phase 5 — Audio focus lifecycle and interruptions — DONE (basics)

Phase 1.5 already covered the steady-state focus model (full `AUDIOFOCUS_GAIN` + silent A2DP keepalive, with `LOSS_TRANSIENT` pausing the keepalive and `LOSS` permanently ending the session). This phase finished the transitions:

**Implemented:**
- Phone call / alarm / Assistant brief takeover: `LOSS_TRANSIENT(_CAN_DUCK)` stops the keepalive; on `AUDIOFOCUS_GAIN`, the keepalive restarts and `GameController.onFocusRegained()` re-announces the engine's last move so the user has context after the interruption.
- Permanent loss (`AUDIOFOCUS_LOSS`, e.g. user starts another media app deliberately): session ends cleanly via `onPermanentLoss` callback.
- `TtsManager.stop()` called at the start of `openListenWindow` so the user can interrupt a long announcement (e.g. `"describe board"`) with a headset tap. Without this the mic would pick up the in-flight TTS.

**Deferred to later (lower priority):**
- BT headset disconnect / reconnect detection independent of focus events. The focus listener catches many of these for free (BT disconnect usually triggers focus loss), but a brief drop-and-reconnect without focus events isn't caught. Would need `ACTION_AUDIO_BECOMING_NOISY` + `BluetoothHeadset.STATE_AUDIO_DISCONNECTED` receivers.
- Resume-on-end behavior matrix across Spotify / YouTube Music / YouTube / Pocket Casts / Apple Podcasts — needs hardware testing on each. Ship in onboarding.

### Phase 6 — Persistence
- Room schema, auto-save per move, resume flow, history list, PGN export.

### Phase 7 — Settings UI
- Verbosity, notation mode, engine strength, headphone test, model management.

### Phase 8 — Outdoor field testing
- Wind, traffic, motion will surface ASR issues invisible at a desk.
- Tune grammar, add synonyms (e.g. accept `"knight"` and `"horse"`), adjust mic gain.

## Open risks

| Risk | Mitigation |
|---|---|
| TWS earbuds eat media buttons entirely (route to own assistant) | Headphone test screen in onboarding; document compatibility; recommend over-ear or non-assistant earbuds |
| Different headsets send different keycodes for the same gesture | Test screen exposes raw events; tap mapping is configurable in settings if needed |
| User expects music to keep playing in the background during a game | Onboarding explains: starting a game pauses other media for the session (required for Bluetooth headset routing — see Phase 1 finding 1) and most apps auto-resume on game end. After game ends, headset buttons revert to music control. |
| BT auto-resume behavior varies by app | Phase 5 ships a per-app compatibility matrix in onboarding (Spotify resumes, YouTube does not, etc.). |
| Vosk accuracy outdoors | Constrained grammar; consider a confidence-threshold fallback to "did you mean X?" if field testing shows it's needed |
| Battery drain | Foreground service is honest about its purpose; expect 10–15%/hour, acceptable for typical commute |
| OEM background-killing (Xiaomi, Huawei, OnePlus) | Onboarding step: "disable battery optimization for this app" |
| Stockfish NDK build complexity | Phase 2 is its own thing; budget time for ABI and CMake debugging |

## Out of scope for v1

- Puzzle / tactics mode
- Game replay or opening trainer
- Online play
- Multiple languages (English only)
- Wake word (deferred; headset button only)
- iOS

## First Claude Code prompt

After scaffolding, the first concrete prompt should be:

> Read DESIGN.md. Implement Phase 1 only: Android project with Kotlin/Compose, min SDK 26, target latest. Foreground service of type `mediaPlayback` that hosts a `MediaSessionCompat`. The session is set active when the user taps a "Start mock game" button in the UI, and released when they tap "Stop". While active, the session's `MediaSessionCompat.Callback.onMediaButtonEvent` handles every `KeyEvent`. Map keycodes: `KEYCODE_MEDIA_PLAY_PAUSE` → TTS "listening"; `KEYCODE_MEDIA_NEXT` → TTS "repeat"; `KEYCODE_MEDIA_PREVIOUS` → TTS "cancel". TTS calls must request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` and release focus when speech ends. Include a settings screen with a "headphone button test" page that logs every received `KeyEvent` (action, keycode, repeat count, timestamp) in a scrollable list. Do not implement anything beyond Phase 1 — no chess engine, no ASR, no persistence.
