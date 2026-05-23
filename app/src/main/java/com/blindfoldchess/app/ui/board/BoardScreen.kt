package com.blindfoldchess.app.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindfoldchess.app.BlindfoldChessApp
import com.blindfoldchess.app.chess.Board
import com.blindfoldchess.app.chess.Color
import com.blindfoldchess.app.chess.Fen
import com.blindfoldchess.app.data.SettingsRepository
import com.blindfoldchess.app.engine.GameController
import com.blindfoldchess.app.service.ChessGameService

/**
 * Board view screen — opt-in visualization aid + non-voice move input.
 *
 *   Tap a square         → toggle fog (peek / re-hide)
 *   Long-press a square  → move state machine: select own piece, then long-press a legal
 *                          target to move. Long-press the selected square again to cancel.
 *
 * Pulls the current game's board from [ChessGameService.gameState] (falls back to startpos
 * when no game is active). Long-press is only active when a game is in progress and it's
 * the user's turn — otherwise the board is read-only.
 *
 * Default fog state comes from [SettingsRepository.Settings.fogMode]; after entry the user
 * toggles individual squares with taps. "Reset" in the top bar re-applies the default.
 * "Reveal all" clears fog so you can read the position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlindfoldChessApp
    val gameState by ChessGameService.gameState.collectAsStateWithLifecycle()
    val serviceState by ChessGameService.state.collectAsStateWithLifecycle()
    val settings by app.settingsRepository.settings.collectAsStateWithLifecycle()

    // v1 hardcodes the user as White (matches GameController).
    val userColor = Color.White

    val displayBoard: Board = gameState.board ?: Fen.parse(STARTPOS_FEN)

    var fogged by remember { mutableStateOf(defaultFog(displayBoard, settings.fogMode, userColor)) }
    val resetKey by remember(settings.fogMode) {
        derivedStateOf { settings.fogMode }
    }
    LaunchedEffect(resetKey) {
        fogged = defaultFog(displayBoard, settings.fogMode, userColor)
    }

    var selectedSquare by remember { mutableStateOf<String?>(null) }

    // Drop the selection whenever the position / status changes (the move went through, or
    // the engine replied, or the game ended).
    LaunchedEffect(gameState.moves.size, gameState.status) {
        selectedSquare = null
    }

    val canMove = serviceState.gameActive &&
        gameState.status == GameController.Status.WaitingForUser

    val legalTargets: Set<String> = remember(selectedSquare, gameState.legalMoves) {
        val src = selectedSquare ?: return@remember emptySet()
        gameState.legalMoves
            .filter { it.length >= 4 && it.startsWith(src) }
            .mapTo(mutableSetOf()) { it.substring(2, 4) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val turn = if (gameState.whoseTurn == Color.White) "White" else "Black"
                    Text("Board · $turn to move")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        fogged = defaultFog(displayBoard, settings.fogMode, userColor)
                    }) { Text("Reset") }
                    TextButton(onClick = { fogged = emptySet() }) { Text("Reveal all") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BoardView(
                board = displayBoard,
                fogged = fogged,
                selectedSquare = selectedSquare,
                legalTargets = legalTargets,
                userColor = userColor,
                onSquareTap = { sq ->
                    fogged = if (sq in fogged) fogged - sq else fogged + sq
                },
                onSquareLongPress = { sq ->
                    if (!canMove) return@BoardView
                    selectedSquare = handleLongPress(
                        square = sq,
                        previousSelection = selectedSquare,
                        board = displayBoard,
                        legalMoves = gameState.legalMoves,
                        userColor = userColor,
                        onMove = { uci -> ChessGameService.submitMove(context, uci) },
                    )
                },
            )

            val instruction = when {
                !serviceState.gameActive ->
                    "No game in progress. Start a game on the main screen to enable move input."
                gameState.status == GameController.Status.WaitingForUser && selectedSquare == null ->
                    "Tap to peek/fog. Long-press your piece to select it for a move."
                gameState.status == GameController.Status.WaitingForUser ->
                    "Long-press a green-dotted square to move, or long-press the selected square again to cancel."
                gameState.status == GameController.Status.Thinking ->
                    "Engine is thinking — moves disabled until reply."
                gameState.status == GameController.Status.Listening ->
                    "Listening for voice input — moves disabled."
                gameState.status == GameController.Status.GameOver ->
                    "Game over. Moves disabled."
                else -> when (settings.fogMode) {
                    SettingsRepository.FogMode.FogAll ->
                        "All squares fogged by default. Tap to peek; tap again to re-fog."
                    SettingsRepository.FogMode.FogOpponent ->
                        "Opponent's pieces hidden. Tap any square to toggle its fog."
                    SettingsRepository.FogMode.RevealAll ->
                        "Tap any square to fog it (e.g. to test your memory of one piece)."
                }
            }
            Text(instruction, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Pure logic for the long-press state machine. Returns the new selection (null = no
 * selection). Calls [onMove] when a legal move is determined; selection is cleared in
 * that case via the caller's LaunchedEffect on `moves.size`, so we just return null here.
 */
private fun handleLongPress(
    square: String,
    previousSelection: String?,
    board: Board,
    legalMoves: List<String>,
    userColor: Color,
    onMove: (String) -> Unit,
): String? {
    if (previousSelection == null) {
        // Selecting: only allow squares with the user's piece that has at least one legal move.
        val piece = board.pieceAt(square)
        return if (piece?.color == userColor && legalMoves.any { it.startsWith(square) }) {
            square
        } else {
            null
        }
    }
    if (square == previousSelection) {
        // Cancel.
        return null
    }
    // Try the move. Auto-promote to queen if a promotion is required.
    val plain = "$previousSelection$square"
    val candidates = listOf(plain, plain + "q")
    val legal = candidates.firstOrNull { it in legalMoves }
    if (legal != null) {
        onMove(legal)
        return null
    }
    // Not a legal target. If user tapped another own piece, switch selection; otherwise
    // leave selection in place so they can try again.
    val piece = board.pieceAt(square)
    return if (piece?.color == userColor && legalMoves.any { it.startsWith(square) }) {
        square
    } else {
        previousSelection
    }
}

private fun defaultFog(
    board: Board,
    mode: SettingsRepository.FogMode,
    userColor: Color,
): Set<String> = when (mode) {
    SettingsRepository.FogMode.RevealAll -> emptySet()
    SettingsRepository.FogMode.FogAll -> ALL_SQUARES
    SettingsRepository.FogMode.FogOpponent -> {
        val opponent = if (userColor == Color.White) Color.Black else Color.White
        board.squares.withIndex()
            .filter { (_, p) -> p != null && p.color == opponent }
            .map { (idx, _) -> Board.squareName(idx) }
            .toSet()
    }
}

private val ALL_SQUARES: Set<String> = buildSet {
    for (file in 'a'..'h') for (rank in 1..8) add("$file$rank")
}

private const val STARTPOS_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
