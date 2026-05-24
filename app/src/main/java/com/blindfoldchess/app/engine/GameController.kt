package com.blindfoldchess.app.engine

import android.content.Context
import android.util.Log
import com.blindfoldchess.app.chess.Board
import com.blindfoldchess.app.chess.Color
import com.blindfoldchess.app.chess.PieceType
import com.blindfoldchess.app.chess.SanConverter
import com.blindfoldchess.app.chess.SanSpeech
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
import kotlinx.coroutines.delay
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
        /** Snapshot of the engine's current position. Updated after every move. */
        val board: Board? = null,
        /** Which side the human is playing in the current game. */
        val userColor: Color = Color.White,
    ) {
        val whoseTurn: Color get() = if (moves.size % 2 == 0) Color.White else Color.Black
    }

    private val jni = StockfishJni()
    private val engine = StockfishEngine(jni)
    private val recognizer = VoskRecognizer(context)
    private val assets = EngineAssets(context)
    private val commandParser = VoiceCommandParser(MoveParser())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gameLock = Mutex()
    private var listenJob: Job? = null

    /** Coroutine running an in-progress "read moves" announcement. Canceled when the user
     *  opens a new listen window or fires [cancelListenWindow] / [stopGame]. */
    private var readJob: Job? = null

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
            currentGameId = repo.startNewGame(
                skillLevel = s.skillLevel,
                userColor = s.userColor.name,
            )

            val userIsWhite = s.userColor == Color.White
            _state.update {
                State(
                    status = if (userIsWhite) Status.WaitingForUser else Status.Thinking,
                    legalMoves = if (userIsWhite) initialLegal else emptyList(),
                    userColor = s.userColor,
                )
            }
            refreshBoard()
            if (userIsWhite) {
                tts.speak("your move")
            } else {
                // Engine moves first when the human plays black.
                runEngineReply()
            }
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

    /** Snapshot the engine's current position into [State.board]. Best-effort. */
    private suspend fun refreshBoard() {
        val board = runCatching { engine.currentBoard() }.getOrNull() ?: return
        _state.update { it.copy(board = board) }
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

            val resumedUserColor = runCatching { Color.valueOf(game.userColor) }
                .getOrDefault(Color.White)

            // persistMoves always runs after the engine reply, so saved state is at the user's
            // turn and the last move (if any) was played by the engine.
            val lastEngineMove = moves.lastOrNull()
            _state.update {
                State(
                    status = Status.WaitingForUser,
                    moves = moves,
                    lastEngineMove = lastEngineMove,
                    legalMoves = legal,
                    userColor = resumedUserColor,
                )
            }
            refreshBoard()
            tts.speak("game resumed")
            if (lastEngineMove != null) {
                tts.speak("last engine move: ${speakMoveText(lastEngineMove, currentSettings().notation)}")
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
                readJob?.cancel()
                readJob = null
                runCatching { tts.stop() }
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
        // Cancel any in-progress move-reading so its TTS queue stops.
        readJob?.cancel()
        readJob = null
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

    /** Silently aborts an in-progress listen window. Also cancels a move-read if one is
     *  running, so PREVIOUS doubles as "stop reading". */
    fun cancelListenWindow() {
        listenJob?.cancel()
        listenJob = null
        if (readJob != null) {
            readJob?.cancel()
            readJob = null
            tts.stop()
        }
    }

    /**
     * Submits a UCI move from a non-voice input path (board long-press, debug UI). Same
     * validation as [processSpoken]: must be the user's turn and the move must be in
     * [State.legalMoves]; otherwise TTS "illegal" and no state change.
     */
    suspend fun submitTextMove(uci: String) {
        if (_state.value.status != Status.WaitingForUser) {
            Log.w(TAG, "submitTextMove ignored in status=${_state.value.status}")
            return
        }
        if (uci !in _state.value.legalMoves) {
            Log.w(TAG, "submitTextMove: \"$uci\" not in legal moves")
            tts.speak("illegal")
            return
        }
        playUserMove(uci)
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
        val moveText = speakMoveText(move, s.notation)
        val full = if (s.verbose) {
            val mover = if (_state.value.whoseTurn == Color.White) "black" else "white"
            "$mover plays $moveText. your turn."
        } else {
            moveText
        }
        tts.speak(full)
    }

    /**
     * Routes a UCI move to the right spoken-text generator based on notation setting. For
     * [SettingsRepository.Notation.Standard] we convert to SAN first (since SAN needs full
     * game context — disambiguation, captures, check), then phonemize via [SanSpeech].
     */
    private fun speakMoveText(move: String, notation: SettingsRepository.Notation): String =
        when (notation) {
            SettingsRepository.Notation.LetterByLetter,
            SettingsRepository.Notation.Nato -> MoveSpeech.spoken(move, notation)
            SettingsRepository.Notation.Standard -> {
                val san = SanConverter.toSan(_state.value.moves).lastOrNull() ?: move
                SanSpeech.spoken(san)
            }
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
            VoiceCommand.TakeBack -> takeBack()
            VoiceCommand.WhoseTurn -> handleWhoseTurn()
            VoiceCommand.HowManyMoves -> handleHowManyMoves()
            VoiceCommand.ListPieces -> handleListPieces()
            VoiceCommand.DescribeBoard -> handleDescribeBoard()
            VoiceCommand.Resign -> handleResign()
            VoiceCommand.NewGame -> handleNewGame()
            is VoiceCommand.WhatsOn -> handleWhatsOn(command.square)
            VoiceCommand.ReadMoves -> handleReadMoves()
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

    /**
     * Removes the last user+engine move pair. Public so non-voice paths (board view Undo
     * button) can call directly. Same effect as the "take back" / "undo" voice command.
     */
    suspend fun takeBack() = gameLock.withLock {
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
        refreshBoard()
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
        val phrase = describePieces(board, _state.value.userColor)
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
        currentGameId = repo.startNewGame(
            skillLevel = s.skillLevel,
            userColor = s.userColor.name,
        )

        val userIsWhite = s.userColor == Color.White
        _state.value = State(
            status = if (userIsWhite) Status.WaitingForUser else Status.Thinking,
            legalMoves = if (userIsWhite) initialLegal else emptyList(),
            userColor = s.userColor,
        )
        refreshBoard()
        if (userIsWhite) {
            tts.speak("new game. your move.")
        } else {
            tts.speak("new game")
            runEngineReply()
        }
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

    /**
     * Reads every move in chronological order via TTS with [READ_GAP_MS] between each.
     * Spawns a separate [readJob] in [scope] so the surrounding listen window can close
     * (status → WaitingForUser) and the user can interrupt with another tap. The job is
     * cancelled by [openListenWindow], [cancelListenWindow], and [stopGame].
     */
    private fun handleReadMoves() {
        val snapshot = _state.value.moves.toList()
        if (snapshot.isEmpty()) {
            tts.speak("no moves to read")
            return
        }
        val notation = currentSettings().notation
        readJob?.cancel()
        readJob = scope.launch {
            // Pre-compute SAN list once when in Standard mode — SanConverter rebuilds the
            // whole list per call, O(n²) if we did it move-by-move.
            val sanList: List<String>? = if (notation == SettingsRepository.Notation.Standard) {
                SanConverter.toSan(snapshot)
            } else {
                null
            }
            tts.speakAndWait("reading move history")
            delay(READ_INTRO_GAP_MS)
            for ((idx, uci) in snapshot.withIndex()) {
                val moveText = when (notation) {
                    SettingsRepository.Notation.LetterByLetter,
                    SettingsRepository.Notation.Nato ->
                        MoveSpeech.spoken(uci, notation)
                    SettingsRepository.Notation.Standard ->
                        SanSpeech.spoken(sanList?.getOrNull(idx) ?: uci)
                }
                val moveNumber = idx / 2 + 1
                val isWhite = idx % 2 == 0
                val prefix = if (isWhite) "move $moveNumber. " else ""
                tts.speakAndWait(prefix + moveText)
                delay(READ_GAP_MS)
            }
            tts.speakAndWait("end of history")
            readJob = null
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
                runEngineReply()
            } catch (t: Throwable) {
                Log.w(TAG, "playUserMove failed", t)
                _state.update { it.copy(status = Status.Error, message = t.message ?: "unknown") }
            }
        }
    }

    /**
     * Runs one engine ply at the current position. Caller is responsible for [gameLock]
     * being held and [engine.setPosition] having been called with the current move list.
     *
     * Updates [_state] with the engine's move, refreshed legal moves, board, and
     * appropriate game-over classification:
     *   - engine returns no move ("(none)"/"0000"/"") → UserWin (only reachable after the
     *     user's move just delivered mate; impossible at startpos)
     *   - perft after engine's reply is empty → UserLoss (mate or stalemate — we don't
     *     yet distinguish; would need to query check status)
     *
     * Persists moves, marks the game complete in the right cases, announces the engine's
     * move via [announceEngineMove], and TTS "game over" when applicable.
     */
    private suspend fun runEngineReply() {
        val reply = engine.goMoveTime(currentSettings().moveTimeMs)
        val noReply = reply == "(none)" || reply == "0000" || reply.isBlank()
        if (noReply) {
            _state.update {
                it.copy(
                    status = Status.GameOver,
                    lastEngineMove = null,
                    legalMoves = emptyList(),
                    message = "Game over",
                )
            }
            refreshBoard()
            persistMoves()
            finalizeGame(GameResult.UserWin)
            tts.speak("game over")
            return
        }
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
        refreshBoard()
        persistMoves()
        if (userHasNoMoves) {
            finalizeGame(GameResult.UserLoss)
        }
        announceEngineMove(reply)
        if (userHasNoMoves) tts.speak("game over")
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
        /** Pause between successive moves during a "read moves" announcement. */
        const val READ_GAP_MS = 2_000L
        /** Slightly shorter pause after the "reading move history" intro. */
        const val READ_INTRO_GAP_MS = 500L
    }
}
