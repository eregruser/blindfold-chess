package com.blindfoldchess.app.engine

import android.content.Context
import android.util.Log
import com.blindfoldchess.app.chess.Board
import com.blindfoldchess.app.chess.Color
import com.blindfoldchess.app.chess.PieceType
import com.blindfoldchess.app.service.Earcons
import com.blindfoldchess.app.service.TtsManager
import com.blindfoldchess.app.voice.ChessGrammar
import com.blindfoldchess.app.voice.MoveParser
import com.blindfoldchess.app.voice.MoveSpeech
import com.blindfoldchess.app.voice.VoiceCommand
import com.blindfoldchess.app.voice.VoiceCommandParser
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

    data class State(
        val status: Status = Status.Idle,
        val moves: List<String> = emptyList(),
        val legalMoves: List<String> = emptyList(),
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
    private val commandParser = VoiceCommandParser(MoveParser())

    /** The human side. v1 hard-codes white; settings toggle will arrive later. */
    private val userColor: Color = Color.White

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
            engine.setPosition(startFen = null, moves = emptyList())
            val initialLegal = engine.perft(1)
            _state.update {
                State(status = Status.WaitingForUser, legalMoves = initialLegal)
            }
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
                val grammar = ChessGrammar.legal(_state.value.legalMoves)
                recognizer.startListening(grammar = grammar)
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
        val command = commandParser.parse(text)
        if (command == null) {
            tts.speak("didn't catch that")
            _state.update { it.copy(status = Status.WaitingForUser) }
            return
        }
        when (command) {
            is VoiceCommand.Move -> handleMove(command.parsed, text)
            VoiceCommand.Repeat -> handleRepeat()
            VoiceCommand.TakeBack -> handleTakeBack()
            VoiceCommand.WhoseTurn -> handleWhoseTurn()
            VoiceCommand.HowManyMoves -> handleHowManyMoves()
            VoiceCommand.ListPieces -> handleListPieces()
            VoiceCommand.DescribeBoard -> handleDescribeBoard()
            VoiceCommand.Resign -> handleResign()
            VoiceCommand.NewGame -> handleNewGame()
            is VoiceCommand.WhatsOn -> handleWhatsOn(command.square)
        }
    }

    // ---------- handlers ----------

    private suspend fun handleMove(parsed: MoveParser.Parsed, originalText: String) {
        val uci = resolveToUci(parsed)
        if (uci !in _state.value.legalMoves) {
            Log.i(TAG, "Rejecting illegal move \"$uci\" (parsed from \"$originalText\")")
            tts.speak("illegal")
            _state.update { it.copy(status = Status.WaitingForUser) }
            return
        }
        playUserMove(uci)
    }

    private fun handleRepeat() {
        val move = _state.value.lastEngineMove
        if (move == null) tts.speak("no engine move yet") else tts.speak(MoveSpeech.spoken(move))
    }

    private suspend fun handleTakeBack() = gameLock.withLock {
        val moves = _state.value.moves
        if (moves.size < 2) {
            tts.speak("nothing to take back")
            return@withLock
        }
        val newMoves = moves.dropLast(2)
        engine.setPosition(startFen = null, moves = newMoves)
        val nextLegal = engine.perft(1)
        _state.update {
            it.copy(
                status = Status.WaitingForUser,
                moves = newMoves,
                lastEngineMove = newMoves.lastOrNull(),
                legalMoves = nextLegal,
                message = null,
            )
        }
        tts.speak("taken back")
    }

    private fun handleWhoseTurn() {
        val side = if (_state.value.whoseTurn == Color.White) "white" else "black"
        tts.speak("$side to move")
    }

    private fun handleHowManyMoves() {
        val full = (_state.value.moves.size + 1) / 2
        tts.speak("$full full moves played")
    }

    private suspend fun handleListPieces() = gameLock.withLock {
        val board = engine.currentBoard()
        val phrase = describePieces(board, userColor)
        tts.speak("your pieces: $phrase")
    }

    private suspend fun handleDescribeBoard() = gameLock.withLock {
        val board = engine.currentBoard()
        val white = describePieces(board, Color.White)
        val black = describePieces(board, Color.Black)
        // Single TTS call — multiple calls would self-cancel via QUEUE_FLUSH.
        tts.speak("white: $white. black: $black.")
    }

    private fun handleResign() {
        _state.update {
            it.copy(
                status = Status.GameOver,
                legalMoves = emptyList(),
                message = "Resigned",
            )
        }
        tts.speak("game over, you resigned")
    }

    private suspend fun handleNewGame() = gameLock.withLock {
        engine.newGame()
        engine.setPosition(startFen = null, moves = emptyList())
        val initialLegal = engine.perft(1)
        _state.value = State(status = Status.WaitingForUser, legalMoves = initialLegal)
        tts.speak("new game. your move.")
    }

    private suspend fun handleWhatsOn(square: String) = gameLock.withLock {
        val board = engine.currentBoard()
        val piece = board.pieceAt(square)
        val spokenSquare = squareToSpoken(square)
        if (piece == null) {
            tts.speak("$spokenSquare is empty")
        } else {
            val color = if (piece.color == Color.White) "white" else "black"
            tts.speak("$color ${piece.type.spoken} on $spokenSquare")
        }
    }

    // ---------- helpers ----------

    private fun resolveToUci(parsed: MoveParser.Parsed): String = when (parsed) {
        is MoveParser.Parsed.Normal -> parsed.toUci()
        MoveParser.Parsed.CastleKingside ->
            if (_state.value.whoseTurn == Color.White) "e1g1" else "e8g8"
        MoveParser.Parsed.CastleQueenside ->
            if (_state.value.whoseTurn == Color.White) "e1c1" else "e8c8"
    }

    private fun describePieces(board: Board, color: Color): String {
        val pieces = board.piecesOf(color)
        if (pieces.isEmpty()) return "none"
        // Group by piece type for a shorter announcement.
        val byType = pieces.groupBy { it.second.type }
        val ordered = listOf(
            PieceType.King, PieceType.Queen, PieceType.Rook,
            PieceType.Bishop, PieceType.Knight, PieceType.Pawn,
        )
        return ordered.mapNotNull { type ->
            val squares = byType[type] ?: return@mapNotNull null
            val placed = squares.joinToString(" and ") { squareToSpoken(it.first) }
            val noun = if (squares.size == 1) type.spoken else "${type.spoken}s"
            "$noun on $placed"
        }.joinToString(", ")
    }

    private fun squareToSpoken(square: String): String {
        val file = square[0]
        val rank = square[1]
        val fileWord = when (file) {
            'a' -> "alpha"; 'b' -> "bravo"; 'c' -> "charlie"; 'd' -> "delta"
            'e' -> "echo"; 'f' -> "foxtrot"; 'g' -> "golf"; 'h' -> "hotel"
            else -> file.toString()
        }
        val rankWord = when (rank) {
            '1' -> "one"; '2' -> "two"; '3' -> "three"; '4' -> "four"
            '5' -> "five"; '6' -> "six"; '7' -> "seven"; '8' -> "eight"
            else -> rank.toString()
        }
        return "$fileWord $rankWord"
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
                        it.copy(
                            status = Status.GameOver,
                            lastEngineMove = null,
                            legalMoves = emptyList(),
                            message = "Game over",
                        )
                    }
                    tts.speak("game over")
                } else {
                    // Apply engine reply to position, then refresh legal moves for the next turn.
                    val newMoves = _state.value.moves + reply
                    engine.setPosition(startFen = null, moves = newMoves)
                    val nextLegal = engine.perft(1)
                    val terminal = nextLegal.isEmpty()
                    _state.update {
                        it.copy(
                            status = if (terminal) Status.GameOver else Status.WaitingForUser,
                            moves = newMoves,
                            lastEngineMove = reply,
                            legalMoves = nextLegal,
                            message = if (terminal) "Game over" else null,
                        )
                    }
                    tts.speak(MoveSpeech.spoken(reply))
                    if (terminal) tts.speak("game over")
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
