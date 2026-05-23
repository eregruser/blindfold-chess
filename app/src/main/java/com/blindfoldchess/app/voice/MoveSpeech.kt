package com.blindfoldchess.app.voice

/**
 * Converts a UCI long-algebraic move string into a phrase the platform TTS engine can
 * pronounce clearly. Per-character with spaces so TTS reads "e seven e five" instead of
 * trying to pronounce "e7e5" as a word.
 *
 *   "e2e4"  -> "e 2 e 4"
 *   "e7e8q" -> "e 7 e 8 queen"
 *   "e1g1"  -> "castle kingside"        (white king from e1 to g1 can only be O-O)
 *   "e1c1"  -> "castle queenside"
 *   "e8g8"  -> "castle kingside"
 *   "e8c8"  -> "castle queenside"
 *
 * For Phase 3c this is the only TTS pathway for engine replies; settings-driven notation
 * (NATO etc.) arrives in Phase 7.
 */
object MoveSpeech {

    fun spoken(uci: String): String {
        if (uci.isEmpty() || uci == "(none)" || uci == "0000") return "game over"

        // Castle detection — UCI uses the king's start/end square. e1g1/e8g8 can only be O-O,
        // e1c1/e8c8 can only be O-O-O.
        when (uci) {
            "e1g1", "e8g8" -> return "castle kingside"
            "e1c1", "e8c8" -> return "castle queenside"
        }

        if (uci.length < 4) return uci  // malformed; speak as-is

        val from = "${uci[0]} ${uci[1]}"
        val to = "${uci[2]} ${uci[3]}"
        val promo = if (uci.length >= 5) " ${pieceName(uci[4])}" else ""
        return "$from $to$promo"
    }

    private fun pieceName(c: Char): String = when (c.lowercaseChar()) {
        'q' -> "queen"
        'r' -> "rook"
        'b' -> "bishop"
        'n' -> "knight"
        else -> c.toString()
    }
}
