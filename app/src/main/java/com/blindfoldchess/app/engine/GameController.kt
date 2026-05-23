package com.blindfoldchess.app.engine

import android.content.Context
import android.util.Log
import com.blindfoldchess.app.service.Earcons
import com.blindfoldchess.app.service.TtsManager
import com.blindfoldchess.app.voice.MoveParser
import com.blindfoldchess.app.voice.MoveSpeech
import com.blindfoldchess.app.voice.VoskRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives a real game vs. the embedded Stockfish engine via voice (headset) input.
 *
 * Owned by [com.blindfoldchess.app.service.ChessGameService]. Exposes [state] for the UI.
 *
 * State machine:
 *   Idle ──startGame()──> Loading ──> WaitingForUser
 *   WaitingForUser ──openListenWindow()──> Listening ──> Thinking ──> WaitingForUser
 *                                                                  └─> GameOver
 *   any ──stopGame()──> Idle
 *
 * The engine and recognizer hold large native resources; one instance of this controller
 * per process. Service is responsible for [release] on destroy.
 */
class GameController(
    private val context: Context,
    private val tts: TtsManager,
    private val earcons: Earcons,
) {

    enum class Status { Idle, Loading, WaitingForUser, Listening, Thinking, GameOver, Error }
    enum class Color { White, Black }

    data class State(
        val status: Status = Status.Idle,
        val moves: List<String> = emptyList(),
        val lastEngineMove: String? = null,
        val lastPartialText: String = "",
        val lastFinalText: String? = null,
        val message: String? = null,
    ) {
        val whoseTurn: Color get() = if (moves.size % 2 == 0) Color.White else Color.Black
    }

    private val jni = StockfishJni()
    private val engine = StockfishEngine(jni)
    private val recognizer = VoskRecognizer(context)
    private val assets = EngineAssets(context)
    private val parser = MoveParser()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gameLock = Mutex()
    private var listenJob: Job? = null

    /** Boots engine + recognizer and announces "your move". Idempotent vs. concurrent calls. */
    suspend fun startGame() = gameLock.withLock {
        val cur = _state.value.status
        if (cur != Status.Idle && cur != Status.GameOver && cur != Status.Error) return@withLock
        _state.update { State(status = Status.Loading, message = "Loading engine + recognizer...") }
        try {
            val nnue = withContext(Dispatchers.IO) { assets.ensureExtracted() }
            engine.start()
            if (nnue != null) {
                engine.setOption("EvalFile", nnue.big.absolutePath)
                engine.setOption("EvalFileSmall", nnue.small.absolutePath)
            } else {
                Log.w(TAG, "NNUE not bundled — engine will use degraded eval")
            }
            engine.newGame()
            recognizer.ensureModel()
            _state.update { State(status = Status.WaitingForUser) }
            tts.speak("your move")
        } catch (t: Throwable) {
            Log.w(TAG, "startGame failed", t)
            _state.update { it.copy(status = Status.Error, message = t.message ?: "unknown error") }
        }
    }

    /** Stops engine/recognizer and resets to Idle. Safe to call from any state. */
    fun stopGame() {
        scope.launch {
            gameLock.withLock {
                listenJob?.cancel()
                listenJob = null
                runCatching { recognizer.stopListening() }
                runCatching { engine.stop() }
                _state.value = State(status = Status.Idle)
            }
        }
    }

    /**
     * Opens an ASR window. Earcon → mic on → first Vosk final result (or 5s timeout) →
     * mic off. On parseable move, send to engine, await reply, TTS. On unparseable,
     * TTS "didn't catch that" and return to WaitingForUser.
     *
     * Ignored if not currently WaitingForUser (e.g. mid-search).
     */
    fun openListenWindow() {
        if (_state.value.status != Status.WaitingForUser) {
            Log.d(TAG, "openListenWindow ignored in status=${_state.value.status}")
            return
        }
        listenJob?.cancel()
        listenJob = scope.launch {
            _state.update { it.copy(status = Status.Listening, lastPartialText = "", lastFinalText = null) }
            earcons.listenStart()
            try {
                recognizer.startListening()
                val partialCollector = launch {
                    recognizer.events.filter { !it.isFinal }.collect { event ->
                        _state.update { it.copy(lastPartialText = event.text) }
                    }
                }
                val final = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
                    recognizer.events.first { it.isFinal }
                }
                partialCollector.cancel()
                if (final == null) {
                    tts.speak("didn't catch that")
                    _state.update { it.copy(status = Status.WaitingForUser, lastPartialText = "") }
                } else {
                    _state.update { it.copy(lastFinalText = final.text, lastPartialText = "") }
                    processSpoken(final.text)
                }
            } finally {
                runCatching { recognizer.stopListening() }
                _state.update {
                    if (it.status == Status.Listening) {
                        it.copy(status = Status.WaitingForUser, lastPartialText = "")
                    } else it
                }
            }
        }
    }

    /** Silently aborts an in-progress listen window. */
    fun cancelListenWindow() {
        listenJob?.cancel()
        listenJob = null
    }

    /** Re-speaks the last engine move. No-op if no engine move yet. */
    fun repeatLastEngineMove() {
        val move = _state.value.lastEngineMove ?: return
        tts.speak(MoveSpeech.spoken(move))
    }

    fun release() {
        runCatching { listenJob?.cancel() }
        runCatching { recognizer.release() }
        runCatching { engine.stop() }
        scope.cancel()
    }

    // -------------------------------------------------------------------------

    private suspend fun processSpoken(text: String) {
        val parsed = parser.parse(text)
        if (parsed == null) {
            tts.speak("didn't catch that")
            _state.update { it.copy(status = Status.WaitingForUser) }
            return
        }
        val uci = resolveToUci(parsed)
        playUserMove(uci)
    }

    private fun resolveToUci(parsed: MoveParser.Parsed): String = when (parsed) {
        is MoveParser.Parsed.Normal -> parsed.toUci()
        MoveParser.Parsed.CastleKingside ->
            if (_state.value.whoseTurn == Color.White) "e1g1" else "e8g8"
        MoveParser.Parsed.CastleQueenside ->
            if (_state.value.whoseTurn == Color.White) "e1c1" else "e8c8"
    }

    private suspend fun playUserMove(uci: String) {
        gameLock.withLock {
            _state.update { it.copy(status = Status.Thinking, moves = it.moves + uci) }
            try {
                engine.setPosition(startFen = null, moves = _state.value.moves)
                val reply = engine.goMoveTime(ENGINE_MOVE_TIME_MS)
                val gameOver = reply == "(none)" || reply == "0000" || reply.isBlank()
                if (gameOver) {
                    _state.update {
                        it.copy(status = Status.GameOver, lastEngineMove = null, message = "Game over")
                    }
                    tts.speak("game over")
                } else {
                    _state.update {
                        it.copy(
                            status = Status.WaitingForUser,
                            moves = it.moves + reply,
                            lastEngineMove = reply,
                        )
                    }
                    tts.speak(MoveSpeech.spoken(reply))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "playUserMove failed", t)
                _state.update { it.copy(status = Status.Error, message = t.message ?: "unknown") }
            }
        }
    }

    private companion object {
        const val TAG = "GameController"
        const val LISTEN_TIMEOUT_MS = 5_000L
        const val ENGINE_MOVE_TIME_MS = 500L
    }
}
