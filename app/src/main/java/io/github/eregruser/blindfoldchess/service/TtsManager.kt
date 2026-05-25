package io.github.eregruser.blindfoldchess.service

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * Utterance ids of in-flight [speakAndWait] calls mapped to deferreds that complete when
     * the engine signals onDone / onError / onStop. Fire-and-forget [speak] does not register
     * here.
     */
    private val pendingCompletions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: status=$status")
            return
        }
        tts.language = Locale.US
        tts.setAudioAttributes(audioAttributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pendingCompletions.remove(it)?.complete(Unit) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pendingCompletions.remove(it)?.complete(Unit) }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { pendingCompletions.remove(it)?.complete(Unit) }
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { pendingCompletions.remove(it)?.complete(Unit) }
            }
        })
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

    /**
     * Speaks [text] and suspends until the engine reports the utterance complete (onDone, or
     * onStop after being interrupted by another [speak] / [stop] / shutdown). QUEUE_FLUSH so
     * any in-flight utterance is replaced immediately.
     *
     * Cancellation-safe: if the coroutine is cancelled, the pending entry is dropped from the
     * map in the finally block, but the TTS engine is left to finish on its own — callers
     * who want to also stop the audio should call [stop] after cancelling.
     */
    suspend fun speakAndWait(text: String) {
        if (!ready) {
            Log.w(TAG, "TTS not ready; dropping utterance: \"$text\"")
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Unit>()
        pendingCompletions[utteranceId] = deferred
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            pendingCompletions.remove(utteranceId)
            Log.w(TAG, "TTS.speak failed for \"$text\" (result=$result)")
            return
        }
        try {
            deferred.await()
        } finally {
            pendingCompletions.remove(utteranceId)
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
