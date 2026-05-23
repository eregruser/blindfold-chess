package com.blindfoldchess.app.voice

import com.blindfoldchess.app.data.SettingsRepository

/**
 * Converts a UCI long-algebraic move string into a phrase the platform TTS engine can
 * pronounce clearly. Per-character output (e.g. "e seven e five") was the v1 default;
 * Phase 7 settings allow switching to NATO ("echo seven echo five") for consistency with
 * the input grammar.
 *
 *   "e2e4"  notation=LetterByLetter → "e 2 e 4"
 *   "e2e4"  notation=Nato           → "echo two echo four"
 *   "e7e8q" notation=Nato           → "echo seven echo eight queen"
 *   "e1g1"                          → "castle kingside"           (notation-independent)
 *   "e1c1"                          → "castle queenside"
 *   "(none)" / "0000" / ""          → "game over"
 *
 * Verbosity (prepending "<side> plays" / appending ". your turn.") is applied at the
 * GameController call site so it can include game context this helper doesn't have.
 */
object MoveSpeech {

    fun spoken(uci: String, notation: SettingsRepository.Notation): String {
        if (uci.isEmpty() || uci == "(none)" || uci == "0000") return "game over"

        // Standard UCI castle encoding — king's start/end square only.
        when (uci) {
            "e1g1", "e8g8" -> return "castle kingside"
            "e1c1", "e8c8" -> return "castle queenside"
        }

        if (uci.length < 4) return uci

        val from = squareSpoken(uci[0], uci[1], notation)
        val to = squareSpoken(uci[2], uci[3], notation)
        val promo = if (uci.length >= 5) " ${pieceName(uci[4])}" else ""
        return "$from $to$promo"
    }

    private fun squareSpoken(
        file: Char,
        rank: Char,
        notation: SettingsRepository.Notation,
    ): String = when (notation) {
        SettingsRepository.Notation.LetterByLetter -> "$file $rank"
        SettingsRepository.Notation.Nato -> "${NATO_FILES[file] ?: file} ${RANK_WORDS[rank] ?: rank}"
    }

    private fun pieceName(c: Char): String = when (c.lowercaseChar()) {
        'q' -> "queen"
        'r' -> "rook"
        'b' -> "bishop"
        'n' -> "knight"
        else -> c.toString()
    }

    private val NATO_FILES = mapOf(
        'a' to "alpha", 'b' to "bravo", 'c' to "charlie", 'd' to "delta",
        'e' to "echo", 'f' to "foxtrot", 'g' to "golf", 'h' to "hotel",
    )
    private val RANK_WORDS = mapOf(
        '1' to "one", '2' to "two", '3' to "three", '4' to "four",
        '5' to "five", '6' to "six", '7' to "seven", '8' to "eight",
    )
}
