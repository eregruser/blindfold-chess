package io.github.eregruser.blindfoldchess.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eregruser.blindfoldchess.BlindfoldChessApp
import io.github.eregruser.blindfoldchess.chess.Board
import io.github.eregruser.blindfoldchess.chess.Color
import io.github.eregruser.blindfoldchess.chess.Fen
import io.github.eregruser.blindfoldchess.chess.SanConverter
import io.github.eregruser.blindfoldchess.data.SettingsRepository
import io.github.eregruser.blindfoldchess.engine.GameController
import io.github.eregruser.blindfoldchess.service.ChessGameService

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
 * Default fog state comes from [SettingsRepository.Settings.fogMode] and is re-applied
 * on every position change so fogged squares move with the pieces (essential for the
 * FogOpponent mode). Per-square taps are transient peeks/re-hides scoped to the current
 * position — once a move lands they reset to the new default. "Reset" in the top bar
 * also re-applies the default mid-position; "Reveal all" clears fog entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlindfoldChessApp
    val gameState by ChessGameService.gameState.collectAsStateWithLifecycle()
    val serviceState by ChessGameService.state.collectAsStateWithLifecycle()
    val settings by app.settingsRepository.settings.collectAsStateWithLifecycle()

    // Comes from the live game state. Falls back to the user's preference when no game is
    // active (so board orientation matches what they'd see when they start one).
    val userColor: Color = if (serviceState.gameActive) gameState.userColor else settings.userColor

    val displayBoard: Board = gameState.board ?: Fen.parse(STARTPOS_FEN)

    // Re-derive default fog whenever the board, mode, or user side changes. Keys on
    // `displayBoard` (data class with structural equality on its 64-square list) — not
    // on moves.size — because GameController.runEngineReply emits the new moves first
    // and then calls refreshBoard() as a separate update; keying on moves.size would
    // fire against the still-stale board and leave the just-moved opponent piece
    // visible until the *next* move.
    //
    // remember(keys) is used (rather than a LaunchedEffect that mutates a separate
    // state) so the fresh fog set lands in the same composition as the new board.
    // A LaunchedEffect runs its block after commit, which would cause one frame where
    // the new piece renders unfogged before fog catches up.
    //
    // Per-square taps are intentionally scoped to the current position: when the
    // board changes the key changes, remember re-initializes the state, and tap
    // overrides are wiped. "Reset" still works mid-position to do the same wipe by
    // hand without waiting for the next move.
    var fogged by remember(displayBoard, settings.fogMode, userColor) {
        mutableStateOf(defaultFog(displayBoard, settings.fogMode, userColor))
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
                    TextButton(
                        onClick = { ChessGameService.takeBack(context) },
                        enabled = serviceState.gameActive &&
                            gameState.status == GameController.Status.WaitingForUser &&
                            gameState.moves.size >= 2,
                    ) { Text("Undo") }
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

            val statusMessage: String? = when {
                !serviceState.gameActive ->
                    "No game in progress. Start one on the main screen to enable moves."
                gameState.status == GameController.Status.Thinking ->
                    "Engine is thinking…"
                gameState.status == GameController.Status.Listening ->
                    "Listening for voice input…"
                gameState.status == GameController.Status.GameOver ->
                    "Game over."
                else -> null
            }
            if (statusMessage != null) {
                Text(statusMessage, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            MovesPanel(
                moves = gameState.moves,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

/**
 * Move history below the board, most-recent pair at the top so the user doesn't have to
 * scroll for the latest moves. Pairs are numbered with their original (chronological)
 * move number — only the list order is reversed.
 */
@Composable
private fun MovesPanel(
    moves: List<String>,
    modifier: Modifier = Modifier,
) {
    if (moves.isEmpty()) {
        Text(
            text = "No moves yet.",
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
        return
    }
    val sanMoves = remember(moves) { SanConverter.toSan(moves) }
    // Pair (white, black) then reverse so newest pair is at the top, but keep each pair's
    // original move number for display.
    val numberedPairsNewestFirst = sanMoves.chunked(2)
        .mapIndexed { idx, pair -> idx + 1 to pair }
        .asReversed()

    Column(modifier = modifier) {
        Text(
            text = "Moves",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(numberedPairsNewestFirst, key = { (n, _) -> n }) { (moveNumber, pair) ->
                val white = pair[0]
                val black = pair.getOrNull(1) ?: ""
                Text(
                    text = "%2d. %-6s   %s".format(moveNumber, white, black),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                )
            }
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
