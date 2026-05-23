package com.blindfoldchess.app.engine

import android.content.Context
import android.util.Log
import com.blindfoldchess.app.chess.Board
import com.blindfoldchess.app.chess.Color
import com.blindfoldchess.app.chess.PieceType
import com.blindfoldchess.app.data.GameRepository
import com.blindfoldchess.app.data.GameResult
import com.blindfoldchess.app.data.SettingsRepository
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
    private val repo: GameRepository,
    private val settings: SettingsRepository,
) {

    private fun currentSettings(): SettingsRepository.Settings = settings.settings.value

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

    /** DB row id of the currently-active game, or null between games. */
    private var currentGameId: Long? = null

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
            val s = currentSettings()
            engine.setOption("Skill Level", s.skillLevel.toString())
            engine.newGame()
            recognizer.ensureModel()
            engine.setPosition(startFen = null, moves = emptyList())
            val initialLegal = engine.perft(1)

            // Abandon any previously-active game and create a fresh row for this session.
            abandonAnyActiveGame()
            currentGameId = repo.startNewGame(skillLevel = s.skillLevel)

            _state.update {
                State(status = Status.WaitingForUser, legalMoves = initialLegal)
            }
            tts.speak("your move")
        } catch (t: Throwable) {
            Log.w(TAG, "startGame failed", t)
            _state.update { it.copy(status = Status.Error, message = t.message ?: "unknown error") }
        }
    }

    private suspend fun abandonAnyActiveGame() {
        val active = repo.findActive() ?: return
        Log.i(TAG, "Marking previously-active game ${active.id} as Abandoned")
        repo.markComplete(active.id, GameResult.Abandoned)
    }

    /**
     * Boots engine + recognizer, restores a saved unfinished game's position, sets state
     * to WaitingForUser. Assumes the saved move list is at the user's turn (always true
     * given when persistMoves runs — after each engine reply).
     */
    suspend fun resumeGame(gameId: Long) = gameLock.withLock {
        val cur = _state.value.status
        if (cur != Status.Idle && cur != Status.GameOver && cur != Status.Error) {
            Log.w(TAG, "resumeGame ignored in status=$cur")
            return@withLock
        }
        val game = repo.findById(gameId)
        if (game == null) {
            Log.w(TAG, "resumeGame: no game with id $gameId")
            _state.update { it.copy(status = Status.Error, message = "Game $gameId not found") }
            return@withLock
        }
        if (game.completedAt != null) {
            Log.w(TAG, "resumeGame: game $gameId is already completed")
            _state.update { it.copy(status = Status.Error, message = "Game already finished") }
            return@withLock
        }

        _state.update { State(status = Status.Loading, message = "Resuming game...") }
        try {
            val nnue = withContext(Dispatchers.IO) { assets.ensureExtracted() }
            engine.start()
            if (nnue != null) {
                engine.setOption("EvalFile", nnue.big.absolutePath)
                engine.setOption("EvalFileSmall", nnue.small.absolutePath)
            }
            engine.setOption("Skill Level", game.skillLevel.toString())
            engine.newGame()
            recognizer.ensureModel()

            val moves = game.movesUci.split(" ").filter { it.isNotBlank() }
            engine.setPosition(startFen = null, moves = moves)
            val legal = engine.perft(1)
            currentGameId = game.id

            // lastEngineMove is the latest move IF the move count is even (which it should be,
            // since persistMoves only runs after engine replies).
            val lastEngineMove = if (moves.isNotEmpty() && moves.size % 2 == 0) moves.last() else null
            _state.update {
                State(
                    status = Status.WaitingForUser,
                    moves = moves,
                    lastEngineMove = lastEngineMove,
                    legalMoves = legal,
                )
            }
            tts.speak("game resumed")
            if (lastEngineMove != null) {
                tts.speak("last engine move: ${MoveSpeech.spoken(lastEngineMove, currentSettings().notation)}")
            }
            tts.speak("your move")
        } catch (t: Throwable) {
            Log.w(TAG, "resumeGame failed", t)
            _state.update { it.copy(status = Status.Error, message = t.message ?: "resume failed") }
        }
    }

    /** Stops engine/recognizer and resets to Idle. Safe to call from any state. */
    fun stopGame() {
        scope.launch {
            gameLock.withLock {
                listenJob?.cancel()
                listenJob = null
                // If we were mid-game (not already terminated), record as Abandoned.
                val id = currentGameId
                if (id != null && _state.value.status != Status.GameOver) {
                    runCatching { repo.markComplete(id, GameResult.Abandoned) }
                }
                currentGameId = null
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
            // Silence any in-flight TTS (e.g. mid-describe-board) so the mic doesn't pick it up
            // and so the user gets immediate feedback that they're being heard.
            tts.stop()
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
        announceEngineMove(move)
    }

    /**
     * Called after a transient audio-focus loss is recovered (e.g. phone call ended, alarm
     * dismissed). Re-announces the engine's last move so the user knows where they were
     * without having to remember through the interruption.
     */
    fun onFocusRegained() {
        val st = _state.value
        if (st.status != Status.WaitingForUser) return
        val move = st.lastEngineMove ?: return
        Log.i(TAG, "Focus regained mid-game; re-announcing engine move $move")
        announceEngineMove(move)
    }

    /**
     * TTS an engine move respecting the user's notation + verbosity settings. In verbose mode
     * prepends the moving side and appends "your turn." so the user can follow along after
     * an interruption / repeat. Caller is responsible for game-state being post-engine-reply
     * (i.e. status WaitingForUser, [_state] whoseTurn = user's color).
     */
    private fun announceEngineMove(move: String) {
        val s = currentSettings()
        val moveText = MoveSpeech.spoken(move, s.notation)
        val full = if (s.verbose) {
            val mover = if (_state.value.whoseTurn == Color.White) "black" else "white"
            "$mover plays $moveText. your turn."
        } else {
            moveText
        }
        tts.speak(full)
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
        if (move == null) tts.speak("no engine move yet") else announceEngineMove(move)
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
        persistMoves()
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

    private suspend fun handleResign() = gameLock.withLock {
        _state.update {
            it.copy(
                status = Status.GameOver,
                legalMoves = emptyList(),
                message = "Resigned",
            )
        }
        finalizeGame(GameResult.UserResigned)
        tts.speak("game over, you resigned")
    }

    private suspend fun handleNewGame() = gameLock.withLock {
        // Abandon the in-progress game (if not already terminated) before creating a fresh row.
        val previousId = currentGameId
        if (previousId != null && _state.value.status != Status.GameOver) {
            runCatching { repo.markComplete(previousId, GameResult.Abandoned) }
        }
        currentGameId = null

        val s = currentSettings()
        engine.setOption("Skill Level", s.skillLevel.toString())
        engine.newGame()
        engine.setPosition(startFen = null, moves = emptyList())
        val initialLegal = engine.perft(1)
        currentGameId = repo.startNewGame(skillLevel = s.skillLevel)
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
                val reply = engine.goMoveTime(currentSettings().moveTimeMs)
                val userMatedEngine = reply == "(none)" || reply == "0000" || reply.isBlank()
                if (userMatedEngine) {
                    _state.update {
                        it.copy(
                            status = Status.GameOver,
                            lastEngineMove = null,
                            legalMoves = emptyList(),
                            message = "Game over",
                        )
                    }
                    persistMoves()
                    finalizeGame(GameResult.UserWin)
                    tts.speak("game over")
                } else {
                    // Apply engine reply to position, then refresh legal moves for the next turn.
                    val newMoves = _state.value.moves + reply
                    engine.setPosition(startFen = null, moves = newMoves)
                    val nextLegal = engine.perft(1)
                    val userHasNoMoves = nextLegal.isEmpty()
                    _state.update {
                        it.copy(
                            status = if (userHasNoMoves) Status.GameOver else Status.WaitingForUser,
                            moves = newMoves,
                            lastEngineMove = reply,
                            legalMoves = nextLegal,
                            message = if (userHasNoMoves) "Game over" else null,
                        )
                    }
                    persistMoves()
                    if (userHasNoMoves) {
                        // Could be mate or stalemate — we don't distinguish for now (Phase 6+).
                        finalizeGame(GameResult.UserLoss)
                    }
                    announceEngineMove(reply)
                    if (userHasNoMoves) tts.speak("game over")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "playUserMove failed", t)
                _state.update { it.copy(status = Status.Error, message = t.message ?: "unknown") }
            }
        }
    }

    /** Write the current move list to the active game row. No-op if no active game id. */
    private suspend fun persistMoves() {
        val id = currentGameId ?: return
        runCatching { repo.recordMoves(id, _state.value.moves) }
    }

    /** Mark the current game complete with the given result. Clears currentGameId. */
    private suspend fun finalizeGame(result: GameResult) {
        val id = currentGameId ?: return
        runCatching { repo.markComplete(id, result) }
        currentGameId = null
    }

    private companion object {
        const val TAG = "GameController"
        const val LISTEN_TIMEOUT_MS = 5_000L
    }
}
