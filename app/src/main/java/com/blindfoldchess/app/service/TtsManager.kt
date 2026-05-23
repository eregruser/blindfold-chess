package com.blindfoldchess.app.service

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Speaks short phrases via the platform TTS engine.
 *
 * Audio focus is intentionally NOT requested here — [SessionAudio] holds AUDIOFOCUS_GAIN for the
 * whole game/test session so the BT stack routes media keys to us. TTS just plays through that
 * existing focus.
 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val tts: TextToSpeech = TextToSpeech(appContext, this)

    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: status=$status")
            return
        }
        tts.language = Locale.US
        tts.setAudioAttributes(audioAttributes)
        ready = true
    }

    fun speak(text: String) {
        if (!ready) {
            Log.w(TAG, "TTS not ready; dropping utterance: \"$text\"")
            return
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS.speak failed for \"$text\" (result=$result)")
        }
    }

    /** Cancels any in-flight utterance immediately. Used to interrupt long announcements
     *  (e.g. describe-board) when the user opens a new listen window. */
    fun stop() {
        try {
            tts.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "TTS stop threw", t)
        }
    }

    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "TTS shutdown threw", t)
        }
    }

    private companion object {
        const val TAG = "TtsManager"
    }
}
