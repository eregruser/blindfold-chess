package com.blindfoldchess.app.chess

import android.util.Log
import com.github.bhlangonijr.chesslib.Board as ChessLibBoard
import com.github.bhlangonijr.chesslib.move.Move as ChessLibMove
import com.github.bhlangonijr.chesslib.move.MoveList

/**
 * Converts UCI long-algebraic moves (e.g. `"e2e4"`, `"e1g1"`, `"e7e8q"`) into Standard
 * Algebraic Notation (`"e4"`, `"O-O"`, `"e8=Q"`) using the chesslib library.
 *
 * Display-only — the engine and persistence layers still speak UCI. We deliberately don't
 * mutate stored data; UI screens call this on the fly with `remember(moves)` to cache.
 *
 * If any move in the sequence fails to parse (corrupted DB, mid-stream illegal move, etc.),
 * falls back to returning the original UCI strings so the UI never shows a half-converted
 * list.
 */
object SanConverter {

    private const val TAG = "SanConverter"

    fun toSan(uciMoves: List<String>): List<String> {
        if (uciMoves.isEmpty()) return emptyList()
        return try {
            val board = ChessLibBoard()
            uciMoves.map { uci ->
                val move = ChessLibMove(uci, board.sideToMove)
                val san = encodeSingle(board, move)
                board.doMove(move)
                san
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SAN conversion failed for moves=$uciMoves; falling back to UCI", t)
            uciMoves
        }
    }

    /**
     * Encode one move's SAN using a one-element [MoveList] anchored at the current position.
     * Defensively splits on whitespace and takes the last token — chesslib's toSan() output
     * has historically been just the move text, but newer versions occasionally prefix a
     * move number like `"1. e4"` which we strip here.
     */
    private fun encodeSingle(board: ChessLibBoard, move: ChessLibMove): String {
        val ml = MoveList(board.fen)
        ml.add(move)
        val text = ml.toSan().trim()
        if (text.isEmpty()) return move.toString()
        return text.split(Regex("\\s+")).last()
    }
}
