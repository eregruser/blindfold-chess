package com.blindfoldchess.app.service

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Short non-speech cues. Mixed into our session's audio focus alongside TTS — they play
 * immediately (no synthesis latency) so the user gets sub-100ms feedback that a tap was
 * received before the slower TTS phrase catches up.
 */
class Earcons {

    private val toneGen: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
    } catch (t: Throwable) {
        Log.w(TAG, "ToneGenerator init failed; earcons disabled", t)
        null
    }

    /** "Listening window opened" cue. Plays during the mock-game `PLAY_PAUSE`/`PLAY` handler. */
    fun listenStart() {
        toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    /** "Listening window closed" cue. Reserved for Phase 3 when there's a real ASR window. */
    fun listenEnd() {
        toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
    }

    fun release() {
        try { toneGen?.release() } catch (_: Throwable) {}
    }

    private companion object {
        const val TAG = "Earcons"
        const val VOLUME = 90
    }
}
