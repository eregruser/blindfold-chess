package com.blindfoldchess.app.voice

/**
 * Builds a Vosk grammar string that enumerates every possible chess move utterance,
 * regardless of legality (per DESIGN.md Phase 3 "static full-board grammar").
 *
 * Phase 4 will replace this with a per-move legal-move grammar (~30 phrases instead of
 * ~4k), lifting accuracy further; this static grammar is the cheap intermediate.
 *
 * Field-tested decisions (Phase 3d iteration):
 *
 *  - **NATO files only.** The 4 single-letter file forms (a–h spoken) are constantly
 *    mistranscribed by the small Vosk model. "echo / bravo / etc." are 5+ syllables
 *    and unambiguous. The parser still accepts letter input for text-mode contexts.
 *  - **No "to" connector.** "to" and "two" are perfect English homophones, so including
 *    "echo two to echo four" alongside "echo two echo four" makes the recognizer
 *    coin-flip on every utterance. We standardize on the no-connector form.
 *  - **Castles use two-word forms.** "kingside" / "queenside" appear to be OOV in the
 *    small model; "king side" / "queen side" decompose into common words.
 *  - **Promotion uses no "to" connector.** Same homophone reasoning.
 *  - **`[unk]` fallback included** so out-of-grammar audio surfaces as "didn't catch that"
 *    rather than being force-matched to a near-miss phrase.
 */
object ChessGrammar {

    /** Cached singleton — generation is ~5ms; reuse across listen windows. */
    val fullBoard: String by lazy { buildFullBoard() }

    /**
     * Builds a grammar tightly constrained to the supplied legal-move list. Phase 4 wire-up
     * queries `go perft 1` after every engine reply to refresh this per turn, so the
     * recognizer is choosing between ~30 phrases rather than ~4.3k. Accuracy gain is large.
     *
     * Each legal UCI move yields one or more phrasings:
     *   - normal move "e2e4"  → "echo two echo four"
     *   - promotion "e7e8q"   → "echo seven echo eight promote queen"
     *   - castle e1g1/e8g8    → ["castle king side", "short castle"]
     *   - castle e1c1/e8c8    → ["castle queen side", "long castle"]
     *
     * "[unk]" is always appended so out-of-grammar audio still surfaces as a failure case
     * rather than being force-matched to a legal move.
     */
    fun legal(legalMoves: List<String>): String {
        val phrases = LinkedHashSet<String>(legalMoves.size * 2)
        for (uci in legalMoves) phrases.addAll(phrasingsFor(uci))
        phrases.add("[unk]")
        return phrases.joinToString(prefix = "[\"", separator = "\", \"", postfix = "\"]")
    }

    private fun phrasingsFor(uci: String): List<String> {
        // Castling — UCI king-from/king-to encoding (standard, not Chess960).
        when (uci) {
            "e1g1", "e8g8" -> return listOf("castle king side", "short castle")
            "e1c1", "e8c8" -> return listOf("castle queen side", "long castle")
        }

        if (uci.length !in 4..5) return emptyList()
        val fromFile = uci[0]
        val fromRank = uci[1]
        val toFile = uci[2]
        val toRank = uci[3]
        if (fromFile !in 'a'..'h' || fromRank !in '1'..'8' ||
            toFile !in 'a'..'h' || toRank !in '1'..'8'
        ) return emptyList()

        val fromFW = NATO_FILES.getValue(fromFile)
        val toFW = NATO_FILES.getValue(toFile)
        val fromRW = RANK_WORDS.getValue(fromRank)
        val toRW = RANK_WORDS.getValue(toRank)
        val base = "$fromFW $fromRW $toFW $toRW"

        if (uci.length == 5) {
            val piece = PROMOTION_BY_LETTER[uci[4].lowercaseChar()] ?: return emptyList()
            return listOf("$base promote $piece")
        }
        return listOf(base)
    }

    private val PROMOTION_BY_LETTER = mapOf(
        'q' to "queen", 'r' to "rook", 'b' to "bishop", 'n' to "knight",
    )

    private fun buildFullBoard(): String {
        val phrases = ArrayList<String>(5_000)

        for (fromFile in FILES) {
            for (fromRank in RANKS) {
                for (toFile in FILES) {
                    for (toRank in RANKS) {
                        if (fromFile == toFile && fromRank == toRank) continue
                        val fromFW = NATO_FILES.getValue(fromFile)
                        val toFW = NATO_FILES.getValue(toFile)
                        val fromRW = RANK_WORDS.getValue(fromRank)
                        val toRW = RANK_WORDS.getValue(toRank)
                        phrases.add("$fromFW $fromRW $toFW $toRW")

                        val isPromotion =
                            (fromRank == '7' && toRank == '8') ||
                                (fromRank == '2' && toRank == '1')
                        if (isPromotion) {
                            for (piece in PROMOTION_PIECES) {
                                phrases.add("$fromFW $fromRW $toFW $toRW promote $piece")
                            }
                        }
                    }
                }
            }
        }

        phrases.add("castle king side")
        phrases.add("castle queen side")
        phrases.add("short castle")
        phrases.add("long castle")

        // Vosk grammar JSON: ["phrase 1", ..., "[unk]"]. No phrase contains characters
        // that need JSON escaping.
        return phrases.joinToString(
            prefix = "[\"",
            separator = "\", \"",
            postfix = "\", \"[unk]\"]",
        )
    }

    private val FILES = ('a'..'h').toList()
    private val RANKS = ('1'..'8').toList()
    private val NATO_FILES = mapOf(
        'a' to "alpha", 'b' to "bravo", 'c' to "charlie", 'd' to "delta",
        'e' to "echo", 'f' to "foxtrot", 'g' to "golf", 'h' to "hotel",
    )
    private val RANK_WORDS = mapOf(
        '1' to "one", '2' to "two", '3' to "three", '4' to "four",
        '5' to "five", '6' to "six", '7' to "seven", '8' to "eight",
    )
    private val PROMOTION_PIECES = listOf("queen", "rook", "bishop", "knight")
}
