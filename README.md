# Blindfold Chess

A native Android app for practicing blindfold chess hands-free while commuting or jogging.
Wear headphones, play full games against Stockfish offline, and interact entirely by voice.

See [DESIGN.md](DESIGN.md) for the detailed design and phasing.

## Features

- Voice play vs. offline Stockfish (NNUE), driven by headset media-button taps + speech.
- On-device speech recognition via Vosk (no network calls during a game).
- Auto-save per move, resume on launch, persistent game history.
- Optional board view with per-square fog toggle for training visualization, plus
  long-press tap-to-move for non-voice play.
- Three TTS notations (letter-by-letter, NATO phonetic, standard algebraic).
- Configurable engine skill, think time, verbosity, fog default, user side (white/black).

## License

This program is free software: you can redistribute it and/or modify it under the terms
of the **GNU General Public License v3** as published by the Free Software Foundation. See
[LICENSE](LICENSE) for the full text.

It links the [Stockfish](https://github.com/official-stockfish/Stockfish) chess engine
(GPLv3), and that determines the license of the combined work.

Third-party assets and libraries are documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), including the **Cburnett** chess piece
artwork (Colin M.L. Burnett, CC-BY-SA 3.0) and the
[Vosk](https://github.com/alphacep/vosk-api) speech recognition library (Apache 2.0).

## Build

Requires the NNUE and Vosk model assets, which are gitignored due to size:

```bash
./scripts/fetch_nnue.sh
./scripts/fetch_vosk.sh
```

Then open in Android Studio (NDK r26+ and CMake 3.22+ are needed for the Stockfish JNI
build) and run on a device with `arm64-v8a` architecture.

## Privacy

See [PRIVACY_POLICY.md](PRIVACY_POLICY.md). Short version: everything happens on-device,
nothing leaves your phone.
