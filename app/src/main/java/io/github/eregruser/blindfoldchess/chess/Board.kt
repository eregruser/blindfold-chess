package io.github.eregruser.blindfoldchess.chess

/**
 * Immutable chess position. Source of truth is whatever FEN string parsed it; this is just a
 * convenient view for query commands ("what's on f3", "list my pieces") and for tracking
 * whose turn it is.
 *
 * Move generation and legality live in Stockfish via `go perft 1` (see [io.github.eregruser.blindfoldchess.engine.StockfishEngine]).
 * We deliberately do not implement chess rules here.
 */
data class Board(
    /** 64 squares indexed 0=a1 ... 63=h8 (file + 8*rank, with rank 0 = white's first rank). */
    val squares: List<Piece?>,
    val sideToMove: Color,
    val castlingRights: String, // FEN castling field, e.g. "KQkq", "Kq", or "-"
    val enPassantSquare: String?, // algebraic like "e3", or null
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    init {
        require(squares.size == 64) { "expected 64 squares, got ${squares.size}" }
    }

    fun pieceAt(square: String): Piece? = squares[squareIndex(square)]

    fun piecesOf(color: Color): List<Pair<String, Piece>> =
        squares.withIndex().mapNotNull { (idx, p) ->
            if (p != null && p.color == color) squareName(idx) to p else null
        }

    companion object {
        /** "e2" -> 12 (file 4 + 8*rank 1). */
        fun squareIndex(name: String): Int {
            require(name.length == 2) { "bad square: $name" }
            val file = name[0] - 'a'
            val rank = name[1] - '1'
            require(file in 0..7 && rank in 0..7) { "bad square: $name" }
            return file + 8 * rank
        }

        /** 12 -> "e2". */
        fun squareName(index: Int): String {
            require(index in 0..63) { "bad index: $index" }
            return "${('a' + index % 8)}${('1' + index / 8)}"
        }
    }
}

enum class Color { White, Black }

enum class PieceType(val letter: Char, val spoken: String) {
    King('K', "king"),
    Queen('Q', "queen"),
    Rook('R', "rook"),
    Bishop('B', "bishop"),
    Knight('N', "knight"),
    Pawn('P', "pawn"),
}

data class Piece(val type: PieceType, val color: Color) {
    /** FEN letter: uppercase for white, lowercase for black. */
    val fenChar: Char = if (color == Color.White) type.letter else type.letter.lowercaseChar()
}
