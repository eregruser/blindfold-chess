package com.blindfoldchess.app.chess

/**
 * Turns a Standard Algebraic Notation move string into a phrase the platform TTS engine
 * can pronounce naturally.
 *
 *   "e4"      -> "e four"
 *   "Nf3"     -> "knight f three"
 *   "Nbd2"    -> "knight b d two"               (disambiguation by file)
 *   "Nb1d2"   -> "knight b one d two"           (full square disambiguation)
 *   "exd5"    -> "e takes d five"
 *   "Nxe5"    -> "knight takes e five"
 *   "Bxe5+"   -> "bishop takes e five, check"
 *   "Nf3#"    -> "knight f three, mate"
 *   "e8=Q"    -> "e eight promotes to queen"
 *   "exd8=Q+" -> "e takes d eight promotes to queen, check"
 *   "O-O"     -> "castle kingside"
 *   "O-O-O"   -> "castle queenside"
 *
 * Defensive: if a token doesn't parse cleanly, returns the input string unchanged so the
 * TTS engine at least says *something*.
 */
object SanSpeech {

    fun spoken(san: String): String {
        var s = san.trim()
        if (s.isEmpty()) return san

        // Strip annotation suffixes (!, ?, !?, etc.) — they don't affect the move.
        s = s.trimEnd('!', '?')

        // Check / mate markers — captured before stripping.
        val isMate = s.endsWith("#")
        val isCheck = !isMate && s.endsWith("+")
        s = s.trimEnd('+', '#')

        // Castling — both modern (O) and old-style (0) notations.
        when (s) {
            "O-O", "0-0" -> return wrap("castle kingside", isCheck, isMate)
            "O-O-O", "0-0-0" -> return wrap("castle queenside", isCheck, isMate)
        }

        // Promotion: "...=Q", "...=N", etc.
        var promotionPart = ""
        val eq = s.indexOf('=')
        if (eq in 1 until s.length - 1) {
            val pieceChar = s[eq + 1]
            promotionPart = " promotes to ${pieceName(pieceChar)}"
            s = s.substring(0, eq)
        }

        // Piece prefix (uppercase K/Q/R/B/N). Pawn moves have no prefix.
        var piecePrefix = ""
        if (s.isNotEmpty() && s[0] in "KQRBN") {
            piecePrefix = pieceName(s[0])
            s = s.substring(1)
        }

        // Capture marker ('x'). Strip it after noting.
        val hasCapture = 'x' in s
        s = s.replace("x", "")

        // What's left = [optional disambiguation chars] + destination (2 chars).
        if (s.length < 2) return san // malformed
        val destination = s.takeLast(2)
        val disambig = s.dropLast(2)

        val parts = mutableListOf<String>()
        if (piecePrefix.isNotEmpty()) parts += piecePrefix
        if (disambig.isNotEmpty()) {
            // Each disambig char becomes a separate spoken token (file letter, rank word, or both).
            parts += disambig.map { letterOrDigit(it) }.joinToString(" ")
        }
        if (hasCapture) parts += "takes"
        parts += "${letterOrDigit(destination[0])} ${letterOrDigit(destination[1])}"

        return wrap(parts.joinToString(" ") + promotionPart, isCheck, isMate)
    }

    private fun letterOrDigit(c: Char): String = when (c) {
        '1' -> "one"
        '2' -> "two"
        '3' -> "three"
        '4' -> "four"
        '5' -> "five"
        '6' -> "six"
        '7' -> "seven"
        '8' -> "eight"
        else -> c.toString()
    }

    private fun pieceName(c: Char): String = when (c.uppercaseChar()) {
        'K' -> "king"
        'Q' -> "queen"
        'R' -> "rook"
        'B' -> "bishop"
        'N' -> "knight"
        else -> c.toString()
    }

    private fun wrap(text: String, isCheck: Boolean, isMate: Boolean): String = when {
        isMate -> "$text, mate"
        isCheck -> "$text, check"
        else -> text
    }
}
