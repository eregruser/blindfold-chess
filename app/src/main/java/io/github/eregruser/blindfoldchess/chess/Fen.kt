package io.github.eregruser.blindfoldchess.chess

/**
 * FEN (Forsyth-Edwards Notation) parser. Accepts the 6-field standard FEN string emitted
 * by Stockfish's `d` command and any other UCI engine output.
 *
 *   rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1
 *   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *   placement                                  side cast ep half full
 *
 * Placement is rank-8 first (top), files a-h within each rank, digits = empty squares,
 * letters = pieces (uppercase=white, lowercase=black).
 */
object Fen {

    fun parse(fen: String): Board {
        val fields = fen.trim().split(Regex("\\s+"))
        require(fields.size >= 4) { "FEN needs at least 4 fields, got ${fields.size}: \"$fen\"" }
        val placement = fields[0]
        val sideToMove = parseSide(fields[1])
        val castling = fields[2]
        val ep = fields[3].takeIf { it != "-" }
        val halfmove = fields.getOrNull(4)?.toIntOrNull() ?: 0
        val fullmove = fields.getOrNull(5)?.toIntOrNull() ?: 1
        return Board(
            squares = parsePlacement(placement),
            sideToMove = sideToMove,
            castlingRights = castling,
            enPassantSquare = ep,
            halfmoveClock = halfmove,
            fullmoveNumber = fullmove,
        )
    }

    private fun parseSide(token: String): Color = when (token.lowercase()) {
        "w" -> Color.White
        "b" -> Color.Black
        else -> error("FEN side-to-move must be 'w' or 'b', got '$token'")
    }

    /**
     * Placement string ranks ordered 8 → 1; within each rank, files a → h. We return an
     * array where index 0 = a1 (white's perspective) so square arithmetic is natural.
     */
    private fun parsePlacement(placement: String): List<Piece?> {
        val ranks = placement.split('/')
        require(ranks.size == 8) { "FEN placement needs 8 ranks, got ${ranks.size}" }
        val squares = arrayOfNulls<Piece>(64)
        // FEN rank index 0 = rank 8 (top); board rank 0 = rank 1 (bottom for white)
        for (fenRankIdx in ranks.indices) {
            val rank = 7 - fenRankIdx // board rank
            var file = 0
            for (ch in ranks[fenRankIdx]) {
                when {
                    ch.isDigit() -> file += (ch - '0')
                    else -> {
                        require(file in 0..7) { "FEN rank overflow at '$ch' in '${ranks[fenRankIdx]}'" }
                        squares[file + 8 * rank] = pieceFromChar(ch)
                        file++
                    }
                }
            }
            require(file == 8) { "FEN rank '${ranks[fenRankIdx]}' filled $file squares, need 8" }
        }
        return squares.toList()
    }

    private fun pieceFromChar(ch: Char): Piece {
        val color = if (ch.isUpperCase()) Color.White else Color.Black
        val type = when (ch.uppercaseChar()) {
            'K' -> PieceType.King
            'Q' -> PieceType.Queen
            'R' -> PieceType.Rook
            'B' -> PieceType.Bishop
            'N' -> PieceType.Knight
            'P' -> PieceType.Pawn
            else -> error("Unknown FEN piece char: '$ch'")
        }
        return Piece(type, color)
    }
}
