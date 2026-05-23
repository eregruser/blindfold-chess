package com.blindfoldchess.app.ui.board

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blindfoldchess.app.chess.Board
import com.blindfoldchess.app.chess.Color
import com.blindfoldchess.app.chess.Piece
import com.blindfoldchess.app.chess.PieceType

/**
 * 8×8 board renderer. Square colors alternate; rank labels run down the left edge and file
 * labels along the bottom. Pieces use Unicode glyphs (♔♕♖♗♘♙ / ♚♛♜♝♞♟). Fogged squares
 * render as solid gray with no piece glyph.
 *
 * Tap toggles fog; long-press is forwarded as the move-selection gesture (caller decides
 * whether to select, move, or ignore based on game state).
 *
 * @param board current position. null is treated as an empty board.
 * @param fogged set of square names (e.g. "e4") to hide.
 * @param selectedSquare currently-selected source square for a long-press move, or null.
 * @param legalTargets squares the selected piece can legally move to. Empty when no selection.
 * @param onSquareTap fired on single tap. Caller typically toggles fog.
 * @param onSquareLongPress fired on long-press. Caller drives the move state machine.
 * @param userColor controls orientation — user's rank 1 sits at the bottom.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoardView(
    board: Board?,
    fogged: Set<String>,
    selectedSquare: String?,
    legalTargets: Set<String>,
    onSquareTap: (String) -> Unit,
    onSquareLongPress: (String) -> Unit,
    userColor: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    val ranks = if (userColor == Color.White) (8 downTo 1).toList() else (1..8).toList()
    val files = if (userColor == Color.White) ('a'..'h').toList() else ('h' downTo 'a').toList()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        for (rank in ranks) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .width(LABEL_GUTTER)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(rank.toString(), style = MaterialTheme.typography.labelSmall)
                }
                for (file in files) {
                    val squareName = "$file$rank"
                    val isLight = ((file - 'a') + (rank - 1)) % 2 == 1
                    val isFogged = squareName in fogged
                    val isSelected = squareName == selectedSquare
                    val isTarget = squareName in legalTargets
                    val piece = board?.pieceAt(squareName)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(squareColor(isLight, isFogged))
                            .then(
                                if (isSelected) Modifier.border(3.dp, SELECTED_BORDER) else Modifier
                            )
                            .combinedClickable(
                                onClick = { onSquareTap(squareName) },
                                onLongClick = { onSquareLongPress(squareName) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!isFogged && piece != null) {
                            Text(
                                text = piece.glyph().toString(),
                                fontSize = 38.sp,
                                fontWeight = FontWeight.Normal,
                                color = if (piece.color == Color.White) WHITE_PIECE else BLACK_PIECE,
                            )
                        }
                        // Legal-target indicator: small filled dot on top of background/piece.
                        if (isTarget) {
                            Box(
                                modifier = Modifier
                                    .size(if (piece != null) 12.dp else 14.dp)
                                    .clip(CircleShape)
                                    .background(TARGET_DOT),
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(LABEL_GUTTER),
        ) {
            Box(modifier = Modifier.width(LABEL_GUTTER))
            for (file in files) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(file.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun squareColor(isLight: Boolean, isFogged: Boolean): UiColor = when {
    isFogged && isLight -> FOG_LIGHT
    isFogged -> FOG_DARK
    isLight -> LIGHT_SQ
    else -> DARK_SQ
}

private fun Piece.glyph(): Char = when (color) {
    Color.White -> when (type) {
        PieceType.King -> '♔'
        PieceType.Queen -> '♕'
        PieceType.Rook -> '♖'
        PieceType.Bishop -> '♗'
        PieceType.Knight -> '♘'
        PieceType.Pawn -> '♙'
    }
    Color.Black -> when (type) {
        PieceType.King -> '♚'
        PieceType.Queen -> '♛'
        PieceType.Rook -> '♜'
        PieceType.Bishop -> '♝'
        PieceType.Knight -> '♞'
        PieceType.Pawn -> '♟'
    }
}

private val LABEL_GUTTER = 24.dp

private val LIGHT_SQ = UiColor(0xFFF0D9B5)
private val DARK_SQ = UiColor(0xFFB58863)
private val FOG_LIGHT = UiColor(0xFF8A8A8A)
private val FOG_DARK = UiColor(0xFF5C5C5C)
private val WHITE_PIECE = UiColor(0xFFFFFFFF)
private val BLACK_PIECE = UiColor(0xFF000000)
private val SELECTED_BORDER = UiColor(0xFF4CAF50)
private val TARGET_DOT = UiColor(0xCC4CAF50)
