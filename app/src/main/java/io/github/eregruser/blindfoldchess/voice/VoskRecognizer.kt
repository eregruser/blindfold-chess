package io.github.eregruser.blindfoldchess.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin Kotlin wrapper around the Vosk offline ASR library. One instance per app process is
 * intended; the underlying Model + Recognizer hold large native allocations.
 *
 * Lifecycle:
 *   1. [ensureModel] — extracts the model directory from APK assets to internal storage
 *      (idempotent, ~7s on first run for the small English model) and constructs [Model].
 *   2. [startListening] — opens the mic via Vosk's [SpeechService], constructs a [Recognizer]
 *      (optionally grammar-constrained), and starts feeding audio.
 *   3. [stopListening] — releases mic + recognizer.
 *   4. [release] — releases the model too.
 *
 * Recognition events come out on [events]: partials during speech, finals when the recognizer
 * decides an utterance is complete.
 */
class VoskRecognizer(private val context: Context) {

    enum class State { Idle, Loading, Ready, Listening, Error }

    data class Event(val text: String, val isFinal: Boolean)

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    /**
     * Extracts the bundled Vosk model from APK assets (`assets/vosk-model-small-en-us-0.15/`)
     * to `filesDir/vosk-model/` if not already there, then loads it. Idempotent.
     */
    suspend fun ensureModel() {
        if (model != null) {
            _state.value = State.Ready
            return
        }
        _state.value = State.Loading
        try {
            val m = unpackModel()
            model = m
            _state.value = State.Ready
            Log.i(TAG, "Vosk model loaded")
        } catch (t: Throwable) {
            Log.w(TAG, "Vosk model load failed", t)
            _state.value = State.Error
            throw t
        }
    }

    private suspend fun unpackModel(): Model =
        suspendCancellableCoroutine { cont ->
            StorageService.unpack(
                context,
                MODEL_ASSET_DIR,
                MODEL_INTERNAL_DIR,
                { m -> if (cont.isActive) cont.resume(m) },
                { e -> if (cont.isActive) cont.resumeWithException(e) },
            )
        }

    /**
     * Opens the mic and begins recognition. Requires [ensureModel] to have completed.
     * If [grammar] is non-null it's a JSON array of phrases (e.g. `["[\"e2 to e4\", \"e7 to e5\"]"`)
     * and the recognizer will only emit those phrases (plus `"[unk]"`).
     */
    fun startListening(grammar: String? = null) {
        val m = model ?: error("Call ensureModel() before startListening()")
        if (speechService != null) {
            Log.w(TAG, "startListening called while already listening — ignoring")
            return
        }
        val rec = if (grammar != null) {
            Recognizer(m, SAMPLE_RATE, grammar)
        } else {
            Recognizer(m, SAMPLE_RATE)
        }
        recognizer = rec
        val service = SpeechService(rec, SAMPLE_RATE)
        speechService = service
        service.startListening(listener)
        _state.value = State.Listening
    }

    fun stopListening() {
        speechService?.let {
            it.stop()
            it.shutdown()
        }
        speechService = null
        recognizer?.close()
        recognizer = null
        if (_state.value == State.Listening) _state.value = State.Ready
    }

    fun release() {
        stopListening()
        model?.close()
        model = null
        _state.value = State.Idle
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String) {
            val text = parseField(hypothesis, "partial")
            if (text.isNotBlank()) _events.tryEmit(Event(text, isFinal = false))
        }

        override fun onResult(hypothesis: String) {
            val text = parseField(hypothesis, "text")
            Log.d(TAG, "onResult: \"$text\"  (raw=$hypothesis)")
            if (text.isNotBlank()) _events.tryEmit(Event(text, isFinal = true))
        }

        override fun onFinalResult(hypothesis: String) {
            val text = parseField(hypothesis, "text")
            Log.d(TAG, "onFinalResult: \"$text\"  (raw=$hypothesis)")
            if (text.isNotBlank()) _events.tryEmit(Event(text, isFinal = true))
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "Vosk recognition error", e)
            _state.value = State.Error
        }

        override fun onTimeout() {
            Log.i(TAG, "Vosk recognition timeout")
            stopListening()
        }
    }

    private fun parseField(json: String, field: String): String {
        return try {
            JSONObject(json).optString(field, "")
        } catch (_: Throwable) {
            ""
        }
    }

    companion object {
        private const val TAG = "VoskRecognizer"
        private const val SAMPLE_RATE = 16_000.0f

        /** Asset-relative path; matches the directory inside the .zip from alphacephei.com. */
        private const val MODEL_ASSET_DIR = "vosk-model-small-en-us-0.15"

        /** Path under filesDir/ that StorageService.unpack writes to. */
        private const val MODEL_INTERNAL_DIR = "vosk-model"
    }
}
