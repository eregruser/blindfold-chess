# Releasing

End-to-end flow for cutting a Play Store release. Run through this once per release.

## 0. Prerequisites (one-time)

1. **Google Play Console account** — register at https://play.google.com/console
   ($25 one-time fee, identity verification can take up to 48 h for individuals).
2. **Upload keystore** — see step 1 below. **Back this up off-machine**; if you lose
   it you cannot ship updates to the same `applicationId` without going through
   Play's upload-key reset flow (slow, and not always granted).
3. **Stockfish submodule + asset files** are present locally:
   ```bash
   git submodule update --init --recursive
   ./scripts/fetch_nnue.sh
   ./scripts/fetch_vosk.sh
   ```

## 1. Create the upload keystore (one-time)

```bash
keytool -genkeypair -v \
    -keystore upload-keystore.jks \
    -alias upload \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=eregruser, OU=, O=, L=, S=, C=US"
```

`keytool` will prompt for a keystore password and a key password. Save both — they go
into `keystore.properties` next, and there is no recovery if you forget them.

Then copy the template and fill it in:

```bash
cp keystore.properties.example keystore.properties
$EDITOR keystore.properties
```

If `upload-keystore.jks` sits at the repo root, the example `storeFile=upload-keystore.jks`
line works as-is. `keystore.properties` and `*.jks` are both gitignored.

**Back up `upload-keystore.jks` and `keystore.properties` to a password manager or
encrypted volume now.** A second copy on a different disk is the minimum.

## 2. Bump the version (per release)

In `app/build.gradle.kts`:

- `versionCode` — integer, must increase monotonically across every Play upload.
  Convention: increment by 1 per release (`1` → `2` → `3` …). Play rejects re-uploads
  with the same `versionCode`.
- `versionName` — user-visible string. Convention: semver
  (`0.1.0` → `0.2.0` → `1.0.0` …).

Commit the bump as its own commit so the release tag points at exactly the code that
was uploaded.

## 3. Build the release AAB

```bash
./gradlew :app:bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab` (~10–20 MB; the NNUE +
Vosk assets ship in the install-time asset pack, not the base AAB).

Sanity check the signing:

```bash
# Confirm the AAB is signed with the upload key (not the debug key).
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab \
    | grep -E '^(s|sm|smk|Signed by)' | head
```

The signer DN should match the `-dname` you used in step 1, NOT
`CN=Android Debug,O=Android,C=US`.

## 4. First-time only: create the app in Play Console

1. Play Console → **Create app**
   - App name: `Blindfold Chess`
   - Default language: English (United States)
   - App or game: App
   - Free or paid: Free
   - Declarations: tick the developer-program-policies and US-export-laws boxes.
2. Set up the app:
   - **App access** — All functionality available without restrictions.
   - **Ads** — No, my app does not contain ads.
   - **Content rating** — fill the IARC questionnaire. For a chess app the result
     is "Everyone" / PEGI 3 across all regions.
   - **Target audience** — 13+ is the safe default unless you want to be
     listed as "Designed for Families" (then 5+, with extra requirements).
   - **News app** — No.
   - **COVID-19 contact tracing** — No.
   - **Data safety** — declare:
     - Audio recording: collected on-device, *not* shared, *not* stored — used
       only for speech-to-text within a game session.
     - App activity (game history, settings): stored on-device only, not shared.
     - No data collected by the app developer.
     The form has explicit "processed on device only / not sent off device"
     options for these — pick them.
   - **Government apps** — No.
   - **Financial features** — No.
   - **Health** — No.
3. **Store listing**:
   - Short description (≤80 chars): e.g.,
     `Blindfold chess training. Play hands-free vs. Stockfish via headset and voice.`
   - Full description (≤4000 chars): write up the feature list from `README.md`
     and explain the headset-button + voice flow.
   - **App icon** — upload `play/icon-512.png`.
   - **Feature graphic** (1024×500 PNG) — required; not yet generated in-repo.
   - **Phone screenshots** — 2–8 required, 16:9 to 9:16, min edge 320 px.
     Capture from a real device or the emulator; include the board view,
     preferences, game history, and About screens at minimum.
4. **App content** → **Privacy policy** — paste
   `https://github.com/eregruser/blindfold-chess/blob/main/PRIVACY_POLICY.md`.

## 5. Upload the AAB and submit for review

1. Play Console → **Releases** → **Production** → **Create new release**.
   (You explicitly decided to skip Internal/Closed testing tracks for v0.1
   because the user base is tiny.)
2. **App signing by Google Play** — enabled by default for new apps. Accept it.
   Your upload keystore signs uploads; Google holds the actual app-signing key
   and re-signs each install.
3. Drag-drop `app-release.aab`. Play will:
   - Verify the upload signature matches the registered upload key.
   - Run pre-launch report (automated install + smoke tests on a few Firebase
     Test Lab devices).
4. Fill **Release notes** (per language, ≤500 chars).
5. **Review release** → **Start rollout to Production**.

Review for a new app typically takes a few hours to several days. Subsequent
releases are usually under a day.

## 6. After approval

- Tag the commit:
  ```bash
  git tag -a v0.1.0 -m "v0.1.0 — first Play Store release"
  git push origin v0.1.0
  ```
- Verify the Play listing URL works:
  `https://play.google.com/store/apps/details?id=com.blindfoldchess.app`
- Smoke-test by installing from Play on a real device — confirm the asset pack
  downloads cleanly during install and the engine starts on first launch.

## Troubleshooting

- **`Failed to read key … from store`** — keystore password / key alias /
  key password mismatch between `keystore.properties` and the keystore.
- **`Upload failed: APK signed with a different key`** — you generated a new
  keystore but Play already has a different upload key on file. Either sign
  with the original, or go through Play Console → Setup → App signing → Request
  upload key reset (Google support intervention).
- **Asset pack size warnings** — `engineassets` is configured for install-time
  delivery; the pack downloads with the app and the user never sees it as a
  separate step. If size grows past Play's pack limits (1.5 GB per install-time
  pack, 4 GB total app), switch the smaller-but-rarely-used assets to
  `on-demand` delivery instead.
