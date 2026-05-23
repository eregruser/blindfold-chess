package com.blindfoldchess.app.voice

/**
 * Turns a recognized spoken phrase into a structured chess move.
 *
 * Accepts long-algebraic and NATO-phonetic phrasings simultaneously — the recognizer's
 * grammar (Phase 4) will restrict the input to one set per game. Numbers can be either
 * digits or spelled words. The connector word "to" is optional.
 *
 * Castling is returned as a structured [Parsed.CastleKingside] / [Parsed.CastleQueenside]
 * because mapping to UCI (`e1g1` vs `e8g8`) requires knowing whose turn it is — the
 * caller (engine layer) does that resolution.
 *
 * Examples
 *   "e 2 to e 4"                             -> Normal(e2, e4)
 *   "e two to e four"                        -> Normal(e2, e4)
 *   "echo two echo four"                     -> Normal(e2, e4)
 *   "e7 to e8 promote to queen"              -> Normal(e7, e8, promotion='q')
 *   "castle kingside" / "castle short"       -> CastleKingside
 *   "castle queenside" / "castle long"       -> CastleQueenside
 */
class MoveParser {

    sealed class Parsed {
        data class Normal(
            val from: String,
            val to: String,
            val promotion: Char? = null,
        ) : Parsed() {
            fun toUci(): String = "$from$to${promotion ?: ""}"
        }

        object CastleKingside : Parsed()
        object CastleQueenside : Parsed()
    }

    fun parse(input: String): Parsed? {
        val tokens = tokenize(input)
        if (tokens.isEmpty()) return null

        parseCastle(tokens)?.let { return it }
        return parseNormalMove(tokens)
    }

    private fun tokenize(input: String): List<String> =
        input.lowercase()
            .split(Regex("[\\s.,\\-]+"))
            .filter { it.isNotBlank() }

    private fun parseCastle(tokens: List<String>): Parsed? {
        val hasCastle = tokens.any { it == "castle" || it == "castles" || it == "castling" }
        if (!hasCastle) return null
        val isKingside = tokens.any { it == "kingside" || it == "short" || it == "king" }
        val isQueenside = tokens.any { it == "queenside" || it == "long" || it == "queen" }
        return when {
            isKingside && !isQueenside -> Parsed.CastleKingside
            isQueenside && !isKingside -> Parsed.CastleQueenside
            else -> null
        }
    }

    private fun parseNormalMove(tokens: List<String>): Parsed? {
        val squares = mutableListOf<String>()
        var promotion: Char? = null
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]

            // promotion: "promote to X" or "promotes to X" or "promote X"
            if (token == "promote" || token == "promotes" || token == "promoting") {
                val skipTo = if (i + 1 < tokens.size && tokens[i + 1] == "to") 1 else 0
                val pieceIdx = i + 1 + skipTo
                if (pieceIdx < tokens.size) {
                    val piece = parsePiece(tokens[pieceIdx])
                    if (piece != null) {
                        promotion = piece
                        i = pieceIdx + 1
                        continue
                    }
                }
            }

            // square in one token: "e2", "h7"
            val oneToken = parseSquareOneToken(token)
            if (oneToken != null) {
                squares.add(oneToken)
                i++
                continue
            }

            // square in two tokens: "e 2", "echo two"
            val file = parseFile(token)
            if (file != null && i + 1 < tokens.size) {
                val rank = parseRank(tokens[i + 1])
                if (rank != null) {
                    squares.add("$file$rank")
                    i += 2
                    continue
                }
            }

            // anything else (incl. "to", filler) — skip
            i++
        }

        if (squares.size != 2) return null
        return Parsed.Normal(squares[0], squares[1], promotion)
    }

    private fun parseSquareOneToken(token: String): String? {
        if (token.length != 2) return null
        val file = parseFile(token.substring(0, 1)) ?: return null
        val rank = parseRank(token.substring(1, 2)) ?: return null
        return "$file$rank"
    }

    private fun parseFile(token: String): Char? {
        NATO_TO_FILE[token]?.let { return it }
        if (token.length == 1 && token[0] in 'a'..'h') return token[0]
        return null
    }

    private fun parseRank(token: String): Char? {
        SPELLED_TO_DIGIT[token]?.let { return it }
        if (token.length == 1 && token[0] in '1'..'8') return token[0]
        return null
    }

    private fun parsePiece(token: String): Char? = PIECE_NAMES[token]

    private companion object {
        val NATO_TO_FILE = mapOf(
            "alpha" to 'a', "bravo" to 'b', "charlie" to 'c', "delta" to 'd',
            "echo" to 'e', "foxtrot" to 'f', "golf" to 'g', "hotel" to 'h',
        )
        val SPELLED_TO_DIGIT = mapOf(
            "one" to '1', "two" to '2', "three" to '3', "four" to '4',
            "five" to '5', "six" to '6', "seven" to '7', "eight" to '8',
        )
        val PIECE_NAMES = mapOf(
            "queen" to 'q',
            "rook" to 'r',
            "bishop" to 'b',
            "knight" to 'n', "horse" to 'n',
        )
    }
}
