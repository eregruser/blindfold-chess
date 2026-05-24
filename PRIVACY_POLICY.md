# Privacy Policy

_Blindfold Chess_ collects no data and sends nothing off your device.

## What the app does on your device

- **Microphone audio.** When you open a listen window (headset tap or on-screen
  button), the app records audio and feeds it to an on-device speech recognizer
  (Vosk). Audio is processed locally in memory and discarded immediately after the
  utterance is recognized. It is never written to disk, never uploaded, never shared.
- **Game data.** Completed and in-progress games are stored in a local SQLite database
  on your device for the auto-save / resume / history features. This data never
  leaves your device.
- **Settings.** Preferences (skill level, notation, fog default, side, etc.) are stored
  in local Android preferences storage. Local-only.

## What the app does not do

- No accounts, no sign-in, no user identifiers.
- No analytics or telemetry.
- No third-party advertising or tracking SDKs.
- No network calls during gameplay.
- No data collection or sharing of any kind.

## Permissions

- `RECORD_AUDIO` — needed to recognize spoken chess moves locally.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
  `FOREGROUND_SERVICE_MICROPHONE` — needed to keep the game running with the screen
  off and to capture headset media-button events while audio is playing.
- `POST_NOTIFICATIONS` — needed to show the ongoing-game notification while the
  service is running.

## Third-party content

The app bundles third-party libraries and assets (Stockfish, Vosk, Cburnett piece
artwork). These are part of the binary; they make no network calls and collect no
data. See `THIRD_PARTY_NOTICES.md` in the source repository for full attribution.

## Changes

Updates to this policy will be reflected in this file in the source repository, and
the in-app "Privacy policy" link will point to the current version.

## Contact

Source code, issues, and questions: see the repository linked from the app's About
screen.
